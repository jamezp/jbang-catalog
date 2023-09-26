///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS info.picocli:picocli:4.7.3
//DEPS jakarta.json:jakarta.json-api:2.1.2
//DEPS org.eclipse.parsson:parsson:1.1.4
//DEPS org.apache.commons:commons-compress:1.24.0
//DEPS me.tongfei:progressbar:0.10.0
//SOURCES BaseCommand.java,Install.java,JdkList.java,Info.java,JdkClient.java

package jdkmanager;

import java.nio.file.Files;
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
        final CommandLine commandLine = new CommandLine(new jdkmanager());
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        final var gen = commandLine.getSubcommands().get("generate-completion");
        gen.getCommandSpec().usageMessage().hidden(true);
        if (Files.notExists(WORK_DIR)) {
            Files.createDirectories(WORK_DIR);
        }
        final int exitStatus = commandLine.execute(args);
        System.exit(exitStatus);
    }

    @Override
    Integer call(final JdkClient client) {
        // Display the usage if there was no sub-command sent
        spec.commandLine().usage(getStdout());
        return 0;
    }

}
