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

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;

import jakarta.json.Json;
import jakarta.json.stream.JsonGenerator;
import jdkmanager.client.JdkClient;
import jdkmanager.util.Environment;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@CommandLine.Command(name = "info", description = "Prints information about the current JVM.",
        subcommands = AutoComplete.GenerateCompletion.class)
class InfoCommand extends BaseCommand {

    enum OutputFormat {
        json,
        properties
    }

    @Option(names = {"-d", "--download"}, description = "Indicates the JDK should be downloaded if it's missing.")
    private boolean downloadIfMissing;

    @Option(names = {"-f", "--format"}, description = "The output format of the results.")
    private OutputFormat outputFormat;

    @Option(names = {"-p", "--property"}, description = "The property to print. Note that the format parameter has no affect on the output with this argument.", completionCandidates = KnownProperties.class)
    private String property;

    @Parameters(arity = "0..1", description = "The version you'd like to get the properties for.")
    private int version;

    @Override
    Integer call(final JdkClient client) throws Exception {
        if (BaseCommand.KnownProperties.missing(property)) {
            final StringBuilder properties = new StringBuilder();
            BaseCommand.KnownProperties.PROPERTIES.forEach((name) -> properties.append(System.lineSeparator())
                    .append(name));
            printError("Property \"%s\" is is not a valid property. Valid properties are: %s", property, properties);
            return 1;
        }
        if (version > 0) {
            final Path javaHome = Environment.resolveJavaHome(distribution, version);
            if (Files.notExists(javaHome) || Environment.isEmpty(javaHome)) {
                if (downloadIfMissing) {
                    final int exitCode = client.download(javaHome, version, !verbose)
                            .toCompletableFuture().get();
                    if (exitCode > 0) {
                        printError("Failed to download Java %d", version);
                        return exitCode;
                    }
                } else {
                    printError("Java %s is not installed. Pass -d to download if the JDK is missing.", version);
                    return 1;
                }
            }
            final var properties = javaInfo(javaHome);
            if (property == null) {
                printInfo(properties);
            } else {
                print(properties.getProperty(property));
            }
        } else {
            if (property == null) {
                printInfo(System.getProperties());
            } else {
                print(System.getProperty(property));
            }
        }
        return 0;
    }

    private void printInfo(final Properties properties) {
        final BiConsumer<String, String> lineWriter;
        final Runnable completer;
        if (outputFormat == OutputFormat.json) {
            final JsonGenerator generator = Json.createGeneratorFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true))
                    .createGenerator(getStdout());
            generator.writeStartObject();
            lineWriter = (name, value) -> {
                if (value == null) {
                    generator.writeNull(name);
                } else {
                    generator.write(name, value);
                }
            };
            completer = () -> {
                generator.writeEnd();
                generator.flush();
                print();
            };
        } else if (outputFormat == OutputFormat.properties) {
            completer = () -> {
            };
            lineWriter = (name, value) -> {
                writeSanitized(getStdout(), name, true);
                getStdout().print('=');
                writeSanitized(getStdout(), value, false);
                getStdout().println();
            };
        } else {
            completer = () -> {
            };
            lineWriter = (name, value) -> {
                print("@|bold,green %-30s:|@ %s", name, value);
            };
        }
        for (var name : KnownProperties.PROPERTIES) {
            lineWriter.accept(name, properties.getProperty(name));
        }
        completer.run();
    }

    private static void writeSanitized(final PrintWriter out, final String value, final boolean escapeSpace) {
        if (value == null) {
            out.print("null");
            return;
        }
        for (int x = 0; x < value.length(); x++) {
            final char c = value.charAt(x);
            switch (c) {
                case ' ':
                    if (x == 0 || escapeSpace)
                        out.append('\\');
                    out.append(c);
                    break;
                case '\t':
                    out.append('\\').append('t');
                    break;
                case '\n':
                    out.append('\\').append('n');
                    break;
                case '\r':
                    out.append('\\').append('r');
                    break;
                case '\f':
                    out.append('\\').append('f');
                    break;
                case '\\':
                case '=':
                case ':':
                case '#':
                case '!':
                    out.append('\\').append(c);
                    break;
                default:
                    out.append(c);
            }
        }
    }
}
