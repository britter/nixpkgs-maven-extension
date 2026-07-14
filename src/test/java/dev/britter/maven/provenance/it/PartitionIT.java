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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Partition correctness across both manifests (design §9.3, group D.14/D.15): the project and
 * implicit manifests are disjoint, together cover exactly the non-volatile files in the local
 * repository, list each file at most once, and never list a volatile resolution-state file. Uses an
 * isolated local repository so the manifests can be checked against the exact set of files produced.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class PartitionIT {

    private static final List<String> VOLATILE_MARKERS = List.of(
            "maven-metadata", "_remote.repositories", ".lastUpdated", "resolver-status.properties");

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public PartitionIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void manifestsPartitionTheNonVolatileRepository() throws Exception {
        File basedir = resources.getBasedir("reactor");
        Path localRepo = basedir.toPath().resolve("target/it-local-repo");

        maven.forProject(basedir)
                .withCliOption("-B")
                .withCliOption("-Dmaven.repo.local=" + localRepo.toAbsolutePath())
                .execute("clean", "package")
                .assertErrorFreeLog();

        List<String> projectFiles =
                Manifest.read(basedir.toPath().resolve("target/repo-provenance.json")).allFiles();
        List<String> implicitFiles =
                Manifest.read(basedir.toPath().resolve("target/repo-provenance-implicit.json"))
                        .allFiles();

        assertFalse("expected a non-empty PROJECT set", projectFiles.isEmpty());
        assertFalse("expected a non-empty IMPLICIT set", implicitFiles.isEmpty());

        // Each manifest lists every file at most once.
        assertEquals("a file is listed twice in the project manifest",
                new HashSet<>(projectFiles).size(), projectFiles.size());
        assertEquals("a file is listed twice in the implicit manifest",
                new HashSet<>(implicitFiles).size(), implicitFiles.size());

        Set<String> project = new HashSet<>(projectFiles);
        Set<String> implicit = new HashSet<>(implicitFiles);

        // Primary artifacts partition cleanly; only shared descriptor-closure POMs (parents, import
        // BOMs) may appear in both manifests so each is self-contained for its own descriptor-read
        // closure (issue #7). Any overlap must therefore be pom files or their checksum sidecars — a
        // jar in both manifests is still a partition failure.
        Set<String> overlap = new HashSet<>(project);
        overlap.retainAll(implicit);
        for (String file : overlap) {
            assertTrue("only descriptor-closure POMs may overlap the two manifests, not: " + file,
                    file.contains(".pom"));
        }

        Set<String> union = new HashSet<>(project);
        union.addAll(implicit);

        // No volatile resolution-state file is in either manifest (D.15).
        for (String file : union) {
            for (String marker : VOLATILE_MARKERS) {
                assertFalse("volatile file must not be in any manifest: " + file,
                        file.contains(marker));
            }
        }

        // The union equals the repository's non-volatile files: nothing missing, nothing spurious.
        Set<String> repoNonVolatile = nonVolatileFiles(localRepo);
        Set<String> missing = new HashSet<>(repoNonVolatile);
        missing.removeAll(union);
        assertTrue("non-volatile repo files covered by no manifest: " + missing, missing.isEmpty());
        Set<String> spurious = new HashSet<>(union);
        spurious.removeAll(repoNonVolatile);
        assertTrue("manifest lists files absent from the repo: " + spurious, spurious.isEmpty());
    }

    /** Repository-relative POSIX paths of every non-volatile regular file under the local repo. */
    private Set<String> nonVolatileFiles(Path localRepo) throws Exception {
        Set<String> result = new HashSet<>();
        try (Stream<Path> paths = Files.walk(localRepo)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = localRepo.relativize(path).toString().replace(File.separatorChar, '/');
                boolean volatileFile = VOLATILE_MARKERS.stream().anyMatch(relative::contains);
                if (!volatileFile) {
                    result.add(relative);
                }
            }
        }
        return result;
    }
}
