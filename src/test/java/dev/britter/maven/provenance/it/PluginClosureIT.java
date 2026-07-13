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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
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
 * The full resolution closure of a PROJECT-declared plugin/extension must be PROJECT, not IMPLICIT
 * (issue #3). Capturing only the plugin's realized {@code ClassRealm} misses:
 * <ul>
 *   <li>transitive artifacts read while resolving the plugin that are mediated OUT of its final
 *       realm (repro 1: {@code com.mycila:maven-license-plugin:1.10.b1} pulls
 *       {@code maven-project:3.0-alpha-2} / {@code maven-plugin-api:3.0.1}), and</li>
 *   <li>build extensions, whose realm id is prefixed {@code extension>} rather than
 *       {@code plugin>} (repro 2: {@code nexus-staging-maven-plugin:1.6.13}).</li>
 * </ul>
 *
 * <p>Both are proven by an offline replay: split the local repo into the PROJECT set and its
 * complement, then rebuild from the PROJECT+IMPLICIT recombination offline. If the closure is
 * pruned from the project manifest, the offline build cannot resolve the plugin.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class PluginClosureIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public PluginClosureIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void pluginClosureIsProjectAndReplaysOffline() throws Exception {
        File basedir = resources.getBasedir("plugin-closure");
        buildAndReplayOffline(basedir, manifest -> {
            // Transitive deps mediated out of the license plugin's realm: they must be PROJECT so
            // the offline build can resolve the plugin, and must not leak into the implicit set.
            assertProjectNotImplicit(manifest, "org.apache.maven:maven-project:3.0-alpha-2");
            assertProjectNotImplicit(manifest, "org.apache.maven:maven-plugin-api:3.0.1");
        });
    }

    @Test
    public void buildExtensionIsProjectAndReplaysOffline() throws Exception {
        File basedir = resources.getBasedir("build-extension");
        buildAndReplayOffline(basedir, manifest ->
                // The extension's own jar lives in an "extension>" realm; it must be PROJECT.
                assertProjectNotImplicit(manifest,
                        "org.sonatype.plugins:nexus-staging-maven-plugin:1.6.13"));
    }

    /**
     * Builds the project online into a temp local repo, runs the caller's manifest assertions, then
     * partitions the repo into PROJECT/IMPLICIT and rebuilds offline from the recombination.
     */
    private void buildAndReplayOffline(File basedir, ManifestCheck check) throws Exception {
        Path workspace = Files.createTempDirectory("repo-prov-plugin-closure");
        try {
            Path localRepo = workspace.resolve("full-repo");
            maven.forProject(basedir)
                    .withCliOption("-B")
                    .withCliOption("-Dmaven.repo.local=" + localRepo.toAbsolutePath())
                    .execute("clean", "package")
                    .assertErrorFreeLog();

            Manifests manifests = new Manifests(
                    Manifest.read(basedir.toPath().resolve("target/repo-provenance.json")),
                    Manifest.read(basedir.toPath().resolve("target/repo-provenance-implicit.json")));
            check.check(manifests);

            Set<String> projectFiles = new HashSet<>(manifests.project.allFiles());
            Path combined = workspace.resolve("recombined-repo");
            Path projectRepo = workspace.resolve("project-repo");
            Path implicitRepo = workspace.resolve("implicit-repo");
            OfflineRepos.partition(localRepo, projectFiles, projectRepo, implicitRepo, combined);

            maven.forProject(basedir)
                    .withCliOption("-B")
                    .withCliOption("-o")
                    .withCliOption("-Dmaven.repo.local=" + combined.toAbsolutePath())
                    .execute("clean", "package")
                    .assertErrorFreeLog();
        } finally {
            OfflineRepos.deleteRecursively(workspace);
        }
    }

    private static void assertProjectNotImplicit(Manifests manifests, String coordinate) {
        assertTrue(coordinate + " should be PROJECT:\n" + manifests.project.raw(),
                manifests.project.contains(coordinate));
        assertFalse(coordinate + " must not be in the implicit manifest:\n" + manifests.implicit.raw(),
                manifests.implicit.contains(coordinate));
    }

    @FunctionalInterface
    private interface ManifestCheck {
        void check(Manifests manifests) throws IOException;
    }

    private static final class Manifests {
        final Manifest project;
        final Manifest implicit;

        Manifests(Manifest project, Manifest implicit) {
            this.project = project;
            this.implicit = implicit;
        }
    }
}
