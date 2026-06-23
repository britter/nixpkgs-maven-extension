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
import static org.junit.Assert.assertNotNull;

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
 * The load-bearing provenance probe (design §9.1): on a real forked build it confirms that a
 * project-pinned plugin and a default-bound (unpinned) plugin are reliably distinguishable, and
 * that this holds identically across Maven 3.9.6 and 3.9.12.
 *
 * <p>Running under {@code @MavenVersions} also proves the harness itself: that takari can provision
 * and fork both Maven releases under the JDK running the test.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class ProvenanceProbeIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public ProvenanceProbeIT(MavenRuntimeBuilder builder) throws Exception {
        // Force a real forked Maven process and load our core extension exactly as a consumer would,
        // via -Dmaven.ext.class.path (the forked launcher maps withExtension to that property). The
        // embedded launcher injects extensions into a classworlds realm whose Sisu discovery is not
        // consistent across Maven 3.9.x releases, so we deliberately avoid it.
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void pinnedIsProjectAndUnpinnedIsImplicit() throws Exception {
        File basedir = resources.getBasedir("probe");

        maven.forProject(basedir)
                .withCliOption("-B")
                .execute("package")
                .assertErrorFreeLog();

        Path report = basedir.toPath().resolve("target/repo-provenance-report.json");
        ProvenanceReport provenance = ProvenanceReport.read(report);

        String compiler = provenance.provenanceOf("org.apache.maven.plugins:maven-compiler-plugin");
        String surefire = provenance.provenanceOf("org.apache.maven.plugins:maven-surefire-plugin");

        assertNotNull("compiler plugin missing from report:\n" + provenance.raw(), compiler);
        assertNotNull("surefire plugin missing from report:\n" + provenance.raw(), surefire);

        // Pinned in the project POM -> PROJECT; declared without a version -> IMPLICIT (§5.1).
        assertEquals("pinned maven-compiler-plugin should be PROJECT", "PROJECT", compiler);
        assertEquals("unpinned maven-surefire-plugin should be IMPLICIT", "IMPLICIT", surefire);
    }
}
