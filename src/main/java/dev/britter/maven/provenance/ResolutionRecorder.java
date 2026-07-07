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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.aether.RepositoryEvent;

/**
 * Shared, build-scoped collector of everything resolved into the local repository.
 *
 * <p>This singleton is written concurrently: in a parallel reactor build ({@code mvn -T})
 * {@link RepositoryEvent}s fire on several worker threads at once. All mutable state is therefore
 * held in thread-safe structures and updated without check-then-act races. Reads happen only at
 * session end, after every worker thread has finished (a happens-before barrier), so the
 * classification that consumes this data runs single-threaded over a stable snapshot.
 *
 * <p>We deduplicate on insertion (an artifact/file is typically resolved many times across a
 * reactor) using the keys of concurrent maps, which makes the recorded sets independent of thread
 * interleaving — a precondition for the manifest being identical between serial and parallel
 * builds.
 */
@Named
@Singleton
public class ResolutionRecorder {

    // Keyed by absolute file path so repeated resolutions of the same file collapse to one entry.
    private final Map<String, ResolvedArtifact> artifacts = new ConcurrentHashMap<>();

    // Absolute paths of files resolved as part of a project-dependency resolution (any scope). A
    // file resolved as a project dependency in any event lands here, so PROJECT precedence over a
    // shared plugin resolution is automatic (set union, design §5.2).
    private final Set<String> projectDependencyFiles = ConcurrentHashMap.newKeySet();

    /** Records a single artifact resolution. Safe to call from any thread. */
    public void recordArtifact(ResolvedArtifact artifact) {
        if (artifact.file() != null) {
            artifacts.putIfAbsent(artifact.file().getAbsolutePath(), artifact);
        }
    }

    /** Records that a file was resolved as a project dependency. Safe to call from any thread. */
    public void recordProjectDependencyFile(File file) {
        if (file != null) {
            projectDependencyFiles.add(file.getAbsolutePath());
        }
    }

    /** Absolute paths of every file observed being resolved as a project dependency. */
    public Set<String> projectDependencyFiles() {
        return Set.copyOf(projectDependencyFiles);
    }

    /**
     * The distinct artifacts observed, in a deterministic order independent of resolution timing.
     */
    public List<ResolvedArtifact> distinctArtifacts() {
        return artifacts.values().stream()
                .sorted(Comparator
                        .comparing(ResolvedArtifact::groupId)
                        .thenComparing(ResolvedArtifact::artifactId)
                        .thenComparing(ResolvedArtifact::version)
                        .thenComparing(ResolvedArtifact::type)
                        .thenComparing(a -> a.classifier() == null ? "" : a.classifier())
                        .thenComparing(a -> a.file().getAbsolutePath()))
                .toList();
    }
}
