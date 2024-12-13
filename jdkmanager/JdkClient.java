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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
abstract class JdkClient implements AutoCloseable {
    protected final BaseCommand command;
    private final String baseUri;
    protected final HttpClient httpClient;

    JdkClient(final BaseCommand command, final String baseUri) {
        this.command = command;
        this.baseUri = baseUri;
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    }

    // TODO (jrp) this needs to be implemented here and come from the foojay API
    abstract Distributions supportedDistributions() throws IOException, InterruptedException;

    int latestLts() throws IOException, InterruptedException {
        final var versions = getVersions();
        return versions.body().latestLts().version();
    }

    // TODO (jrp) should this really be he default?
    Version version(int majorVersion) throws IOException, InterruptedException {
        final Status<Versions> versionStatus = getVersions();
        if (versionStatus.exitStatus() == 0) {
            final Versions versions = versionStatus.body();
            return Stream.concat(versions.lts().stream(), versions.available().stream())
                    .filter(v -> v.version() == majorVersion)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Version " + majorVersion + " not found"));
        }
        throw new RuntimeException(String.format("Could not find version %d: %s", majorVersion, versionStatus.rawData()
                .get()));
    }

    abstract Status<Versions> getVersions() throws IOException, InterruptedException;

    abstract Status<Properties> getJavaInfo(Path javaHome) throws IOException, InterruptedException;

    abstract CompletionStage<Integer> download(Path javaHome, int jdkVersion, boolean quiet);

    UriBuilder uriBuilder() {
        return new UriBuilder(baseUri);
    }

    Cache versionsJson() throws IOException {
        return Environment.resolveCacheFile(String.format("%s-versions.json", command.distribution));
    }

    @Override
    public void close() {
        //httpClient.close();
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
