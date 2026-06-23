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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The manifest's output contract (design §6.2): it must validate against
 * {@code docs/manifest.schema.json} and be reproducible — re-running the same build produces a
 * byte-identical manifest.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class ManifestContractIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public ManifestContractIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void bothManifestsValidateAgainstSchema() throws Exception {
        File basedir = resources.getBasedir("reactor");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();

        // Both manifests share the identical shape and validate against the one schema (design §6.2).
        Set<ValidationMessage> projectErrors =
                validate(basedir.toPath().resolve("target/repo-provenance.json"));
        assertTrue("project manifest violates schema: " + projectErrors, projectErrors.isEmpty());
        Set<ValidationMessage> implicitErrors =
                validate(basedir.toPath().resolve("target/repo-provenance-implicit.json"));
        assertTrue("implicit manifest violates schema: " + implicitErrors, implicitErrors.isEmpty());
    }

    @Test
    public void bothManifestsAreReproducible() throws Exception {
        File basedir = resources.getBasedir("reactor");
        Path projectManifest = basedir.toPath().resolve("target/repo-provenance.json");
        Path implicitManifest = basedir.toPath().resolve("target/repo-provenance-implicit.json");

        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();
        byte[] firstProject = Files.readAllBytes(projectManifest);
        byte[] firstImplicit = Files.readAllBytes(implicitManifest);

        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();
        assertArrayEquals("re-running changed the project manifest",
                firstProject, Files.readAllBytes(projectManifest));
        // Same Maven version -> the implicit manifest is byte-identical too (design §6.3, H.27).
        assertArrayEquals("re-running changed the implicit manifest",
                firstImplicit, Files.readAllBytes(implicitManifest));
    }

    private static Set<ValidationMessage> validate(Path manifest) throws Exception {
        // The schema lives in the extension repo, not the fixture; tests run from the project root.
        Path schemaPath = Paths.get("docs/manifest.schema.json");
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream schemaStream = Files.newInputStream(schemaPath)) {
            JsonSchema schema = factory.getSchema(schemaStream);
            JsonNode node = new ObjectMapper().readTree(manifest.toFile());
            return schema.validate(node);
        }
    }
}
