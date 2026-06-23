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
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Robustness and no-side-effects (design §7.5, group G): the extension never changes the build's
 * produced artifact and copes with trivial / pom-packaging projects.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class RobustnessIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntimeBuilder builder;

    public RobustnessIT(MavenRuntimeBuilder builder) {
        this.builder = builder;
    }

    private MavenRuntime withExtension() throws Exception {
        return builder.forkedBuilder()
                .withExtension(new File(System.getProperty("extension.jar")))
                .build();
    }

    private MavenRuntime withoutExtension() throws Exception {
        return builder.forkedBuilder().build();
    }

    @Test
    public void extensionDoesNotChangeProducedArtifact() throws Exception {
        // Build the same project once without and once with the extension; the produced jar's
        // contents must be identical, and the extension must only add the manifest/report.
        File plain = resources.getBasedir("probe");
        withoutExtension().forProject(plain).withCliOption("-B").execute("clean", "package")
                .assertErrorFreeLog();
        Set<String> withoutEntries = jarEntries(plain.toPath().resolve("target/probe-1.0.jar"));
        assertFalse("no manifest expected without the extension",
                Files.exists(plain.toPath().resolve("target/repo-provenance.json")));

        File instrumented = resources.getBasedir("probe");
        withExtension().forProject(instrumented).withCliOption("-B").execute("clean", "package")
                .assertErrorFreeLog();
        Set<String> withEntries = jarEntries(instrumented.toPath().resolve("target/probe-1.0.jar"));
        assertTrue("the extension must add its manifest",
                Files.exists(instrumented.toPath().resolve("target/repo-provenance.json")));

        assertEquals("the produced artifact differs with the extension loaded",
                withoutEntries, withEntries);
    }

    @Test
    public void pomPackagingProjectProducesValidManifest() throws Exception {
        File basedir = resources.getBasedir("pom-only");

        withExtension().forProject(basedir).withCliOption("-B").execute("clean", "install")
                .assertErrorFreeLog();

        Path manifest = basedir.toPath().resolve("target/repo-provenance.json");
        assertTrue("a manifest should be produced even for a trivial pom project",
                Files.exists(manifest));
        // It must at least be a well-formed manifest document.
        assertTrue(Files.readString(manifest).contains("\"schemaVersion\": \"1\""));
    }

    private static Set<String> jarEntries(Path jar) throws Exception {
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // The jar's own manifest carries build metadata; compare payload entries only.
                if (!entry.getName().equals("META-INF/MANIFEST.MF")) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }
}
