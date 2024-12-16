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

package jdkmanager.util;

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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.stream.Stream;

import jdkmanager.client.CacheFile;

/**
 * The current environment.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class Environment {
    private static final String ARCH;
    private static final OS CURRENT_OS;
    private static final boolean SUPPORTS_POSIX;

    private static final Path USER_DIR = Path.of(System.getProperty("user.home"));
    public static final Path WORK_DIR = USER_DIR.resolve(".jdk-manager");
    private static final Path CACHE_DIR = WORK_DIR.resolve("cache");
    private static final Path TMP_DIR = WORK_DIR.resolve("tmp");

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

    public enum OS {
        linux,
        alpine_linux,
        mac,
        windows,
        aix,
        unknown;
    }

    /**
     * Indicates we are running in a Windows environment.
     *
     * @return {@code true} if this is a Windows environment
     */
    public static boolean isWindows() {
        return os() == OS.windows;
    }

    /**
     * Indicates if we are <em>not</em> running in a Windows environment.
     *
     * @return {@code true} if we are not running in a Windows envrionment
     */
    public static boolean isNotWindows() {
        return os() != OS.windows;
    }

    /**
     * Returns the resolved architecture .
     *
     * @return the resolved architecture
     */
    public static String arch() {
        return ARCH;
    }

    /**
     * Returns the current operating system.
     *
     * @return the current operating system
     */
    public static OS os() {
        return CURRENT_OS;
    }

    /**
     * Resolves the directory for the Java Home based on the distribution and version.
     *
     * @param distribution the distribution
     * @param version      the version
     *
     * @return the path for the Java Home
     */
    public static Path resolveJavaHome(final String distribution, final int version) {
        final Path dir = Environment.WORK_DIR.resolve(distribution).resolve("jdk-" + version);
        createDirectory(dir);
        return dir;
    }

    /**
     * Resolves a path to the temporary directory.
     *
     * @param path the path to resolve
     *
     * @return the path to the temporary directory
     */
    public static Path createTempDirectory(final String path) {
        final var result = TMP_DIR.resolve(path);
        if (Files.notExists(result)) {
            createDirectory(result);
        }
        return result;
    }

    /**
     * Resolves a path to the temporary directory.
     *
     * @param paths the paths to resolve
     *
     * @return the path to the temporary directory
     */
    public static Path createTempDirectory(final String... paths) {
        Path result = TMP_DIR;
        for (String path : paths) {
            result = result.resolve(path);
        }
        if (Files.notExists(result.getParent())) {
            createDirectory(result.getParent());
        }
        return result;
    }

    /**
     * Resolves a path to the temporary directory and creates the file.
     *
     * @param paths the paths to resolve
     *
     * @return the path to the temporary file
     */
    public static Path createTempFile(final String... paths) {
        Path result = TMP_DIR;
        for (String path : paths) {
            result = result.resolve(path);
        }
        final var parent = result.getParent();
        if (parent != null && Files.notExists(parent)) {
            createDirectory(parent);
        }
        if (Files.notExists(result)) {
            try {
                if (SUPPORTS_POSIX) {
                    Files.createFile(result, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createFile(result);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create temp file", e);
            }
        }
        return result;
    }

    /**
     * Resolves a path to the temporary directory and creates the file.
     *
     * @param paths the paths to resolve
     *
     * @return the path to the temporary file
     */
    public static Path resolveTempFile(final String... paths) {
        Path result = TMP_DIR;
        for (String path : paths) {
            result = result.resolve(path);
        }
        final var parent = result.getParent();
        if (parent != null && Files.notExists(parent)) {
            createDirectory(parent);
        }
        return result;
    }

    /**
     * Deletes the cache directory.
     *
     * @throws IOException if an error occurs deleting the directory
     */
    public static void deleteCache() throws IOException {
        deleteDirectory(CACHE_DIR, true);
    }

    /**
     * Resolves a cached file.
     *
     * @param name the name of the cached file
     *
     * @return the cached file, which may or may not exist
     */
    public static CacheFile resolveCacheFile(final String name) {
        final var cacheFile = CACHE_DIR.resolve(name);
        return new CacheFile(cacheFile);
    }

    /**
     * Resolves a cached file.
     *
     * @param name       the name of the cached file
     * @param daysToKeep the number of days to keep the cache for
     *
     * @return the cached file, which may or may not exist
     */
    public static CacheFile resolveCacheFile(final String name, final int daysToKeep) {
        final var cacheFile = CACHE_DIR.resolve(name);
        return new CacheFile(cacheFile, daysToKeep);
    }

    /**
     * Checks if a directory is empty.
     *
     * @param dir the directory to check
     *
     * @return {@code true} if the directory is empty
     *
     * @throws IOException if an error occurs checking if the directory is empty
     */
    public static boolean isEmpty(final Path dir) throws IOException {
        if (Files.exists(dir)) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> ls = Files.list(dir)) {
                    return ls.findAny().isEmpty();
                }
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Deletes a directory and all its contents.
     *
     * @param dir the directory to delete
     *
     * @throws IOException if an error occurs deleting the directory
     */
    public static void deleteDirectory(final Path dir) throws IOException {
        deleteDirectory(dir, false);
    }

    /**
     * Deletes the contents of a directory. If the {@code keepDir} is set to {@code true} the base directory is kept
     * and only the contents of the directory are deleted. If set to {@code false}, the base directory is also deleted.
     *
     * @param dir     the directory to delete the contents of
     * @param keepDir {@code true} to delete only the contents of the directory, {@code false} to delete the contents of
     *                the directory and the directory itself
     *
     * @throws IOException if an error occurs deleting the directory
     */
    public static void deleteDirectory(final Path dir, final boolean keepDir) throws IOException {
        final Path baseDir = (keepDir ? dir : null);
        if (Files.exists(dir)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    if (!dir.equals(baseDir)) {
                        Files.delete(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void createDirectory(final Path dir) {
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
}
