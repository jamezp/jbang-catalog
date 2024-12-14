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

package jdkmanager.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import jdkmanager.ConsoleWriter;
import jdkmanager.util.Environment;

/**
 * A simple client downloading information about available JDK's.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public abstract class JdkClient implements AutoCloseable {
    private final String baseUri;
    protected final ConsoleWriter consoleWriter;
    protected final String distribution;
    protected final HttpClient httpClient;

    JdkClient(final ConsoleWriter consoleWriter, final String distribution, final String baseUri) {
        this.consoleWriter = consoleWriter;
        this.distribution = distribution;
        this.baseUri = baseUri;
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    }

    /**
     * Creates a new client.
     *
     * @param consoleWriter the console writer for logging output
     * @param distribution  the distribution the client should be associated with
     *
     * @return a new client
     */
    public static JdkClient of(final ConsoleWriter consoleWriter, final String distribution) {
        return new FoojayJdkClient(consoleWriter, distribution);
    }

    /**
     * Returns the supported distributions for the client.
     *
     * @return the supported distributions
     */
    public abstract Distributions supportedDistributions();

    public void expireCache() throws IOException {
        versionsJson().expire();
    }

    /**
     * That latest LTS version available for the distribution.
     *
     * @return the latest LTS version
     */
    public int latestLts() {
        final var versions = getVersions();
        return versions.latestLts().version();
    }

    /**
     * Returns the available versions for the distribution.
     *
     * @return the available version
     */
    public abstract Versions getVersions();

    /**
     * Downloads the JDK and extracts to the download to the {@code javaHome} directory.
     *
     * @param javaHome   the directory to download and extract the JDK to
     * @param jdkVersion the JDK version
     * @param quiet      if the progress should be shown
     *
     * @return an async stage which returns the exit code for the download
     */
    public abstract CompletionStage<Integer> download(Path javaHome, int jdkVersion, boolean quiet);

    @Override
    public void close() {
        //httpClient.close();
    }

    Version findVersion(final int majorVersion) {
        final Versions versions = getVersions();
        return Stream.concat(versions.lts().stream(), versions.available().stream())
                .filter(v -> v.version() == majorVersion)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Version " + majorVersion + " not found"));
    }

    UriBuilder uriBuilder() {
        return new UriBuilder(baseUri);
    }

    CacheFile versionsJson() throws IOException {
        return Environment.resolveCacheFile(String.format("%s-versions.json", distribution));
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

}
