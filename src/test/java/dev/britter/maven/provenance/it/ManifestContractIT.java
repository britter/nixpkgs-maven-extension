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
    public void manifestValidatesAgainstSchema() throws Exception {
        File basedir = resources.getBasedir("reactor");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();

        Path manifest = basedir.toPath().resolve("target/repo-provenance.json");
        Set<ValidationMessage> errors = validate(manifest);
        assertTrue("manifest violates schema: " + errors, errors.isEmpty());
    }

    @Test
    public void manifestIsReproducible() throws Exception {
        File basedir = resources.getBasedir("reactor");
        Path manifest = basedir.toPath().resolve("target/repo-provenance.json");

        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();
        byte[] first = Files.readAllBytes(manifest);

        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();
        byte[] second = Files.readAllBytes(manifest);

        assertArrayEquals("re-running the build produced a different manifest", first, second);
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
