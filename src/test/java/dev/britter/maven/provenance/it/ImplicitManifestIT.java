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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The implicit manifest as a first-class output (design §6.2/§6.3, test group H). Uses the plain
 * jar fixture, which pins nothing and has no tests, so the default plugins and their deep realm
 * closures are all IMPLICIT.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class ImplicitManifestIT {

    private static final Map<String, byte[]> PROJECT = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> IMPLICIT = new ConcurrentHashMap<>();

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public ImplicitManifestIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    private Manifest buildAndReadImplicit(File basedir) throws Exception {
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();
        return Manifest.read(basedir.toPath().resolve("target/repo-provenance-implicit.json"));
    }

    @Test
    public void implicitManifestIsEmittedAndRealmAware() throws Exception {
        // H.25 emitted + non-empty for a build using default plugins; H.26 realm-aware: it carries
        // not just the default plugin jars but their deep realm dependencies.
        Manifest implicit = buildAndReadImplicit(resources.getBasedir("plain"));

        assertTrue("default maven-compiler-plugin should be IMPLICIT:\n" + implicit.raw(),
                implicit.contains("org.apache.maven.plugins:maven-compiler-plugin"));
        assertTrue("deep realm dep plexus-compiler-api should be IMPLICIT:\n" + implicit.raw(),
                implicit.contains("org.codehaus.plexus:plexus-compiler-api"));
    }

    @Test
    public void noTestProjectHasNoSurefireProvider() throws Exception {
        // H.29 provider presence is driven by the project's test setup, not unconditional.
        Manifest implicit = buildAndReadImplicit(resources.getBasedir("plain"));
        assertFalse("a project with no tests must not pull a surefire provider:\n" + implicit.raw(),
                implicit.raw().contains("surefire-junit") || implicit.raw().contains("surefire-testng"));
    }

    @Test
    public void implicitVariesWhileProjectIsStableAcrossVersions() throws Exception {
        // H.28 the two manifests move independently: implicit tracks the Maven version (3.9.6 bundles
        // surefire 3.2.2, 3.9.12 bundles 3.2.5), project stays byte-identical.
        File basedir = resources.getBasedir("plain");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();

        byte[] project = Files.readAllBytes(basedir.toPath().resolve("target/repo-provenance.json"));
        byte[] implicit =
                Files.readAllBytes(basedir.toPath().resolve("target/repo-provenance-implicit.json"));
        String version = maven.getMavenVersion();
        PROJECT.put(version, project);
        IMPLICIT.put(version, implicit);

        for (String peer : PROJECT.keySet()) {
            if (!peer.equals(version)) {
                assertTrue("project manifest must be identical across Maven versions",
                        Arrays.equals(project, PROJECT.get(peer)));
                assertFalse("implicit manifest must differ across Maven versions",
                        Arrays.equals(implicit, IMPLICIT.get(peer)));
            }
        }
    }
}
