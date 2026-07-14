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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test-scope project dependencies must be PROJECT, not IMPLICIT (issue #1). A declared test
 * dependency and its closure are project-controlled regardless of scope, so they belong in the
 * project manifest and must be absent from the implicit manifest. The bug only surfaces under a
 * {@code package} build: a later runtime-scope mojo (maven-jar-plugin) re-resolves and drops the
 * test closure from {@code MavenProject.getArtifacts()}, so these tests build with {@code package}.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class TestScopeDependencyIT {

    // The test-scope dependency's closure at its resolved versions. Coordinates are versioned
    // because the surefire provider independently pulls an OLDER opentest4j (1.2.0), which is
    // correctly IMPLICIT; the project's own opentest4j 1.3.0 must be PROJECT. Matching on
    // groupId:artifactId alone would conflate the two.
    private static final List<String> TEST_CLOSURE = List.of(
            "org.junit.jupiter:junit-jupiter:5.10.2",
            "org.junit.jupiter:junit-jupiter-api:5.10.2",
            "org.apiguardian:apiguardian-api:1.1.2",
            "org.opentest4j:opentest4j:1.3.0");

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public TestScopeDependencyIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    private Manifest project(File basedir) throws IOException {
        return Manifest.read(basedir.toPath().resolve("target/repo-provenance.json"));
    }

    private Manifest implicit(File basedir) throws IOException {
        return Manifest.read(basedir.toPath().resolve("target/repo-provenance-implicit.json"));
    }

    @Test
    public void singleModuleTestScopeClosureIsProject() throws Exception {
        File basedir = resources.getBasedir("junit5");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();

        assertClosureIsProjectNotImplicit(project(basedir), implicit(basedir));
    }

    @Test
    public void reactorTestScopeClosureIsProject() throws Exception {
        File basedir = resources.getBasedir("reactor");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();

        Manifest project = project(basedir);
        Manifest implicit = implicit(basedir);
        assertClosureIsProjectNotImplicit(project, implicit);

        // The partition still holds for primary artifacts; only shared descriptor-closure POMs
        // (parents, import BOMs — e.g. junit-bom, imported by both the project's JUnit and plexus)
        // may appear in both manifests so each is self-contained for its own descriptor-read closure
        // (design §6, issue #7). Any overlap must therefore be pom files or their checksum sidecars.
        Set<String> overlap = new HashSet<>(project.allFiles());
        overlap.retainAll(new HashSet<>(implicit.allFiles()));
        for (String file : overlap) {
            assertTrue("only descriptor-closure POMs may overlap the two manifests, not: " + file,
                    file.contains(".pom"));
        }
    }

    private void assertClosureIsProjectNotImplicit(Manifest project, Manifest implicit) {
        for (String coordinate : TEST_CLOSURE) {
            assertTrue(coordinate + " should be PROJECT:\n" + project.raw(),
                    project.contains(coordinate));
            assertFalse(coordinate + " must not be in the implicit manifest:\n" + implicit.raw(),
                    implicit.contains(coordinate));
        }
    }
}
