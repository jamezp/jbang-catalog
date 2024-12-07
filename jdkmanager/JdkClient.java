/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2023 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jdkmanager;

import static jdkmanager.BaseCommand.WORK_DIR;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
abstract class JdkClient {
    private static final int OWNER_READ_FILEMODE = 0b100_000_000; // 0400
    private static final int OWNER_WRITE_FILEMODE = 0b010_000_000; // 0200
    private static final int OWNER_EXEC_FILEMODE = 0b001_000_000; // 0100

    private static final int GROUP_READ_FILEMODE = 0b000_100_000; // 0040
    private static final int GROUP_WRITE_FILEMODE = 0b000_010_000; // 0020
    private static final int GROUP_EXEC_FILEMODE = 0b000_001_000; // 0010

    private static final int OTHERS_READ_FILEMODE = 0b000_000_100; // 0004
    private static final int OTHERS_WRITE_FILEMODE = 0b000_000_010; // 0002
    private static final int OTHERS_EXEC_FILEMODE = 0b000_000_001; // 0001
    protected final BaseCommand command;
    private final String baseUri;

    JdkClient(final BaseCommand command, final String baseUri) {
        this.command = command;
        this.baseUri = baseUri;
    }

    abstract int latestLts() throws IOException, InterruptedException;

    abstract Status<Versions> getVersions(boolean refresh) throws IOException, InterruptedException;

    abstract Status<Properties> getJavaInfo(Path javaHome) throws IOException, InterruptedException;

    abstract CompletionStage<Integer> download(Path javaHome, int version, boolean quiet);

    UriBuilder uriBuilder() {
        return new UriBuilder(baseUri);
    }

