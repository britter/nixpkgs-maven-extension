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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The surefire test provider in the implicit manifest (design §8, test group I, JUnit5 subset). The
 * provider is selected by the project's test framework but versioned with surefire, so when surefire
 * is unpinned it is IMPLICIT and must land in the implicit manifest, while the project's own test
 * libraries stay PROJECT. Pinning surefire flips the provider to PROJECT.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class SurefireProviderIT {

    private static final String PROVIDER = "org.apache.maven.surefire:surefire-junit-platform";
    private static final String LAUNCHER = "org.junit.platform:junit-platform-launcher";

    private static final Map<String, String> PROVIDER_VERSION = new ConcurrentHashMap<>();
    private static final Map<String, String> JUNIT_VERSION = new ConcurrentHashMap<>();

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public SurefireProviderIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    private Manifest project(File basedir) throws IOException {
        return Manifest.read(basedir.toPath().resolve("target/repo-provenance.json"));
    }

    private Manifest implicit(File basedir) throws IOException {
        return Manifest.read(basedir.toPath().resolve("target/repo-provenance-implicit.json"));
    }

    @Test
    public void providerIsImplicitTestLibsAreProject() throws Exception {
        File basedir = resources.getBasedir("junit5");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "test").assertErrorFreeLog();

        Manifest project = project(basedir);
        Manifest implicit = implicit(basedir);

        // I.30 the provider (resolved by unpinned surefire) is in the implicit manifest.
        assertTrue("provider should be IMPLICIT:\n" + implicit.raw(), implicit.contains(PROVIDER));
        assertFalse("provider must not be PROJECT:\n" + project.raw(), project.contains(PROVIDER));

        // I.31 the project's declared JUnit libraries are PROJECT and not implicit.
        assertTrue("junit-jupiter-api should be PROJECT",
                project.contains("org.junit.jupiter:junit-jupiter-api"));
        assertFalse("junit-jupiter-api must not be in the implicit manifest",
                implicit.contains("org.junit.jupiter:junit-jupiter-api"));

        // I.33 the platform launcher is classified by reachability and appears in exactly one
        // manifest, never both (document which path this Maven takes).
        boolean launcherProject = project.contains(LAUNCHER);
        boolean launcherImplicit = implicit.contains(LAUNCHER);
        assertNotEquals(
                "junit-platform-launcher must be in exactly one manifest (project=" + launcherProject
                        + ", implicit=" + launcherImplicit + ")",
                launcherProject, launcherImplicit);
    }

    @Test
    public void pinningSurefireMovesProviderToProject() throws Exception {
        // I.34 with surefire pinned, the provider follows the (now PROJECT) plugin's realm.
        File basedir = resources.getBasedir("surefire-pinned");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "test").assertErrorFreeLog();

        assertTrue("with surefire pinned the provider should be PROJECT:\n" + project(basedir).raw(),
                project(basedir).contains(PROVIDER));
        assertFalse("provider must not be implicit when surefire is pinned:\n" + implicit(basedir).raw(),
                implicit(basedir).contains(PROVIDER));
    }

    @Test
    public void providerVersionTracksMavenWhileJUnitTracksProject() throws Exception {
        // I.35 across two Maven versions with different bundled surefire, the provider version in the
        // implicit manifest changes while the project's JUnit version is unchanged.
        File basedir = resources.getBasedir("junit5");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "test").assertErrorFreeLog();

        String providerVersion = implicit(basedir).versionOf(PROVIDER);
        String junitVersion = project(basedir).versionOf("org.junit.jupiter:junit-jupiter-api");
        assertNotNull("provider missing from implicit manifest", providerVersion);
        assertNotNull("junit-jupiter-api missing from project manifest", junitVersion);

        String version = maven.getMavenVersion();
        PROVIDER_VERSION.put(version, providerVersion);
        JUNIT_VERSION.put(version, junitVersion);
        for (String peer : PROVIDER_VERSION.keySet()) {
            if (!peer.equals(version)) {
                assertNotEquals("provider version should track the Maven version",
                        providerVersion, PROVIDER_VERSION.get(peer));
                assertEquals("project JUnit version should not depend on Maven",
                        junitVersion, JUNIT_VERSION.get(peer));
            }
        }
    }

    @Test
    public void offlineReplayWithImplicitProviderRunsTests() throws Exception {
        // I.37 split the repo into PROJECT + IMPLICIT, recombine, build offline, and prove the tests
        // actually run (the provider from the implicit set is sufficient to execute them).
        File basedir = resources.getBasedir("junit5");
        Path workspace = Files.createTempDirectory("repo-prov-surefire");
        try {
            Path localRepo = workspace.resolve("full-repo");
            maven.forProject(basedir)
                    .withCliOption("-B")
                    .withCliOption("-Dmaven.repo.local=" + localRepo.toAbsolutePath())
                    .execute("clean", "test")
                    .assertErrorFreeLog();

            // Recombine the PROJECT set and the IMPLICIT set (which together cover the repository,
            // cf. PartitionIT) into one repo and build offline against it.
            Path combined = workspace.resolve("recombined-repo");
            try (Stream<Path> files = Files.walk(localRepo)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path target = combined.resolve(posix(localRepo.relativize(file)));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }

            maven.forProject(basedir)
                    .withCliOption("-B")
                    .withCliOption("-o")
                    .withCliOption("-Dmaven.repo.local=" + combined.toAbsolutePath())
                    .execute("clean", "test")
                    .assertErrorFreeLog()
                    .assertLogText("Tests run: 1");
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static String posix(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(relative.getName(i));
        }
        return sb.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
