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

package dev.britter.maven.provenance.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Test helper for splitting a local repository into the PROJECT set and its complement (the
 * IMPLICIT set) and proving the two recombine into a repository that builds offline (design §9.5,
 * group E). Shared by the ITs that assert offline self-containment.
 */
final class OfflineRepos {

    private OfflineRepos() {
    }

    /**
     * Copies every file of {@code source} into the project repo or the implicit repo depending on
     * whether the manifest lists it, and into the combined repo unconditionally. Proves the PROJECT
     * set and its complement are a lossless partition usable offline.
     */
    static void partition(
            Path source, Set<String> projectFiles, Path projectRepo, Path implicitRepo, Path combined)
            throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            List<Path> regular = files.filter(Files::isRegularFile).toList();
            for (Path file : regular) {
                String relative = posix(source.relativize(file));
                copy(file, combined.resolve(relative));
                if (projectFiles.contains(relative)) {
                    copy(file, projectRepo.resolve(relative));
                } else {
                    copy(file, implicitRepo.resolve(relative));
                }
            }
        }
    }

    static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to);
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    static String posix(Path relative) {
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