    static Path untargz(final Path archiveFile) throws IOException {
        Path firstDir = null;

        try (TarArchiveInputStream in = new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(archiveFile)))) {
            TarArchiveEntry entry;
            while ((entry = in.getNextTarEntry()) != null) {
                Path extractTarget = WORK_DIR.resolve(entry.getName());
                if (entry.isDirectory()) {
                    final Path dir = Files.createDirectories(extractTarget);
                    if (firstDir == null) {
                        firstDir = dir;
                    }
                } else {
                    Files.createDirectories(extractTarget.getParent());
                    Files.copy(in, extractTarget, StandardCopyOption.REPLACE_EXISTING);
                    final var mode = entry.getMode();
                    if (mode != 0 && BaseCommand.isNotWindows()) {
                        Files.setPosixFilePermissions(extractTarget, toPosixFilePermissions(mode));
                        Files.setLastModifiedTime(extractTarget, entry.getLastModifiedTime());
                    }
                }
            }
            return firstDir;
        }
    }

    static void unzip(final Path zip, final Path targetDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            final Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                final ZipArchiveEntry zipEntry = entries.nextElement();
                final Path entry = Path.of(zipEntry.getName());
                // Skip the base directory of the zip file
                if (entry.getNameCount() == 1) {
                    continue;
                }
                // Remove the base directory of the zip
                final var target = targetDir.resolve(entry.subpath(1, entry.getNameCount())).normalize();
                if (zipEntry.isDirectory()) {
                    Files.createDirectories(target);
                } else if (zipEntry.isUnixSymlink()) {
                    final Scanner s = new Scanner(zipFile.getInputStream(zipEntry)).useDelimiter("\\A");
                    final String result = s.hasNext() ? s.next() : "";
                    Files.createSymbolicLink(target, Path.of(result));
                } else {
                    if (Files.notExists(target.getParent())) {
                        Files.createDirectories(target.getParent());
                    }
                    try (InputStream zis = zipFile.getInputStream(zipEntry)) {
                        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    final int mode = zipEntry.getUnixMode();
                    if (mode != 0 && BaseCommand.isNotWindows()) {
                        Files.setPosixFilePermissions(target, toPosixFilePermissions(mode));
                    }
                }
            }
        }
    }

    static String getFilename(final String contentDisposition) {
        final String filename;
        final var key = "filename=";
        int start = contentDisposition.indexOf(key);
        if (start >= 0) {
            start = start + key.length();
            final int end = contentDisposition.lastIndexOf(';');
            if (end > start) {
                filename = contentDisposition.substring(start, end);
            } else {
                filename = contentDisposition.substring(start);
            }
        } else {
            return null;
        }
        return filename;
    }

    static void deleteDirectory(final Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    static Set<PosixFilePermission> toPosixFilePermissions(final int octalFileMode) {
        final Set<PosixFilePermission> permissions = new LinkedHashSet<>();
        // Owner
        if ((octalFileMode & OWNER_READ_FILEMODE) == OWNER_READ_FILEMODE) {
            permissions.add(PosixFilePermission.OWNER_READ);
        }
        if ((octalFileMode & OWNER_WRITE_FILEMODE) == OWNER_WRITE_FILEMODE) {
            permissions.add(PosixFilePermission.OWNER_WRITE);
        }
        if ((octalFileMode & OWNER_EXEC_FILEMODE) == OWNER_EXEC_FILEMODE) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        // Group
        if ((octalFileMode & GROUP_READ_FILEMODE) == GROUP_READ_FILEMODE) {
            permissions.add(PosixFilePermission.GROUP_READ);
        }
        if ((octalFileMode & GROUP_WRITE_FILEMODE) == GROUP_WRITE_FILEMODE) {
            permissions.add(PosixFilePermission.GROUP_WRITE);
        }
        if ((octalFileMode & GROUP_EXEC_FILEMODE) == GROUP_EXEC_FILEMODE) {
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        // Others
        if ((octalFileMode & OTHERS_READ_FILEMODE) == OTHERS_READ_FILEMODE) {
            permissions.add(PosixFilePermission.OTHERS_READ);
        }
        if ((octalFileMode & OTHERS_WRITE_FILEMODE) == OTHERS_WRITE_FILEMODE) {
            permissions.add(PosixFilePermission.OTHERS_WRITE);
        }
        if ((octalFileMode & OTHERS_EXEC_FILEMODE) == OTHERS_EXEC_FILEMODE) {
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        return Set.copyOf(permissions);
    }

    record Status<T>(int exitStatus, T body, Supplier<String> rawData) {
        public Status(final int exitStatus, final T body, final String rawData) {
            this(exitStatus, body, () -> rawData);
        }
    }

    @SuppressWarnings("SameParameterValue")
    static class UriBuilder {

        private final String base;
        private final List<String> paths;
        private final Map<String, String> queryParams;

        private UriBuilder(final String base) {
            this.base = base;
            paths = new ArrayList<>();
            queryParams = new LinkedHashMap<>();
        }

        UriBuilder path(final String path) {
            if (path != null) {
                Collections.addAll(paths, path.split("/"));
            }
            return this;
        }

        UriBuilder path(final Object path) {
            if (path != null) {
                Collections.addAll(paths, String.valueOf(path));
            }
            return this;
        }

        UriBuilder queryParam(final String name, final String value) {
            queryParams.put(name, value);
            return this;
        }

        URI build() {
            final StringBuilder builder = new StringBuilder();
            builder.append(base);
            paths.stream()
                    .filter(path -> !path.isBlank())
                    .forEach(path -> builder.append('/').append(URLEncoder.encode(path, StandardCharsets.UTF_8)));
            final AtomicBoolean first = new AtomicBoolean(true);
            queryParams.forEach((name, value) -> {
                if (first.compareAndSet(true, false)) {
                    builder.append('?');
                } else {
                    builder.append('&');
                }
                builder.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            });
            return URI.create(builder.toString());
        }
    }

    enum SizeUnit {
        BYTE(1L, "B") {
            @Override
            public String toString(final long size) {
                return size + "B";
            }
        },
        KILOBYTE(BYTE, "KB"),
        MEGABYTE(KILOBYTE, "MB"),
        GIGABYTE(MEGABYTE, "GB"),
        TERABYTE(GIGABYTE, "TB"),
        PETABYTE(TERABYTE, "PB"),
        EXABYTE(TERABYTE, "EB"),

        ;
        private static final DecimalFormat FORMAT = new DecimalFormat("#.##");
        private final long sizeInBytes;
        private final String abbreviation;

        SizeUnit(final long sizeInBytes, final String abbreviation) {
            this.sizeInBytes = sizeInBytes;
            this.abbreviation = abbreviation;
        }

        SizeUnit(final SizeUnit base, final String abbreviation) {
            this.sizeInBytes = base.sizeInBytes << 10;
            this.abbreviation = abbreviation;
        }

        /**
         * Returns the abbreviation for the unit.
         *
         * @return the abbreviation for the unit
         */
        public String abbreviation() {
            return abbreviation;
        }

        /**
         * Converts the given size to bytes from this unit. For example {@code SizeUnit.KILOBYTES.toBytes(1L)} would return
         * 1024.
         *
         * @param size the size to convert
         *
         * @return the size in bytes
         */
        public long toBytes(final long size) {
            return Math.multiplyExact(sizeInBytes, size);
        }

        /**
         * Converts the given size to the given unit to this unit.
         *
         * @param size the size to convert
         * @param unit the unit to convert the size to
         *
         * @return the converted units
         */
        public double convert(final long size, final SizeUnit unit) {
            if (unit == BYTE) {
                return toBytes(size);
            }
            final long bytes = toBytes(size);
            return ((double) bytes / unit.sizeInBytes);
        }

        /**
         * Converts the size to a human-readable string format.
         * <p>
         * For example {@code SizeUnit.KILOBYTE.toString(1024L)} would return "1 KB".
         * </p>
         *
         * @param size the size, in bytes
         *
         * @return a human-readable size
         */
        public String toString(final long size) {
            return FORMAT.format((double) size / sizeInBytes) + abbreviation;
        }

        /**
         * Converts the size, in bytes, to a human-readable form. For example {@code 1024} bytes return "1 KB".
         *
         * @param size the size, in bytes, to convert
         *
         * @return a human-readable size
         */
        public static String toHumanReadable(final long size) {
            if (size == 0L) {
                return "0B";
            }
            final SizeUnit[] values = values();
            for (int i = values.length - 1; i >= 0; i--) {
                final SizeUnit unit = values[i];
                if (size >= unit.sizeInBytes) {
                    return unit.toString(size);
                }
            }
            return size + "B";
        }
    }

    static class KnownProperties implements Iterable<String> {
        static final List<String> PROPERTIES = List.of(
                "java.version",
                "java.version.date",
                "java.vendor",
                "java.vendor.url",
                "java.vendor.version",
                "java.home",
                "java.vm.specification.version",
                "java.vm.specification.vendor",
                "java.vm.specification.name",
                "java.vm.version",
                "java.vm.vendor",
                "java.vm.name",
                "java.specification.version",
                "java.specification.vendor",
                "java.specification.name",
                "java.class.version",
                // Keeping this as it's a standard property, but doesn't do us much good here
                // "java.class.path",
                //"java.library.path",
                "java.io.tmpdir",
                "os.name",
                "os.arch",
                "os.version",
                "file.separator",
                "path.separator",
                "line.separator",
                "user.name",
                "user.home",
                "user.dir"
        );

        @Override
        public Iterator<String> iterator() {
            return PROPERTIES.iterator();
        }

        static boolean missing(final String property) {
            return property != null && !PROPERTIES.contains(property);
        }
    }
}
