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
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class Environment {
    private static final String ARCH;
    private static final OS CURRENT_OS;
    private static final boolean SUPPORTS_POSIX;

    static final Path USER_DIR = Path.of(System.getProperty("user.home"));
    static final Path WORK_DIR = USER_DIR.resolve(".jdk-manager");
    // TODO (jrp) determine how we use cache and tmp
    static final Path CACHE_DIR = WORK_DIR.resolve("cache");
    static final Path TMP_DIR = WORK_DIR.resolve("tmp");

    static {
        SUPPORTS_POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

        final var archProp = System.getProperty("os.arch")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        if (archProp.matches("^(x8664|amd64|ia32e|em64t|x64)$")) {
            ARCH = "x64";
        } else if (archProp.matches("^(x8632|x86|i[3-6]86|ia32|x32)$")) {
            ARCH = "x32";
        } else if (archProp.matches("^(aarch64)$")) {
            ARCH = "aarch64";
        } else if (archProp.matches("^(ppc64)$")) {
            ARCH = "ppc64";
        } else if (archProp.matches("^(ppc64le)$")) {
            ARCH = "ppc64le";
        } else if (archProp.matches("^(s390x)$")) {
            ARCH = "s390x";
        } else if (archProp.matches("^(arm64)$")) {
            ARCH = "arm64";
        } else {
            ARCH = archProp;
        }
        final var osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.startsWith("mac") || osName.startsWith("osx")) {
            CURRENT_OS = OS.mac;
        } else if (osName.startsWith("linux")) {
            if (Files.exists(Paths.get("/etc/alpine-release"))) {
                CURRENT_OS = OS.alpine_linux;
            } else {
                CURRENT_OS = OS.linux;
            }
        } else if (osName.startsWith("win")) {
            CURRENT_OS = OS.windows;
        } else if (osName.startsWith("aix")) {
            CURRENT_OS = OS.aix;
        } else {
            CURRENT_OS = OS.unknown;
        }
        // Attempt to create the needed directories
        createDirectory(WORK_DIR);
        createDirectory(CACHE_DIR);
        createDirectory(TMP_DIR);
        final DosFileAttributeView dosFileAttributeView = Files.getFileAttributeView(WORK_DIR, DosFileAttributeView.class);
        if (dosFileAttributeView != null) {
            try {
                dosFileAttributeView.setHidden(true);
            } catch (IOException ignore) {
            }
        }
    }

    enum OS {
        linux,
        alpine_linux,
        mac,
        windows,
        aix,
        unknown;
    }

    static boolean supportsPosix() {
        return SUPPORTS_POSIX;
    }

    static boolean isWindows() {
        return os() == OS.windows;
    }

    static boolean isNotWindows() {
        return os() != OS.windows;
    }

    static String arch() {
        return ARCH;
    }

    static OS os() {
        return CURRENT_OS;
    }

    static void deleteCache() throws IOException {
        deleteDirectory(CACHE_DIR);
        createDirectory(CACHE_DIR);
    }

    static Cache resolveCacheFile(final String name) {
        final var cacheFile = CACHE_DIR.resolve(name);
        return new Cache(cacheFile);
    }

    private static void createDirectory(final Path dir, final FileAttributeView... views) {
        try {
            if (Files.notExists(dir)) {
                if (SUPPORTS_POSIX) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectories(dir);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(String.format("Failed to create directory %s", dir), e);
        }
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
}
