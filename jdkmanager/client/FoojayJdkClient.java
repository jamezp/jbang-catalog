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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jdkmanager.ConsoleWriter;
import jdkmanager.util.Archives;
import jdkmanager.util.Environment;
import jdkmanager.util.Environment.OS;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;

/**
 * A Java client which uses the <a href="https://api.foojay.io/swagger-ui/">https://api.foojay.io/swagger-ui</a> API.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class FoojayJdkClient extends JdkClient {
    public FoojayJdkClient(final ConsoleWriter consoleWriter, final String distribution) {
        super(consoleWriter, distribution, "https://api.foojay.io/disco/v3.0");
    }

    @Override
    public Distributions supportedDistributions() {
        try {
            final CacheFile cache = supportedCacheFile();
            if (cache.requiresDownload()) {
                cache.evict();
            }
            final Path file = cache.file();
            if (Files.notExists(file)) {
                final URI uri = uriBuilder()
                        .path("distributions")
                        .queryParam("include_versions", "false")
                        .queryParam("include_synonyms", "true")
                        .build();
                final HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
                final HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Unexpected response code: " + response.statusCode());
                }
                Files.copy(response.body(), file, StandardCopyOption.REPLACE_EXISTING);
            }
            final Set<Distribution> distributions = new TreeSet<>();
            try (JsonReader reader = Json.createReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
                final JsonObject json = reader.readObject();
                final JsonArray jsonArray = json.getJsonArray("result");
                jsonArray.forEach(jsonValue -> {
                    if (jsonValue instanceof final JsonObject dist) {
                        final Set<String> synonyms;
                        if (dist.containsKey("synonyms")) {
                            synonyms = dist.getJsonArray("synonyms")
                                    .stream()
                                    .map(v -> ((JsonString) v).getString())
                                    .collect(Collectors.toSet());
                        } else {
                            synonyms = Set.of();
                        }
                        distributions.add(new Distribution(dist.getString("name"), synonyms));
                    }
                });
            }
            return new Distributions(distributions);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to resolve available distributions", e);
        }
    }

    @Override
    public void evictCache() throws IOException {
        super.evictCache();
        Environment.resolveCacheFile("foojay-distributions.json").evict();
    }

    @Override
    public Versions getVersions() {
        try {
            final JsonObject json = getVersionJson();

            final Set<Version> availableVersions = new TreeSet<>();

            final JsonArray results = json.getJsonArray("result");
            final AtomicBoolean latestLtsFound = new AtomicBoolean();
            final AtomicBoolean latestFound = new AtomicBoolean();

            results.forEach((jsonValue) -> {
                boolean latest = false;
                final JsonObject data = (JsonObject) jsonValue;
                final int version = data.getInt("jdk_version");
                final boolean lts = data.getString("term_of_support").equals("lts");
                final boolean earlyAccess = data.getString("release_status").equals("ea");


                if (!earlyAccess && latestFound.compareAndSet(false, true)) {
                    latest = true;
                }

                if (lts && !earlyAccess && latestLtsFound.compareAndSet(false, true)) {
                    latest = true;
                }

                availableVersions.add(new Version(latest, lts, version, earlyAccess));
            });
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
        final var download = downloadUri(version);
        if (consoleWriter.verbose()) {
            consoleWriter.print("Downloading from %s", download.uri());
        }
        final HttpRequest request = HttpRequest.newBuilder(download.uri())
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    try {
                        try (InputStream in = response.body()) {
                            if (response.statusCode() == 200) {
                                // Find the file name
                                final String filename = download.filename();
                                final Path downloadedFile = Environment.resolveTempFile(download.filename());
                                if (Files.notExists(downloadedFile)) {
                                    if (quiet) {
                                        Files.copy(in, downloadedFile, StandardCopyOption.REPLACE_EXISTING);
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
                                                OutputStream out = Files.newOutputStream(downloadedFile)
                                        ) {
                                            final byte[] buffer = new byte[4096];
                                            int len;
                                            while ((len = in.read(buffer)) > 0) {
                                                out.write(buffer, 0, len);
                                                progressBar.stepBy(len);
                                            }
                                            if (consoleWriter.verbose()) {
                                                consoleWriter.print("Downloaded %s%n", downloadedFile);
                                            }
                                        }
                                    }
                                }
                                Environment.deleteDirectory(javaHome);
                                Files.createDirectories(javaHome);
                                final Path path;
                                if (filename.endsWith("tar.gz") || filename.endsWith("tgz")) {
                                    final Path targetDir = Environment.createTempDirectory(distribution, "jdk-" + version.version());
                                    path = Archives.untargz(downloadedFile, targetDir);
                                    if (path == null) {
                                        consoleWriter.printError("Could not untar the path %s.", downloadedFile);
                                        return 1;
                                    }
                                    Files.move(path, javaHome, StandardCopyOption.REPLACE_EXISTING);
                                } else {
                                    Archives.unzip(downloadedFile, javaHome);
                                }
                                // Delete the download
                                Files.deleteIfExists(downloadedFile);
                            } else {
                                try (JsonReader reader = Json.createReader(in)) {
                                    consoleWriter.printError("Request failed for version %d: %s", jdkVersion, reader.readObject()
                                            .getString("errorMessage"));
                                }
                                return 1;
                            }
                        }
                    } catch (IOException e) {
                        consoleWriter.printError("Failed to install JDK %s: %s", jdkVersion, e.getMessage());
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
            final OS os = Environment.os();
            final var builder = uriBuilder()
                    .path("packages/")
                    .queryParam("distribution", distribution)
                    .queryParam("architecture", Environment.arch())
                    .queryParam("operating_system", os.name())
                    .queryParam("release_status", "ea,ga")
                    //.queryParam("release_status", "ga")
                    .queryParam("directly_downloadable", "true")
                    .queryParam("package_type", "jdk")
                    .queryParam("latest", "available");
            if (os == OS.windows) {
                builder.queryParam("libc_type", "c_std_lib");
            } else if (os == OS.mac) {
                builder.queryParam("libc_type", "libc");
            } else {
                builder.queryParam("libc_type", "glibc");
            }
            final String archiveType;
            if (os == OS.windows) {
                archiveType = "zip";
            } else {
                archiveType = "tar.gz";
            }
            builder.queryParam("archive_type", archiveType);
            final URI uri = builder.build();
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

    private Download downloadUri(final Version version) {
        final JsonObject json;
        try {
            json = getVersionJson();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Could not resolve the versions.", e);
        }
        // Find the version we're looking for
        final JsonArray results = json.getJsonArray("result");
        String directLink = null;
        String fileName = null;
        for (JsonValue value : results) {
            if (value instanceof JsonObject) {
                final JsonObject result = value.asJsonObject();
                if (version.earlyAccess() != "ea".equals(result.getString("release_status"))) {
                    continue;
                }
                if (result.getInt("jdk_version") == version.version()) {
                    if (!result.getBoolean("javafx_bundled")) {
                        final var links = result.getJsonObject("links");
                        directLink = links.getString("pkg_download_redirect");
                        fileName = result.getString("filename");
                        break;
                    }
                }
            }
        }

        if (directLink == null) {
            throw new RuntimeException("Could not find download link for " + version);
        }
        return new Download(URI.create(directLink), fileName);
    }

    private record Download(URI uri, String filename) {
    }
}
