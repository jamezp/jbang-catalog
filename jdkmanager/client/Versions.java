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

package jdkmanager.client;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Represents a collection of JDK versions.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public record Versions(Set<Version> available) {
    /**
     * Creates the available versions filtering out duplicates of early access versions when there is a release of
     * the version.
     *
     * @param available the versions to add
     */
    public Versions(final Set<Version> available) {
        // Filter out early access versions where his is a released version
        final Map<Integer, Version> versions = new TreeMap<>();
        for (Version version : available) {
            if (versions.containsKey(version.version())) {
                final Version current = versions.get(version.version());
                if (current.earlyAccess() && !version.earlyAccess()) {
                    versions.put(version.version(), version);
                }
            } else {
                versions.put(version.version(), version);
            }
        }
        this.available = new TreeSet<>(versions.values());
    }

    /**
     * All available versions.
     *
     * @return all the available versions
     */
    @Override
    public Set<Version> available() {
        return new TreeSet<>(available);
    }

    /**
     * The long term support (LTS) versions.
     *
     * @return the LTS versions
     */
    public Set<Version> lts() {
        return available.stream()
                .filter(v -> !v.earlyAccess() && v.lts())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The short term support (STS) versions.
     *
     * @return the short versions
     */
    public Set<Version> sts() {
        return available.stream()
                .filter(v -> !v.lts())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The latest LTS version.
     *
     * @return the latest LTS version
     */
    public Version latestLts() {
        return available.stream()
                .filter(v -> !v.earlyAccess() && v.lts())
                .findFirst().orElse(null);
    }

    /**
     * All the early access versions.
     *
     * @return the early access versions
     */
    public Set<Version> earlyAccess() {
        return available.stream()
                .filter(Version::earlyAccess)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
