package dev.britter.maven.provenance.it;

import static org.junit.Assert.assertFalse;
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
 * Artifact classification by reachability (design §5.2, test groups A &amp; B): a PROJECT plugin's
 * whole realm and the project dependency closure are PROJECT; an unpinned (IMPLICIT) plugin and its
 * realm-only artifacts are omitted.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class ClassificationIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public ClassificationIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void pinnedPluginRealmIsProjectUnpinnedIsImplicit() throws Exception {
        File basedir = resources.getBasedir("probe");

        maven.forProject(basedir).withCliOption("-B").execute("package").assertErrorFreeLog();

        Path manifestFile = basedir.toPath().resolve("target/repo-provenance.json");
        Manifest manifest = Manifest.read(manifestFile);

        // A.1: pinned plugin and its realm are PROJECT.
        assertTrue("pinned maven-compiler-plugin should be PROJECT:\n" + manifest.raw(),
                manifest.contains("org.apache.maven.plugins:maven-compiler-plugin"));
        // A.2/A.3: unpinned default-bound plugins are not PROJECT.
        assertFalse("unpinned maven-surefire-plugin must not be PROJECT:\n" + manifest.raw(),
                manifest.contains("org.apache.maven.plugins:maven-surefire-plugin"));
        assertFalse("implicit maven-resources-plugin must not be PROJECT:\n" + manifest.raw(),
                manifest.contains("org.apache.maven.plugins:maven-resources-plugin"));
    }

    @Test
    public void projectDependencyAndPluginRealmClosureAreProject() throws Exception {
        File basedir = resources.getBasedir("reactor");

        maven.forProject(basedir).withCliOption("-B").execute("package").assertErrorFreeLog();

        Path manifestFile = basedir.toPath().resolve("target/repo-provenance.json");
        Manifest manifest = Manifest.read(manifestFile);

        // Project dependency closure is PROJECT (B.10).
        assertTrue("project dependency commons-lang3 should be PROJECT:\n" + manifest.raw(),
                manifest.contains("org.apache.commons:commons-lang3"));
        // The PROJECT (pinned) plugin and a transitive of its realm closure are PROJECT (B.8).
        assertTrue("pinned maven-compiler-plugin should be PROJECT:\n" + manifest.raw(),
                manifest.contains("org.apache.maven.plugins:maven-compiler-plugin"));
        assertTrue("compiler plugin realm dependency plexus-compiler-api should be PROJECT:\n"
                        + manifest.raw(),
                manifest.contains("org.codehaus.plexus:plexus-compiler-api"));
        // An IMPLICIT default plugin is omitted.
        assertFalse("implicit maven-resources-plugin must not be PROJECT:\n" + manifest.raw(),
                manifest.contains("org.apache.maven.plugins:maven-resources-plugin"));
    }
}
