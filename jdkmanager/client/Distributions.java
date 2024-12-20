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

import java.util.Iterator;
import java.util.Set;

/**
 * Represents a collection of {@linkplain Distribution distributions}.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public record Distributions(Set<Distribution> distributions) implements Iterable<Distribution> {

    /**
     * Checks if the passed in string is a support distribution.
     *
     * @param name the distribution name or synonym
     *
     * @return {@code true} if the name is a supported distribution
     */
    public boolean isSupported(final String name) {
        for (Distribution distribution : this) {
            if (distribution.name().equals(name)) {
                return true;
            }
            if (distribution.synonyms().contains(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<Distribution> iterator() {
        return distributions.iterator();
    }
}
