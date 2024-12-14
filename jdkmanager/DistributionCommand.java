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
public class DistributionCommand extends BaseCommand {

    @CommandLine.Option(names = {"-i", "--include-synonyms"}, description = "Indicates whether or not the synonyms should also be listed.", defaultValue = "false")
    private boolean includeSynonyms;

    @Override
    Integer call(final JdkClient client) {
        final var supportedDistributions = client.supportedDistributions();
        for (var distribution : supportedDistributions) {
            print(distribution.name());
            if (includeSynonyms) {
                for (var synonym : distribution.synonyms()) {
                    print(4, synonym);
                }
                print();
            }
        }
        return 0;
    }
}
