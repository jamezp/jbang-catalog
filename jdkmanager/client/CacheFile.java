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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Represents a cached file.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public record CacheFile(Path file, int daysToKeep) {

    public CacheFile(final Path file) {
        this(file, 0);
    }

    public CacheFile(final Path file, final int daysToKeep) {
        this.file = file;
        this.daysToKeep = daysToKeep > 0 ? (daysToKeep - 1) : 0;
    }

    /**
     * Simply deletes the file.
     *
     * @throws IOException if an error occurs deleting the file
     */
    public void evict() throws IOException {
        Files.deleteIfExists(file);
    }

    /**
     * Indicates where the cached file should be re-downloaded.
     *
     * @return {@code true} if the cached file should be re-downloaded, otherwise {@code false}
     */
    public boolean requiresDownload() {
        return Files.notExists(file) || isExpired();
    }

    private boolean isExpired() {
        try {
            if (Files.exists(file)) {
                final var lastModified = Files.getLastModifiedTime(file).toInstant();
                final var lastModDate = LocalDate.ofInstant(lastModified, ZoneId.systemDefault());
                final var cacheDate = LocalDate.now().minusDays(daysToKeep);
                if (lastModDate.isBefore(cacheDate)) {
                    return true;
                }
            } else {
                return true;
            }
        } catch (IOException ignore) {
            return true;
        }
        return false;
    }
}
