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

import static jdkmanager.Environment.CACHE_DIR;
import static jdkmanager.Environment.arch;
import static jdkmanager.Environment.os;

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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jdkmanager.Environment.OS;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class FoojayJdkClient extends JdkClient {
    FoojayJdkClient(final BaseCommand command) {
        super(command, "https://api.foojay.io/disco/v3.0");
    }

    @Override
    Distributions supportedDistributions() throws IOException, InterruptedException {
        // https://api.foojay.io/disco/v3.0/distributions?include_versions=false&include_synonyms=true
        final Path file = CACHE_DIR.resolve("foojay-distributions.json");
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
    }


    @Override
    Status<Versions> getVersions() throws IOException, InterruptedException {
        final Status<JsonObject> status = getVersionJson();
        if (status.exitStatus() != 0) {
            return new Status<>(status.exitStatus(), new Versions(), status.rawData());
        }
        final JsonObject json = status.body();

        final Set<Version> ltsVersions = new TreeSet<>();
        final Set<Version> availableVersions = new TreeSet<>();

        final JsonArray results = json.getJsonArray("result");
        final AtomicBoolean latestLtsFound = new AtomicBoolean();
        final AtomicBoolean latestFound = new AtomicBoolean();
        final AtomicReference<Version> latestVersion = new AtomicReference<>();
        final AtomicReference<Version> latestLtsVersion = new AtomicReference<>();

        results.forEach((jsonValue) -> {
            boolean latest = false;
            final JsonObject data = (JsonObject) jsonValue;
            final int version = data.getInt("jdk_version");
            final boolean lts = data.getString("term_of_support").equals("lts");
            final boolean earlyAccess = data.getString("release_status").equals("ea");

            if (!earlyAccess && latestFound.compareAndSet(false, true)) {
                latest = true;
                latestVersion.set(new Version(true, lts, version, earlyAccess));
            }

            if (lts && latestLtsFound.compareAndSet(false, true)) {
                latestLtsVersion.set(new Version(true, lts, version, earlyAccess));
                latest = true;
            }
            if (lts) {
                ltsVersions.add(new Version(latest, lts, version, earlyAccess));
            } else {
                availableVersions.add(new Version(latest, lts, version, earlyAccess));
            }
        });
        return new Status<>(0, new Versions(
                latestVersion.get(),
                latestLtsVersion.get(),
                ltsVersions,
                availableVersions),
                json::toString);
    }

    @Override
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
                    if (inProperties) {
                        final var trimmed = line.trim();
                        final int i = trimmed.indexOf('=');
                        if (i > 0) {
                            final var key = trimmed.substring(0, i).trim();
                            if (KnownProperties.PROPERTIES.contains(key)) {
                                final var value = trimmed.substring(i + 1).trim();
                                properties.setProperty(key, value);
                            }
                        }
                    }
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

    @Override
    CompletionStage<Integer> download(final Path javaHome, final int jdkVersion, final boolean quiet) {

        if (Files.notExists(javaHome)) {
            final Version version;
            try {
                version = version(jdkVersion);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            final var download = downloadUri(version);
            if (command.verbose) {
                command.print("Downloading from %s", download.uri());
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
                                    final Path downloadedFile = command.distributionDir().resolve(download.filename());
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
                                                if (command.verbose) {
                                                    command.print("Downloaded %s%n", downloadedFile);
                                                }
                                            }
                                        }
                                    }
                                    Environment.deleteDirectory(javaHome);
                                    Files.createDirectories(javaHome);
                                    final Path path;
                                    if (filename.endsWith("tar.gz") || filename.endsWith("tgz")) {
                                        path = Archives.untargz(downloadedFile, command.distributionDir());
                                        if (path == null) {
                                            command.printError("Could not untar the path %s.", downloadedFile);
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
                                        command.printError("Request failed for version %d: %s", jdkVersion, reader.readObject()
                                                .getString("errorMessage"));
                                    }
                                    return 1;
                                }
                            }
                        } catch (IOException e) {
                            command.printError("Failed to install JDK %s: %s", jdkVersion, e.getMessage());
                            return 1;
                        }
                        return 0;
                    });
        }
        return CompletableFuture.completedFuture(0);
    }

    // https://api.foojay.io/disco/v3.0/packages/jdks?distribution=semeru&architecture=x64&archive_type=tar.gz&archive_type=zip&operating_system=linux&release_status=ea&release_status=ga
    // https://api.foojay.io/disco/v3.0/packages/jdks?distribution=semeru&architecture=x64&archive_type=tar.gz&archive_type=zip&operating_system=linux&release_status=ea&release_status=ga&latest=available
    private Status<JsonObject> getVersionJson() throws IOException, InterruptedException {
        final Cache cacheFile = versionsJson();
        final boolean resolve = cacheFile.requiresDownload();
        final JsonObject json;
        if (resolve) {
            final var builder = uriBuilder()
                    .path("packages/jdks")
                    .queryParam("distribution", command.distribution)
                    .queryParam("architecture", arch())
                    .queryParam("operating_system", os().name())
                    .queryParam("release_status", "ea")
                    .queryParam("release_status", "ga")
                    .queryParam("directly_downloadable", "true")
                    .queryParam("latest", "available");
            if (os() == OS.windows) {
                builder.queryParam("libc_type", "c_std_lib");
            } else if (os() == OS.mac) {
                builder.queryParam("libc_type", "libc");
            } else {
                builder.queryParam("libc_type", "glibc");
            }
            final String archiveType;
            if (os() == OS.windows) {
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
            try (JsonReader reader = Json.createReader(Files.newBufferedReader(cacheFile.file(), StandardCharsets.UTF_8))) {
                json = reader.readObject();
            }
        }
        return new Status<>(0, json, json::toString);
    }

    private Download downloadUri(final Version version) {
        final JsonObject json;
        try {
            json = getVersionJson().body();
        } catch (IOException | InterruptedException e) {
            // TODO (jrp) property handle this
            throw new RuntimeException(e);
        }
        // Find the version we're looking for
        final JsonArray results = json.getJsonArray("result");
        String directLink = null;
        String fileName = null;
        for (JsonValue value : results) {
            if (value instanceof JsonObject) {
                final JsonObject result = value.asJsonObject();
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
            // TODO (jrp) do something better
            throw new RuntimeException("Could not find download link for " + version);
        }
        return new Download(URI.create(directLink), fileName);
    }

    private record Download(URI uri, String filename){}
}
