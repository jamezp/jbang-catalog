/// usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS info.picocli:picocli:4.7.3
//DEPS jakarta.json:jakarta.json-api:2.1.2
//DEPS org.eclipse.parsson:parsson:1.1.4
//DEPS org.apache.commons:commons-compress:1.24.0
//DEPS me.tongfei:progressbar:0.10.0
//SOURCES *.java

package jdkmanager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@Command(name = "jdk-manager", description = "Manages local JDK installations.",
        showDefaultValues = true, subcommands = {
        AutoComplete.GenerateCompletion.class,
        Install.class,
        JdkList.class,
        Info.class
}
)
public class jdkmanager extends BaseCommand implements Callable<Integer> {

    public jdkmanager() {
    }

    public static void main(String... args) throws Exception {
        // Load the environment and check for any issues
        final boolean verbose = args != null && (List.of(args).contains("--verbose") || List.of(args).contains("-v"));
        try {
            final var workDir = Environment.WORK_DIR;
            if (verbose) {
                System.out.printf("Working Directory: %s%n", workDir);
            }
        } catch (Throwable t) {
            final Throwable cause;
            if (t.getCause() != null) {
                cause = t.getCause();
            } else {
                cause = t;
            }
            System.err.printf("Failed to initialize environment: %s%n", cause.getMessage());
            if (verbose) {
                cause.printStackTrace(System.err);
            }
            System.exit(1);
        }
        final CommandLine commandLine = new CommandLine(new jdkmanager());
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        disableGenerateCompletion(commandLine.getSubcommands().entrySet());
        final int exitStatus = commandLine.execute(args);
        System.exit(exitStatus);
    }

    @Override
    Integer call(final JdkClient client) {
        // Display the usage if there was no sub-command sent
        spec.commandLine().usage(getStdout());
        return 0;
    }

    private static void disableGenerateCompletion(final Set<Map.Entry<String, CommandLine>> subCommands) {
        for (Map.Entry<String, CommandLine> entry : subCommands) {
            if (entry.getKey().equals("generate-completion")) {
                entry.getValue().getCommandSpec().usageMessage().hidden(true);
            } else {
                disableGenerateCompletion(entry.getValue().getSubcommands().entrySet());
            }
        }
    }

}
