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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jdkmanager.ConsoleWriter;
import jdkmanager.util.Archives;
import jdkmanager.util.Environment;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class AdoptiumJdkClient extends JdkClient {

    public AdoptiumJdkClient(final ConsoleWriter consoleWriter, final String distribution) {
        super(consoleWriter, distribution, "https://api.adoptium.net/v3");
    }

    @Override
    public Distributions supportedDistributions() {
        return new Distributions(Set.of(new Distribution("temurin", Set.of("temurin", "Temurin", "TEMURIN"))));
    }

    @Override
    public Versions getVersions() {
        try {
            final JsonObject json = getVersionJson();
            final var latestLts = json.getInt("most_recent_lts");
            final var latest = json.getInt("most_recent_feature_release");
            final var lts = json.getJsonArray("available_lts_releases");
            final var available = json.getJsonArray("available_releases");

            final Set<Version> availableVersions = new TreeSet<>();

            final AtomicReference<RuntimeException> error = new AtomicReference<>();

            lts.forEach((jsonValue -> {
                if (jsonValue.getValueType() == JsonValue.ValueType.NUMBER) {
                    final int v = ((JsonNumber) jsonValue).intValue();
                    availableVersions.add(new Version(v == latestLts, true, v, v > latest));
                } else {
                    error.set(new RuntimeException(String.format("Version not a number: %s", jsonValue)));
                }
            }));

            available.forEach((jsonValue -> {
                if (jsonValue.getValueType() == JsonValue.ValueType.NUMBER) {
                    final int v = ((JsonNumber) jsonValue).intValue();
                    availableVersions.add(new Version(v == latest, false, v, v > latest));
                } else {
                    error.set(new RuntimeException(String.format("Version not a number: %s", jsonValue)));
                }
            }));
            if (error.get() != null) {
                throw error.get();
            }
            return new Versions(availableVersions);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to resolve available versions for " + distribution, e);
        }
    }

    @Override
    public CompletionStage<Integer> download(final Path javaHome, final int jdkVersion, final boolean quiet) {
        final Version version;
        try {
            version = findVersion(jdkVersion);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        final var uri = downloadUri(version);
        if (consoleWriter.verbose()) {
            consoleWriter.print("Downloading from %s", uri);
        }
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
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
                                    consoleWriter.printError("Could not determine the filename based on the response headers.");
                                    return 1;
                                }
                                final Path download = Environment.resolveTempFile(filename);
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
                                            if (consoleWriter.verbose()) {
                                                consoleWriter.print("Downloaded %s%n", download);
                                            }
                                        }
                                    }
                                }
                                Environment.deleteDirectory(javaHome);
                                Files.createDirectories(javaHome);
                                final Path path;
                                if (filename.endsWith("tar.gz") || filename.endsWith("tgz")) {
                                    final Path targetDir = Environment.resolveTempFile(distribution, "jdk-" + version.version());
                                    path = Archives.untargz(download, targetDir);
                                    if (path == null) {
                                        consoleWriter.printError("Could not extract JDK from %s.", download);
                                        return 1;
                                    }
                                    Files.move(path, javaHome, StandardCopyOption.REPLACE_EXISTING);
                                } else {
                                    Archives.unzip(download, javaHome);
                                }
                                // Delete the download
                                Files.deleteIfExists(download);
                            } else {
                                try (JsonReader reader = Json.createReader(in)) {
                                    consoleWriter.printError("Request failed for version %d: %s", version, reader.readObject()
                                            .getString("errorMessage"));
                                }
                                return 1;
                            }
                        }
                    } catch (IOException e) {
                        consoleWriter.printError("Failed to install JDK %s: %s", version, e.getMessage());
                        return 1;
                    }
                    return 0;
                });
    }

    private JsonObject getVersionJson() throws IOException, InterruptedException {
        final CacheFile cacheFile = versionsJson();
        final boolean resolve = cacheFile.requiresDownload();
        final JsonObject json;
        if (resolve) {
            final var uri = uriBuilder()
                    .path("info")
                    .path("available_releases")
                    .build();
            final HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("accept", "application/json")
                    .build();
            final HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() == 200) {
                    try (JsonWriter writer = Json.createWriter(Files.newBufferedWriter(cacheFile.file(), StandardCharsets.UTF_8))) {
                        json = Json.createReader(in).readObject();
                        writer.writeObject(json);
                    }
                } else {
                    if (response.statusCode() == 404) {
                        throw new RuntimeException("Could not find resource at " + uri);
                    }
                    throw new RuntimeException(String.format("Failed to find available versions at %s - Status %d", uri, response.statusCode()));
                }
            }
        } else {
            try (JsonReader reader = Json.createReader(Files.newBufferedReader(cacheFile.file(), StandardCharsets.UTF_8))) {
                json = reader.readObject();
            }
        }
        return json;
    }

    private String getFilename(final String contentDisposition) {
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

    private URI downloadUri(final Version version) {
        return uriBuilder()
                .path("binary")
                .path("latest")
                .path(version.version())
                .path(version.earlyAccess() ? "ea" : "ga")
                .path(Environment.os())
                .path(Environment.arch())
                .path("jdk")
                .path("hotspot")
                .path("normal")
                .path("eclipse")
                .queryParam("project", "jdk")
                .build();
    }
}
