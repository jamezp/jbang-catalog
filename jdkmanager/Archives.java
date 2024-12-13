/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2024 Red Hat, Inc., and individual contributors
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Scanner;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class Archives {
    private static final int OWNER_READ_FILEMODE = 0b100_000_000; // 0400
    private static final int OWNER_WRITE_FILEMODE = 0b010_000_000; // 0200
    private static final int OWNER_EXEC_FILEMODE = 0b001_000_000; // 0100

    private static final int GROUP_READ_FILEMODE = 0b000_100_000; // 0040
    private static final int GROUP_WRITE_FILEMODE = 0b000_010_000; // 0020
    private static final int GROUP_EXEC_FILEMODE = 0b000_001_000; // 0010

    private static final int OTHERS_READ_FILEMODE = 0b000_000_100; // 0004
    private static final int OTHERS_WRITE_FILEMODE = 0b000_000_010; // 0002
    private static final int OTHERS_EXEC_FILEMODE = 0b000_000_001; // 0001

    static Path untargz(final Path archiveFile, final Path targetDir) throws IOException {
        Path firstDir = null;

        try (TarArchiveInputStream in = new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(archiveFile)))) {
            TarArchiveEntry entry;
            while ((entry = in.getNextTarEntry()) != null) {
                Path extractTarget = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    final Path dir = Files.createDirectories(extractTarget);
                    if (firstDir == null) {
                        firstDir = dir;
                    }
                } else {
                    Files.createDirectories(extractTarget.getParent());
                    Files.copy(in, extractTarget, StandardCopyOption.REPLACE_EXISTING);
                    final var mode = entry.getMode();
                    if (mode != 0 && Environment.isNotWindows()) {
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
                    if (mode != 0 && Environment.isNotWindows()) {
                        Files.setPosixFilePermissions(target, toPosixFilePermissions(mode));
                    }
                }
            }
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
}
