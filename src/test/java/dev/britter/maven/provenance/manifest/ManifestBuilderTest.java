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

package dev.britter.maven.provenance.manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.britter.maven.provenance.ResolvedArtifact;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for the file-claiming pass that keeps a single manifest internally exactly-once and
 * lets two manifests form a disjoint partition (design §6).
 */
public class ManifestBuilderTest {

    @Rule
    public final TemporaryFolder repo = new TemporaryFolder();

    /** Writes {@code groupId/artifactId/version/artifactId-version.ext} and returns the file. */
    private File artifactFile(String groupId, String artifactId, String version, String ext)
            throws Exception {
        Path dir = repo.getRoot().toPath().resolve(groupId.replace('.', '/'))
                .resolve(artifactId).resolve(version);
        Files.createDirectories(dir);
        Path file = dir.resolve(artifactId + "-" + version + "." + ext);
        Files.writeString(file, "x");
        return file.toFile();
    }

    private ResolvedArtifact artifact(String artifactId, String type, File file) {
        return new ResolvedArtifact("g", artifactId, "1", type, null, file);
    }

    /** Writes a POM with the given XML body at {@code groupId/artifactId/version/...} and returns it. */
    private File pomFile(String groupId, String artifactId, String version, String xml)
            throws Exception {
        Path dir = repo.getRoot().toPath().resolve(groupId.replace('.', '/'))
                .resolve(artifactId).resolve(version);
        Files.createDirectories(dir);
        Path file = dir.resolve(artifactId + "-" + version + ".pom");
        Files.writeString(file, xml);
        return file.toFile();
    }

    @Test
    public void foldsPomTypeArtifactIntoTheJarEntryListingThePomOnce() throws Exception {
        File jar = artifactFile("g", "a", "1", "jar");
        artifactFile("g", "a", "1", "pom"); // sibling pom on disk, folded by the builder
        // The universe also contains the standalone pom-type resolution of the same coordinate.
        File pom = new File(jar.getParentFile(), "a-1.pom");

        List<ManifestArtifact> entries = new ManifestBuilder(repo.getRoot().toPath())
                .build(List.of(artifact("a", "jar", jar), artifact("a", "pom", pom)), new HashSet<>());

        // One entry for the coordinate; the pom is listed exactly once (folded into the jar entry).
        assertEquals(1, entries.size());
        ManifestArtifact entry = entries.get(0);
        assertEquals("jar", entry.type());
        assertEquals(List.of("g/a/1/a-1.jar", "g/a/1/a-1.pom"), entry.files());
    }

    @Test
    public void excludesFilesAlreadyClaimedAndDropsEmptiedEntries() throws Exception {
        File jar = artifactFile("g", "a", "1", "jar");

        // Pretend the jar was already claimed by another (project) manifest.
        Set<String> claimed = new HashSet<>(Set.of("g/a/1/a-1.jar"));
        List<ManifestArtifact> entries = new ManifestBuilder(repo.getRoot().toPath())
                .build(List.of(artifact("a", "jar", jar)), claimed);

        // The only file was claimed elsewhere, so the entry is dropped entirely.
        assertTrue("entry whose files are all claimed must be dropped", entries.isEmpty());
    }

    @Test
    public void capturesImportScopeBomReferencedByAParentPom() throws Exception {
        // com.ex:child:1 -> parent com.ex:par:1, which imports org.bom:the-bom:${bomVer} (=2).
        // Mirrors the real plexus-xml -> plexus:18 -> junit-bom:${junit5Version} case (issue #5).
        File childJar = artifactFile("com.ex", "child", "1", "jar");
        pomFile("com.ex", "child", "1",
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>com.ex</groupId><artifactId>par</artifactId>"
                        + "<version>1</version></parent>"
                        + "<artifactId>child</artifactId></project>");
        pomFile("com.ex", "par", "1",
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>com.ex</groupId><artifactId>par</artifactId><version>1</version>"
                        + "<properties><bomVer>2</bomVer></properties>"
                        + "<dependencyManagement><dependencies><dependency>"
                        + "<groupId>org.bom</groupId><artifactId>the-bom</artifactId>"
                        + "<version>${bomVer}</version><type>pom</type><scope>import</scope>"
                        + "</dependency></dependencies></dependencyManagement></project>");
        pomFile("org.bom", "the-bom", "2",
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>org.bom</groupId><artifactId>the-bom</artifactId>"
                        + "<version>2</version></project>");

        List<ManifestArtifact> entries = new ManifestBuilder(repo.getRoot().toPath())
                .build(List.of(new ResolvedArtifact("com.ex", "child", "1", "jar", null, childJar)),
                        new HashSet<>());

        List<String> allFiles = entries.stream().flatMap(e -> e.files().stream()).toList();
        assertTrue("import-scope BOM referenced by the parent POM must be captured:\n" + allFiles,
                allFiles.contains("org/bom/the-bom/2/the-bom-2.pom"));
    }

    @Test
    public void claimingMakesTwoBuildsDisjoint() throws Exception {
        File sharedJar = artifactFile("g", "shared", "1", "jar");
        File onlyImplicit = artifactFile("g", "impl", "1", "jar");

        ManifestBuilder builder = new ManifestBuilder(repo.getRoot().toPath());
        Set<String> claimed = new HashSet<>();
        List<ManifestArtifact> project =
                builder.build(List.of(artifact("shared", "jar", sharedJar)), claimed);
        List<ManifestArtifact> implicit = builder.build(
                List.of(artifact("shared", "jar", sharedJar), artifact("impl", "jar", onlyImplicit)),
                claimed);

        assertEquals(1, project.size());
        // The shared artifact was claimed by the project build, so implicit only keeps "impl".
        assertEquals(1, implicit.size());
        assertEquals("impl", implicit.get(0).artifactId());
    }
}
