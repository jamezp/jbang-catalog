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
import java.util.concurrent.Callable;

import jdkmanager.client.CacheFile;
import jdkmanager.client.JdkClient;
import picocli.AutoComplete;
import picocli.CommandLine;

/**
 * Displays the available distributions.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@CommandLine.Command(name = "distributions", description = "Lists the available JDK distribution names.",
        subcommands = AutoComplete.GenerateCompletion.class)
public class DistributionCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-i", "--include-synonyms"}, description = "Indicates whether or not the synonyms should also be listed.", defaultValue = "false")
    private boolean includeSynonyms;

    @SuppressWarnings("unused")
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help message")
    private boolean usageHelpRequested;

    @CommandLine.Option(names = {"-r", "--refresh"}, description = "Refreshes any local cache being used")
    private boolean refresh;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Prints verbose output.")
    private boolean verbose;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        final var console = new ConsoleWriter(spec.commandLine().getOut(), spec.commandLine()
                .getErr(), spec.commandLine().getColorScheme().ansi(), verbose);
        try (JdkClient client = JdkClient.of(console, "")) {
            if (refresh) {
                final CacheFile cacheFile = client.supportedCacheFile();
                try {
                    cacheFile.evict();
                } catch (IOException e) {
                    if (verbose) {
                        console.printError(e, "Unable to delete cache file %s", cacheFile.file());
                    } else {
                        console.printError("Unable to delete cache file %s", cacheFile.file());
                    }
                    return 1;
                }
            }
            final var supportedDistributions = client.supportedDistributions();
            for (var distribution : supportedDistributions) {
                console.print(distribution.name());
                if (includeSynonyms) {
                    for (var synonym : distribution.synonyms()) {
                        console.print(4, synonym);
                    }
                    console.print();
                }
            }
        }
        return 0;
    }
}
