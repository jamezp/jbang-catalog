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
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonWriter;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class JdkClient {
    private static final int OWNER_READ_FILEMODE = 0b100_000_000; // 0400
    private static final int OWNER_WRITE_FILEMODE = 0b010_000_000; // 0200
    private static final int OWNER_EXEC_FILEMODE = 0b001_000_000; // 0100

    private static final int GROUP_READ_FILEMODE = 0b000_100_000; // 0040
    private static final int GROUP_WRITE_FILEMODE = 0b000_010_000; // 0020
    private static final int GROUP_EXEC_FILEMODE = 0b000_001_000; // 0010

    private static final int OTHERS_READ_FILEMODE = 0b000_000_100; // 0004
    private static final int OTHERS_WRITE_FILEMODE = 0b000_000_010; // 0002
    private static final int OTHERS_EXEC_FILEMODE = 0b000_000_001; // 0001
    private final BaseCommand command;

    JdkClient(final BaseCommand command) {
        this.command = command;
    }

    int latestLts() throws IOException, InterruptedException {
        final var json = getVersions(false);
        return json.body().getInt("most_recent_lts");
    }

    Status<JsonObject> getVersions(final boolean refresh) throws IOException, InterruptedException {
        final Path dir = BaseCommand.TMP_DIR.resolve("jdk-manager");
        boolean resolve = refresh;
        if (Files.notExists(dir)) {
            Files.createDirectories(dir);
            resolve = true;
        }
        final Path file = dir.resolve("current-versions");
        if (Files.exists(file)) {
            final var lastModified = Files.getLastModifiedTime(file).toInstant();
            final var lastModDate = LocalDate.ofInstant(lastModified, ZoneId.systemDefault());
            if (lastModDate.isBefore(LocalDate.now())) {
                resolve = true;
            }
        } else {
            resolve = true;
        }
        final JsonObject json;
        if (resolve) {
            final var uri = UriBuilder.of()
                    .path("info")
                    .path("available_releases")
                    .build();
            final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            final HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("accept", "application/json")
                    .build();
            final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() == 200) {
                    try (JsonWriter writer = Json.createWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                        json = Json.createReader(in).readObject();
                        writer.writeObject(json);
                    }
                } else {
                    if (response.statusCode() == 404) {
                        final JsonObject errorMessage = Json.createObjectBuilder()
                                .add("errorMessage", "Could not find resource at " + uri)
                                .build();
                        return new Status<>(1, errorMessage, new String(in.readAllBytes()));
                    }
                    try (JsonReader reader = Json.createReader(in)) {
                        final JsonObject body = reader.readObject();
                        command.printError("Could not determine the available versions: %s", body.getString("errorMessage"));
                        return new Status<>(1, body, body.toString());
                    }
                }
            }
        } else {
            try (JsonReader reader = Json.createReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
                json = reader.readObject();
            }
        }
        return new Status<>(0, json, json.toString());
    }

    Status<Properties> getJavaInfo(final Path javaHome) throws IOException, InterruptedException {
        final String[] commands = {
                javaHome.resolve("bin").resolve("java").toString(),
                "-XshowSettings:properties",
                "-version"
        };
        final Path tempFile = Files.createTempFile("jdk-info", ".out");
        try {
            final Process process = new ProcessBuilder(commands)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(tempFile.toFile()))
                    .start();
            final int exitCode = process.waitFor();
            final Properties properties = new Properties();
            final List<String> lines = Files.readAllLines(tempFile);
            if (exitCode == 0) {
                // Read each line looking for "Property settings:"
                boolean inProperties = false;
                for (var line : lines) {
                    if (line.trim().startsWith("Property settings:")) {
                        inProperties = true;
                        continue;
                    }
                    if (line.isBlank() && inProperties) {
                        break;
                    }
                    if (inProperties) {
                        final var trimmed = line.trim();
                        final int i = trimmed.indexOf('=');
                        if (i > 0) {
                            properties.put(trimmed.substring(0, i).trim(), trimmed.substring(i + 1).trim());
                        }
                    }
                }
                try (InputStream in = Files.newInputStream(tempFile)) {
                    properties.load(in);
                }
            }
            return new Status<>(exitCode, properties, () -> {
                final StringBuilder builder = new StringBuilder();
                for (String line : lines) {
                    builder.append(line).append(System.lineSeparator());
                }
                return builder.toString();
            });
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    CompletionStage<Integer> download(final Path javaHome, final int version, final boolean quiet) {

        if (command.refresh || Files.notExists(javaHome)) {
            final var earlyAccess = isEarlyAccess(version);
            final var uri = downloadUri(version, earlyAccess);
            if (command.verbose) {
                command.print("Downloading from %s", uri);
            }
            final HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            final HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenApply(response -> {
                        try {
                            try (InputStream in = response.body()) {
                                if (response.statusCode() == 200) {
                                    // Get the content-disposition to get the file name
                                    final var contentDisposition = response.headers()
                                            .firstValue("content-disposition")
                                            .orElseThrow(
                                                    () -> new RuntimeException(String.format("Failed to find the content disposition in %s", response.headers()
                                                            .map())));
                                    // Find the file name
                                    final String filename = getFilename(contentDisposition);
                                    if (filename == null) {
                                        command.printError("Could not determine the filename based on the response headers.");
                                        return 1;
                                    }
                                    final Path download = WORK_DIR.resolve(filename);
                                    if (Files.notExists(download)) {
                                        if (quiet) {
                                            Files.copy(in, download, StandardCopyOption.REPLACE_EXISTING);
                                        } else {
                                            final long contentLength = response.headers()
                                                    .firstValueAsLong("content-length")
                                                    .orElse(-1L);
                                            try (
                                                    ProgressBar progressBar = new ProgressBarBuilder()
                                                            .setInitialMax(contentLength)
                                                            .setSpeedUnit(ChronoUnit.MINUTES)
                                                            .setTaskName("Downloading: ")
                                                            .setUnit(SizeUnit.MEGABYTE.abbreviation(), SizeUnit.MEGABYTE.toBytes(1))
                                                            .setStyle(ProgressBarStyle.ASCII)
                                                            .build();
                                                    OutputStream out = Files.newOutputStream(download)
                                            ) {
                                                final byte[] buffer = new byte[4096];
                                                int len;
                                                while ((len = in.read(buffer)) > 0) {
                                                    out.write(buffer, 0, len);
                                                    progressBar.stepBy(len);
                                                }
                                                if (command.verbose) {
                                                    command.print("Downloaded %s%n", download);
                                                }
                                            }
                                        }
                                    }
                                    deleteDirectory(javaHome);
                                    Files.createDirectories(javaHome);
                                    final Path path;
                                    if (filename.endsWith("tar.gz") || filename.endsWith("tgz")) {
                                        path = untargz(download);
                                        if (path == null) {
                                            command.printError("Could not untar the path %s.", download);
                                            return 1;
                                        }
                                        Files.move(path, javaHome, StandardCopyOption.REPLACE_EXISTING);
                                    } else {
                                        unzip(download, javaHome);
                                    }
                                    // Delete the download
                                    Files.deleteIfExists(download);
                                } else {
                                    try (JsonReader reader = Json.createReader(in)) {
                                        command.printError("Request failed for version %d: %s", version, reader.readObject()
                                                .getString("errorMessage"));
                                    }
                                    return 1;
                                }
                            }
                        } catch (IOException e) {
                            command.printError("Failed to install JDK %s: %s", version, e.getMessage());
                            return 1;
                        }
                        return 0;
                    });
        }
        return CompletableFuture.completedFuture(0);
    }

    private boolean isEarlyAccess(final int version) {
        try {
            final Status<JsonObject> status = getVersions(false);
            if (status.exitStatus() == 0) {
                final JsonObject json = status.body();
                final var tip = json.getInt("tip_version");
                final var featureVersion = json.getInt("most_recent_feature_version");
                final var recentRelease = json.getInt("most_recent_feature_release");
                return version > recentRelease;
            }
        } catch (IOException | InterruptedException e) {
            command.printError("Failed to determine the available JDK versions: %s", e.getMessage());
        }
        return false;
    }

    private URI downloadUri(final int version, final boolean earlyAccess) {
        return UriBuilder.of()
                .path("binary")
                .path("latest")
                .path(version)
                .path(earlyAccess ? "ea" : "ga")
                .path(BaseCommand.os())
                .path(BaseCommand.arch())
                .path("jdk")
                .path("hotspot")
                .path("normal")
                .path("eclipse")
                .queryParam("project", "jdk")
                .build();
    }

    private static Path untargz(final Path archiveFile) throws IOException {
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

    private static void unzip(final Path zip, final Path targetDir) throws IOException {
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

    private static String getFilename(final String contentDisposition) {
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

    private static void deleteDirectory(final Path dir) throws IOException {
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

    private static Set<PosixFilePermission> toPosixFilePermissions(final int octalFileMode) {
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
    private static class UriBuilder {

        private final String base;
        private final List<String> paths;
        private final Map<String, String> queryParams;

        private UriBuilder() {
            this.base = "https://api.adoptium.net/v3";
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

        static UriBuilder of() {
            return new UriBuilder();
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

    private static enum SizeUnit {
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

}
