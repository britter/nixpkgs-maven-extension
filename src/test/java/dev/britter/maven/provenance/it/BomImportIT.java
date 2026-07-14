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

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The descriptor-read closure of a PROJECT artifact must include the import-scope BOM POMs its
 * parents reference (issue #5). Reading {@code org.codehaus.plexus:plexus-xml:3.0.1}'s descriptor
 * resolves its parent {@code org.codehaus.plexus:plexus:18}, which imports
 * {@code org.junit:junit-bom:5.10.2} (pinned via {@code ${junit5Version}}). That BOM POM must be
 * listed in the PROJECT manifest, or an offline build cannot read the descriptor.
 *
 * <p>A {@code .pom}-only artifact has no {@code g:a:v} jar entry, so membership is asserted through
 * the manifest's {@code files} paths rather than {@link Manifest#contains}.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class BomImportIT {

    private static final String JUNIT_BOM_POM = "org/junit/junit-bom/5.10.2/junit-bom-5.10.2.pom";

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public BomImportIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void importScopeBomOfAParentPomIsCaptured() throws Exception {
        File basedir = resources.getBasedir("bom-import");
        Path localRepo = Files.createTempDirectory("repo-prov-bom-import");
        try {
            maven.forProject(basedir)
                    .withCliOption("-B")
                    .withCliOption("-Dmaven.repo.local=" + localRepo.toAbsolutePath())
                    .execute("clean", "package")
                    .assertErrorFreeLog();

            Manifest project = Manifest.read(basedir.toPath().resolve("target/repo-provenance.json"));
            assertTrue("import-scope BOM junit-bom:5.10.2 referenced by plexus:18 must be captured:\n"
                    + project.raw(), project.allFiles().contains(JUNIT_BOM_POM));
        } finally {
            OfflineRepos.deleteRecursively(localRepo);
        }
    }
}
