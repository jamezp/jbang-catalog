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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class AdoptiumJdkClient extends JdkClient {

    AdoptiumJdkClient(final BaseCommand command) {
        super(command, "https://api.adoptium.net/v3");
    }

    int latestLts() throws IOException, InterruptedException {
        final var versions = getVersions(false);
        return versions.body().latestLts().version();
    }

    Status<Versions> getVersions(final boolean refresh) throws IOException, InterruptedException {
        final Status<JsonObject> status = getVersionJson(refresh);
        if (status.exitStatus() != 0) {
            return new Status<>(status.exitStatus(), new Versions(), status.rawData());
        }
        final JsonObject json = status.body();
        final var latestLts = json.getInt("most_recent_lts");
        final var latest = json.getInt("most_recent_feature_release");
        final var lts = json.getJsonArray("available_lts_releases");
        final var available = json.getJsonArray("available_releases");

        final List<Version> ltsVersions = new ArrayList<>();
        final List<Version> availableVersions = new ArrayList<>();

        lts.forEach((jsonValue -> {
            if (jsonValue.getValueType() == JsonValue.ValueType.NUMBER) {
                final int v = ((JsonNumber) jsonValue).intValue();
                ltsVersions.add(new Version(v == latestLts, true, v, v > latest));
            } else {
                // TODO (jrp) what do we do here????
                command.printError("Version not a number: %s", jsonValue);
            }
        }));

        available.forEach((jsonValue -> {
            if (jsonValue.getValueType() == JsonValue.ValueType.NUMBER) {
                final int v = ((JsonNumber) jsonValue).intValue();
                availableVersions.add(new Version(v == latest, false, v, v > latest));
            } else {
                // TODO (jrp) what do we do here????
                command.printError("Version not a number: %s", jsonValue);
            }
        }));
        return new Status<>(0, new Versions(
                new Version(true, false, latest, false),
                new Version(true, true, latestLts, false),
                ltsVersions,
                availableVersions),
                json::toString);
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

    private Status<JsonObject> getVersionJson(final boolean refresh) throws IOException, InterruptedException {

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
            final var uri = uriBuilder()
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
        return new Status<>(0, json, json::toString);
    }

    private boolean isEarlyAccess(final int version) {
        try {
            final Status<JsonObject> status = getVersionJson(false);
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
        return uriBuilder()
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
}
