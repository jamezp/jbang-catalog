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

package jdkmanager;

import java.io.PrintWriter;

import picocli.CommandLine;

/**
 * Represents a way to write to the console.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class ConsoleWriter {

    private final PrintWriter stdout;
    private final PrintWriter stderr;
    private final CommandLine.Help.Ansi ansi;
    private final boolean verbose;

    ConsoleWriter(final PrintWriter stdout, final PrintWriter stderr, final CommandLine.Help.Ansi ansi, final boolean verbose) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.ansi = ansi;
        this.verbose = verbose;
    }

    /**
     * Indicates whether verbose output was enabled.
     *
     * @return {@code true} if verbose output was enabled
     */
    public boolean verbose() {
        return verbose;
    }

    /**
     * Prints a new line to {@code stdout}.
     */
    public void print() {
        getStdout().println();
    }

    /**
     * Prints the object to {@code stdout}.
     *
     * @param msg the object to print
     */
    public void print(final Object msg) {
        getStdout().println(format(String.valueOf(msg)));
    }

    /**
     * Prints the message to {@code stdout} via {@link PrintWriter#printf(String, Object...)}. Noe that a new line
     * is always printed.
     *
     * @param fmt  the message format
     * @param args the arguments for the format
     */
    public void print(final String fmt, final Object... args) {
        print(0, fmt, args);
    }

    /**
     * Prints the message to {@code stdout} prefixing the message with the padding passed in.
     *
     * @param padding the number of spaces to pad the message with
     * @param message the message to log
     */
    @SuppressWarnings("SameParameterValue")
    public void print(final int padding, final Object message) {
        final PrintWriter writer = getStdout();
        if (padding > 0) {
            writer.printf("%1$" + padding + "s", " ");
        }
        writer.println(message);
    }

    /**
     * Prints the message to {@code stdout} via {@link PrintWriter#printf(String, Object...)}. Noe that a new line
     * is always printed. This prefixes the message with the padding passed in.
     *
     * @param padding the number of spaces to pad the message with
     * @param fmt     the message format
     * @param args    the arguments for the format
     */
    public void print(final int padding, final String fmt, final Object... args) {
        print(getStdout(), padding, fmt, args);
    }

    /**
     * Prints the message to {@code stderr} via {@link PrintWriter#printf(String, Object...)}. Noe that a new line
     * is always printed.
     *
     * @param fmt  the message format
     * @param args the arguments for the format
     */
    public void printError(final String fmt, final Object... args) {
        print(getStderr(), 0, "@|red " + fmt + "|@", args);
    }

    /**
     * Prints the message to {@code stderr} via {@link PrintWriter#printf(String, Object...)}. Noe that a new line
     * is always printed.
     *
     * @param fmt  the message format
     * @param args the arguments for the format
     */
    public void printError(final Throwable cause, final String fmt, final Object... args) {
        print(getStderr(), 0, "@|red " + fmt + "|@", args);
        if (verbose) {
            cause.printStackTrace(getStderr());
        }
    }

    /**
     * Returns the {@code stdout} print writer
     *
     * @return {@code stdout}
     */
    public PrintWriter getStdout() {
        return stdout;
    }

    /**
     * Returns the {@code stderr} print writer
     *
     * @return {@code stderr}
     */
    public PrintWriter getStderr() {
        return stderr;
    }

    private String format(final String fmt, final Object... args) {
        return format(ansi, String.format(fmt, args));
    }

    private String format(final CommandLine.Help.Ansi ansi, final String value) {
        return ansi.string(value);
    }

    private void print(final PrintWriter writer, final int padding, final String fmt, final Object... args) {
        if (padding > 0) {
            writer.printf("%1$" + padding + "s", " ");
        }
        writer.println(format(fmt, args));
    }
}
