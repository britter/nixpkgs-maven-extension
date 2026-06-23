package dev.britter.maven.provenance.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Recorder thread-safety: a parallel reactor build ({@code -T}) must observe exactly the same set
 * of artifacts as a serial build. {@link org.eclipse.aether.RepositoryEvent}s fire on multiple
 * worker threads under {@code -T}, so this exercises the {@code ResolutionRecorder}'s concurrent
 * collections against lost or torn writes.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class ParallelDeterminismIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public ParallelDeterminismIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void parallelObservesSameArtifactsAsSerial() throws Exception {
        File basedir = resources.getBasedir("reactor");
        Path report = basedir.toPath().resolve("target/repo-provenance-report.json");

        maven.forProject(basedir)
                .withCliOption("-B")
                .execute("clean", "package")
                .assertErrorFreeLog();
        List<String> serial = ProvenanceReport.read(report).observedArtifacts();

        maven.forProject(basedir)
                .withCliOption("-B")
                .withCliOption("-T2C")
                .execute("clean", "package")
                .assertErrorFreeLog();
        List<String> parallel = ProvenanceReport.read(report).observedArtifacts();

        assertFalse("expected the build to resolve at least some artifacts", serial.isEmpty());
        assertEquals("parallel build observed a different artifact set than the serial build",
                serial, parallel);
    }
}
