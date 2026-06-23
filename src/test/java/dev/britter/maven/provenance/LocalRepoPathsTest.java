package dev.britter.maven.provenance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Unit tests for repository-relative POSIX path computation (design §6.2). */
public class LocalRepoPathsTest {

    private final Path repo = Paths.get("/home/user/.m2/repository");

    @Test
    public void relativizesToPosixPathWithoutLeadingSlash() {
        File file = new File("/home/user/.m2/repository/org/example/lib/1.2.3/lib-1.2.3.jar");
        assertEquals("org/example/lib/1.2.3/lib-1.2.3.jar",
                LocalRepoPaths.relativize(repo, file));
    }

    @Test
    public void returnsNullForFileOutsideRepository() {
        assertNull(LocalRepoPaths.relativize(repo, new File("/tmp/elsewhere/lib.jar")));
    }

    @Test
    public void returnsNullForNullFile() {
        assertNull(LocalRepoPaths.relativize(repo, null));
    }
}
