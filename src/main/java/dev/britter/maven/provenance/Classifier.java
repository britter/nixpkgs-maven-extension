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

import java.util.List;
import java.util.Set;

/**
 * The pure reachability rule of design §5.2/§7.1: an artifact written to the local repository is
 * PROJECT iff its file is reachable from a PROJECT root — the project dependency closure or the
 * realm closure of any PROJECT plugin/extension (plus project-supplied plugin dependencies, §7.3).
 * Everything else is IMPLICIT by omission.
 *
 * <p>Reachability is modelled as set membership over absolute file paths: the caller computes the
 * union of all PROJECT root files (closures + realms), and this class keeps exactly the observed
 * artifacts whose file is in that union. Modelling it as a union is what makes a dependency shared
 * between a PROJECT plugin and an IMPLICIT one come out PROJECT (§5.2), so it is never wrongly
 * dropped.
 *
 * <p>Pure and side-effect free, so it can be unit-tested in isolation.
 */
public final class Classifier {

    private Classifier() {
    }

    /**
     * Returns the subset of {@code universe} that is PROJECT: those artifacts whose resolved file's
     * absolute path is in {@code projectFilePaths}.
     */
    public static List<ResolvedArtifact> projectArtifacts(
            List<ResolvedArtifact> universe, Set<String> projectFilePaths) {
        return universe.stream()
                .filter(a -> a.file() != null
                        && projectFilePaths.contains(a.file().getAbsolutePath()))
                .toList();
    }
}
