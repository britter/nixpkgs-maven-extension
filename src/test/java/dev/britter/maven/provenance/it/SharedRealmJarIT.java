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

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Path;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A primary artifact (jar) shared between the project dependency closure and an IMPLICIT plugin realm
 * must be present in the implicit manifest, not dropped as PROJECT-only (issue #9). The fixture
 * declares {@code org.slf4j:slf4j-api:1.7.36}, which the default maven-resources-plugin realm also
 * pulls (via maven-filtering), so the jar is reachable from both roots. It is PROJECT (a declared
 * dependency) but must ALSO appear in the implicit manifest, or {@code defaultPluginsRepo} ships no
 * slf4j-api and a downstream package building the resources realm offline fails.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class SharedRealmJarIT {

    private static final String SLF4J_JAR = "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar";

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public SharedRealmJarIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void jarSharedWithAnImplicitRealmIsInBothManifests() throws Exception {
        File basedir = resources.getBasedir("shared-realm-jar");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();

        Path base = basedir.toPath();
        Manifest project = Manifest.read(base.resolve("target/repo-provenance.json"));
        Manifest implicit = Manifest.read(base.resolve("target/repo-provenance-implicit.json"));

        // The declared dependency is PROJECT (reachable from the project closure).
        assertTrue("slf4j-api must be PROJECT (a declared dependency):\n" + project.raw(),
                project.allFiles().contains(SLF4J_JAR));
        // ...and must ALSO be in the implicit manifest, because it is in the implicit resources
        // plugin realm; otherwise the implicit set is not self-contained for that realm (issue #9).
        assertTrue("slf4j-api shared with the implicit resources realm must be in the implicit "
                        + "manifest:\n" + implicit.raw(),
                implicit.allFiles().contains(SLF4J_JAR));
    }
}
