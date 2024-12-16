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

import jdkmanager.client.JdkClient;
import jdkmanager.client.Version;
import jdkmanager.util.Environment;
import picocli.AutoComplete;
import picocli.CommandLine;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@CommandLine.Command(name = "list", description = "Lists information about available JDK's.",
        subcommands = AutoComplete.GenerateCompletion.class)
class ListCommand extends BaseCommand {

    @CommandLine.Option(names = {"-l", "--local"}, description = "Lists the JDK's installed locally.")
    private boolean local;

    @Override
    Integer call(final JdkClient client) throws Exception {
        if (local) {
            final AtomicInteger exitCode = new AtomicInteger(0);
            final Path workDir = Environment.WORK_DIR;
            if (Files.notExists(workDir)) {
                print("No JDK's installed for %s", distribution);
                return 0;
            }
            try (Stream<Path> paths = Files.walk(workDir, 2)) {
                paths.sorted()
                        .forEachOrdered(dir -> {
                            if (Files.exists(dir.resolve("bin")
                                    .resolve(Environment.isWindows() ? "java.exe" : "java"))) {
                                try {
                                    final var properties = javaInfo(dir);
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
        final var versions = client.getVersions();
        print("@|bold LTS Versions for %s|@", distribution);
        versions.lts().forEach(listConsumer(true));
        print("@|bold STS Versions for %s|@", distribution);
        versions.sts().forEach(listConsumer(false));
        print("@|bold Early Access Versions for %s|@", distribution);
        versions.earlyAccess().forEach(listConsumer(false));
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
