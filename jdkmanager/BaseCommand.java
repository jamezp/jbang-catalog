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
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import picocli.CommandLine;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
abstract class BaseCommand implements Callable<Integer> {

    enum OS {
        linux,
        alpine_linux,
        mac,
        windows,
        aix,
        unknown;
    }

    static final Path TMP_DIR = Path.of(System.getProperty("java.io.tmpdir"));
    static final Path USER_DIR = Path.of(System.getProperty("user.home"));
    static final Path WORK_DIR = USER_DIR.resolve(".jdk-manager");

    private static final AtomicReference<String> ARCH_REF = new AtomicReference<>();

    private static final AtomicReference<OS> OS_REF = new AtomicReference<>();

    @SuppressWarnings("unused")
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help message")
    boolean usageHelpRequested;

    @CommandLine.Option(names = {"-r", "--refresh"}, description = "Refreshes any local cache being used")
    boolean refresh;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Prints verbose output.")
    boolean verbose;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private PrintWriter stdout;

    private PrintWriter stderr;
    private CommandLine.Help.Ansi ansi;

    @Override
    public final Integer call() throws Exception {
        return call(new AdoptiumJdkClient(this));
    }

    abstract Integer call(JdkClient client) throws Exception;

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

    static boolean isWindows() {
        return os() == OS.windows;
    }

    static boolean isNotWindows() {
        return os() != OS.windows;
    }

    static String arch() {
        return ARCH_REF.updateAndGet(arch -> {
            if (arch == null) {
                final var archProp = System.getProperty("os.arch")
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "");
                if (archProp.matches("^(x8664|amd64|ia32e|em64t|x64)$")) {
                    return "x64";
                } else if (archProp.matches("^(x8632|x86|i[3-6]86|ia32|x32)$")) {
                    return "x32";
                } else if (archProp.matches("^(aarch64)$")) {
                    return "aarch64";
                } else if (archProp.matches("^(ppc64)$")) {
                    return "ppc64";
                } else if (archProp.matches("^(ppc64le)$")) {
                    return "ppc64le";
                } else if (archProp.matches("^(s390x)$")) {
                    return "s390x";
                } else if (archProp.matches("^(arm64)$")) {
                    return "arm64";
                } else {
                    throw new RuntimeException("Unknown architecture " + archProp);
                }
            }
            return arch;
        });
    }

    static OS os() {
        return OS_REF.updateAndGet(os -> {
            if (os == null) {
                final var osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (osName.startsWith("mac") || osName.startsWith("osx")) {
                    return OS.mac;
                } else if (osName.startsWith("linux")) {
                    if (Files.exists(Paths.get("/etc/alpine-release"))) {
                        return OS.alpine_linux;
                    } else {
                        return OS.linux;
                    }
                } else if (osName.startsWith("win")) {
                    return OS.windows;
                } else if (osName.startsWith("aix")) {
                    return OS.aix;
                } else {
                    return OS.unknown;
                }
            }
            return os;
        });
    }
}
