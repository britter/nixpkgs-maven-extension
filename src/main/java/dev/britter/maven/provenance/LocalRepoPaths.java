/*
 * Copyright 2026 Benedikt Ritter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.britter.maven.provenance;

import java.io.File;
import java.nio.file.Path;

/**
 * Pure helpers for turning absolute local-repository files into the canonical, repository-relative
 * POSIX paths the manifest uses (design §6.2): forward slashes, no leading slash.
 */
public final class LocalRepoPaths {

    private LocalRepoPaths() {
    }

    /**
     * Relativizes {@code file} against the local repository base directory and returns a POSIX
     * path, or {@code null} if {@code file} lies outside the repository.
     */
    public static String relativize(Path localRepoBase, File file) {
        if (file == null) {
            return null;
        }
        Path normalizedBase = localRepoBase.toAbsolutePath().normalize();
        Path normalizedFile = file.toPath().toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedBase)) {
            return null;
        }
        Path relative = normalizedBase.relativize(normalizedFile);
        return toPosix(relative);
    }

    private static String toPosix(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(relative.getName(i));
        }
        return sb.toString();
    }
}
