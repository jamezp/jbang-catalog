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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.json.JsonNumber;
import jakarta.json.JsonValue;
import picocli.AutoComplete;
import picocli.CommandLine;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@CommandLine.Command(name = "list", description = "Lists information about available JDK's.",
        subcommands = AutoComplete.GenerateCompletion.class)
class JdkList extends BaseCommand {

    @CommandLine.Option(names = {"-l", "--local"}, description = "Lists the JDK's installed locally.")
    private boolean local;

    @Override
    Integer call(final JdkClient client) throws Exception {
        if (local) {
            final AtomicInteger exitCode = new AtomicInteger(0);
            try (Stream<Path> paths = Files.list(WORK_DIR)) {
                paths.forEach(dir -> {
                    if (Files.exists(dir.resolve("bin").resolve(isWindows() ? "java.exe" : "java"))) {
                        try {
                            final var status = client.getJavaInfo(dir);
                            if (status.exitStatus() > 0) {
                                printError("Failed to determine info for %s. Ensure this is a valid JDK directory.", dir);
                                exitCode.set(status.exitStatus());
                                return;
                            }
                            final var properties = status.body();
                            print("%s %s (%s): %s", properties.getProperty("java.vendor"),
                                    properties.getProperty("java.version"), properties.getProperty("java.version.date"), dir);
                        } catch (IOException | InterruptedException e) {
                            printError("Failed to determine info for %s: %s", dir, e.getMessage());
                            exitCode.set(1);
                        }
                    }
                });
                return exitCode.get();
            }
        }
        final var status = client.getVersions(refresh);
        if (status.exitStatus() > 0) {
            printError("Failed to determine the available JDK's: %s", status.rawData());
            return status.exitStatus();
        } else {
            final var versions = status.body();
            print("@|bold LTS Versions|@");
            versions.lts().forEach(listConsumer(true));
            print("@|bold Versions|@");
            versions.available().forEach(listConsumer(false));
        }
        return 0;
    }

    private Consumer<Version> listConsumer(final boolean mark) {
        return v -> {
            if (mark && v.latest()) {
                print(1, "@|bold,cyan *%s|@", v.version());
            } else {
                print(2, v.version());
            }
        };
    }
}
