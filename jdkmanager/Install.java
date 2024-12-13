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

import java.nio.file.Path;

import picocli.AutoComplete;
import picocli.CommandLine;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@CommandLine.Command(name = "install", description = "Installs a JDK locally.",
        subcommands = AutoComplete.GenerateCompletion.class)
public class Install extends BaseCommand {

    // TODO (jrp) add an option to look for JavaFX included
    @CommandLine.Parameters(arity = "0..1", description = "The version you'd like to install if missing and resolve the path to set for the JAVA_HOME environment variable.")
    private int version;

    @Override
    Integer call(final JdkClient client) throws Exception {
        final int version = this.version > 0 ? this.version : client.latestLts();
        if (version > 0) {
            final Path javaHome = distributionDir().resolve("jdk-" + version);
            client.download(javaHome, version, false)
                    .thenRun(() -> {
                        print(javaHome);
                    }).toCompletableFuture().get();
        }
        return 0;
    }
}
