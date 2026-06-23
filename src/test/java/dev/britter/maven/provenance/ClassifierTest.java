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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** Unit tests for the pure reachability rule (design §5.2). */
public class ClassifierTest {

    private static ResolvedArtifact artifact(String id, String absPath) {
        return new ResolvedArtifact("g", id, "1", "jar", null, new File(absPath));
    }

    @Test
    public void keepsOnlyArtifactsReachableFromProjectRoots() {
        ResolvedArtifact projectDep = artifact("project-dep", "/repo/g/project-dep/1/project-dep-1.jar");
        ResolvedArtifact realmDep = artifact("realm-dep", "/repo/g/realm-dep/1/realm-dep-1.jar");
        ResolvedArtifact implicitOnly = artifact("implicit", "/repo/g/implicit/1/implicit-1.jar");

        List<ResolvedArtifact> universe = List.of(projectDep, realmDep, implicitOnly);
        Set<String> projectFiles = Set.of(
                projectDep.file().getAbsolutePath(),
                realmDep.file().getAbsolutePath());

        List<ResolvedArtifact> result = Classifier.projectArtifacts(universe, projectFiles);

        assertEquals(2, result.size());
        assertTrue(result.contains(projectDep));
        assertTrue(result.contains(realmDep));
        assertTrue("implicit-only artifact must be omitted", !result.contains(implicitOnly));
    }

    @Test
    public void sharedArtifactReachableFromAProjectRootIsProject() {
        // An artifact shared between a PROJECT plugin realm and an implicit one: as long as it is in
        // the project file union it is PROJECT (§5.2), regardless of what else also references it.
        ResolvedArtifact shared = artifact("shared", "/repo/g/shared/1/shared-1.jar");
        List<ResolvedArtifact> result = Classifier.projectArtifacts(
                List.of(shared), Set.of(shared.file().getAbsolutePath()));
        assertEquals(List.of(shared), result);
    }

    @Test
    public void artifactWithoutFileIsNeverProject() {
        ResolvedArtifact noFile = new ResolvedArtifact("g", "a", "1", "jar", null, null);
        assertTrue(Classifier.projectArtifacts(List.of(noFile), Set.of("/anything")).isEmpty());
    }
}
