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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.Callable;

import picocli.CommandLine;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
abstract class BaseCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--distribution"}, description = "The name of the distribution to use. Examples: temurin, semeru, zulu")
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

    boolean distributionSet = false;

    private PrintWriter stdout;

    private PrintWriter stderr;
    private CommandLine.Help.Ansi ansi;

    @Override
    public final Integer call() throws Exception {
        try {
            // TODO (jrp) we need to do something with distribution here. We need to use https://api.foojay.io/disco/v3.0/distributions?include_versions=false&include_synonyms=true
            // TODO (jrp) to download a valid list of distributions
            if (distribution == null) {
                distribution = "temurin";
                try (JdkClient client = new AdoptiumJdkClient(this)) {
                    return call(client);
                }
            }
            if (refresh) {
                Environment.deleteCache();
            }
            distributionSet = true;
            try (JdkClient client = new FoojayJdkClient(this)) {
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

    Path distributionDir() throws IOException {
        final Path dir = Environment.WORK_DIR.resolve(distribution);
        if (Files.notExists(dir)) {
            if (Environment.supportsPosix()) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(dir);
            }
        }
        return dir;
    }

    void print() {
        final PrintWriter writer = getStdout();
        writer.println();
    }

    void print(final Object msg) {
        final PrintWriter writer = getStdout();
        writer.println(format(String.valueOf(msg)));
    }

    void print(final String fmt, final Object... args) {
        print(0, fmt, args);
    }

    @SuppressWarnings("SameParameterValue")
    void print(final int padding, final Object message) {
        final PrintWriter writer = getStdout();
        if (padding > 0) {
            writer.printf("%1$" + padding + "s", " ");
        }
        writer.println(message);
    }

    void print(final int padding, final String fmt, final Object... args) {
        print(getStdout(), padding, fmt, args);
    }

    void printError(final String fmt, final Object... args) {
        print(getStderr(), 0, "@|red " + fmt + "|@", args);
    }

    void printError(final Throwable cause, final String fmt, final Object... args) {
        print(getStderr(), 0, "@|red " + fmt + "|@", args);
        if (verbose) {
            cause.printStackTrace(getStderr());
        }
    }

    private void print(final PrintWriter writer, final int padding, final String fmt, final Object... args) {
        if (padding > 0) {
            writer.printf("%1$" + padding + "s", " ");
        }
        writer.println(format(fmt, args));
    }

    PrintWriter getStdout() {
        if (stdout == null) {
            stdout = spec.commandLine().getOut();
        }
        return stdout;
    }

    PrintWriter getStderr() {
        if (stderr == null) {
            stderr = spec.commandLine().getErr();
        }
        return stderr;
    }

    String format(final String fmt, final Object... args) {
        if (ansi == null) {
            ansi = spec.commandLine().getColorScheme().ansi();
        }
        return format(ansi, String.format(fmt, args));
    }

    String format(final CommandLine.Help.Ansi ansi, final String value) {
        return ansi.string(value);
    }

}
