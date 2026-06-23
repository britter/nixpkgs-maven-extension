package dev.britter.maven.provenance.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
 * Gray-zone characterisation (design §8, group F): the surefire provider an IMPLICIT plugin selects
 * at runtime is classified IMPLICIT (absent from the manifest) and surfaced distinctly in the
 * report's evidence stream, on both Maven versions.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class GrayZoneIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public GrayZoneIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void surefireProviderIsImplicitAndSurfaced() throws Exception {
        File basedir = resources.getBasedir("junit5");

        maven.forProject(basedir).withCliOption("-B").execute("test").assertErrorFreeLog();

        Path manifestFile = basedir.toPath().resolve("target/repo-provenance.json");
        Path reportFile = basedir.toPath().resolve("target/repo-provenance-report.json");

        Manifest manifest = Manifest.read(manifestFile);
        ProvenanceReport report = ProvenanceReport.read(reportFile);

        // The JUnit 5 provider is realm-reachable only from the implicit surefire plugin -> IMPLICIT.
        assertFalse("surefire-junit-platform provider must not be PROJECT:\n" + manifest.raw(),
                manifest.contains("org.apache.maven.surefire:surefire-junit-platform"));

        // ... but it is surfaced distinctly in the report's gray-zone stream, classified IMPLICIT.
        assertEquals("surefire-junit-platform should be surfaced as IMPLICIT gray zone:\n"
                        + report.raw(),
                "IMPLICIT", report.grayZoneProvenanceOf("surefire-junit-platform"));

        // The project's own test dependency, by contrast, is PROJECT.
        assertEquals("org.junit.jupiter:junit-jupiter should be PROJECT",
                true, manifest.contains("org.junit.jupiter:junit-jupiter-api"));
    }
}
