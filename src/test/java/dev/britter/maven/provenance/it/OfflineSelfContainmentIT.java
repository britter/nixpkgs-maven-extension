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
 * Offline self-containment of the PROJECT set (design §9.5, group E):
 * <ul>
 *   <li>E.17 — the PROJECT files include the full parent-POM lineage of a project dependency, and</li>
 *   <li>E.16 — splitting the local repository into the PROJECT set and its complement (the IMPLICIT
 *       set) and recombining them yields a repository that builds offline.</li>
 * </ul>
 *
 * <p>The repositories live in a temporary workspace outside the project's {@code target/}, so the
 * reactor's {@code clean} does not delete the local repository the offline build runs against.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class OfflineSelfContainmentIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public OfflineSelfContainmentIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void projectSetIncludesParentLineageAndReplaysOffline() throws Exception {
        File basedir = resources.getBasedir("reactor");
        Path workspace = Files.createTempDirectory("repo-prov-offline");
        try {
            Path localRepo = workspace.resolve("full-repo");
            maven.forProject(basedir)
                    .withCliOption("-B")
                    .withCliOption("-Dmaven.repo.local=" + localRepo.toAbsolutePath())
                    .execute("clean", "package")
                    .assertErrorFreeLog();

            Manifest manifest =
                    Manifest.read(basedir.toPath().resolve("target/repo-provenance.json"));

            // E.17: the project dependency commons-lang3 carries a parent lineage; it must be
            // PROJECT so the project set is self-contained for interpreting it offline.
            assertTrue("parent POM commons-parent should be in the PROJECT set:\n" + manifest.raw(),
                    manifest.contains("org.apache.commons:commons-parent"));
            assertTrue("root parent POM org.apache:apache should be in the PROJECT set:\n"
                            + manifest.raw(),
                    manifest.contains("org.apache:apache"));

            // E.16: PROJECT set + complement (IMPLICIT) recombine into a repo that builds offline.
            Set<String> projectFiles = new HashSet<>(manifest.allFiles());
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
}
