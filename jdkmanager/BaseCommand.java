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
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;

import jdkmanager.client.Distributions;
import jdkmanager.client.JdkClient;
import jdkmanager.util.Environment;
import picocli.CommandLine;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
abstract class BaseCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--distribution"}, description = "The name of the distribution to use. Examples: temurin, semeru, zulu", defaultValue = "temurin")
    String distribution;

    @SuppressWarnings("unused")
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help message")
    boolean usageHelpRequested;

    @CommandLine.Option(names = {"-r", "--refresh"}, description = "Refreshes any local cache being used")
    boolean refresh;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Prints verbose output.")
    boolean verbose;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private ConsoleWriter console;

    @Override
    public final Integer call() {
        console = new ConsoleWriter(spec.commandLine().getOut(), spec.commandLine()
                .getErr(), spec.commandLine().getColorScheme().ansi(), verbose);
        try {
            try (JdkClient client = JdkClient.of(console, distribution)) {
                if (refresh) {
                    client.evictCache();
                }
                final Distributions distributions = client.supportedDistributions();
                if (distributions.isSupported(distribution)) {
                    return call(client);
                }
                printError("Failed to find supported distribution: %s", distribution);
                return 1;
            }
        } catch (Throwable e) {
            final Throwable cause;
            if (e.getCause() != null) {
                cause = e.getCause();
            } else {
                cause = e;
            }
            printError(cause, "Failed to invoke %s: %s", spec.name(), cause.getMessage());
            return 1;
        }
    }

    abstract Integer call(JdkClient client) throws Exception;

    void print() {
        console.print();
    }

    void print(final Object msg) {
        console.print(msg);
    }

    void print(final String fmt, final Object... args) {
        console.print(0, fmt, args);
    }

    @SuppressWarnings("SameParameterValue")
    void print(final int padding, final Object message) {
        console.print(padding, message);
    }

    void print(final int padding, final String fmt, final Object... args) {
        console.print(padding, fmt, args);
    }

    void printError(final String fmt, final Object... args) {
        console.printError(fmt, args);
    }

    void printError(final Throwable cause, final String fmt, final Object... args) {
        console.printError(cause, fmt, args);
    }

    PrintWriter getStdout() {
        return console.getStdout();
    }

    Properties javaInfo(final Path javaHome) throws IOException, InterruptedException {
        final String[] commands = {
                javaHome.resolve("bin").resolve("java").toString(),
                "-XshowSettings:properties",
                "-version"
        };
        final Path tempFile = Environment.createTempFile(distribution, javaHome.getFileName()
                .toString(), "jdk-info.out");
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
            return properties;
        } finally {
            Files.deleteIfExists(tempFile);
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
