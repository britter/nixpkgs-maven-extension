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
