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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The central invariant (design §2, §9.2): the project manifest must be <em>byte-identical</em>
 * across Maven versions, given the same project sources.
 *
 * <p>The runner executes this method once per Maven version in the same JVM. Each run records its
 * manifest bytes in a static map keyed by version; whichever run executes second finds the other
 * version's bytes and asserts equality. Using in-memory state (not files on disk) keeps the check
 * immune to stale outputs from a previous, non-cleaned build.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class CrossVersionDeterminismIT {

    private static final Map<String, byte[]> PROJECT_MANIFESTS = new ConcurrentHashMap<>();

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public CrossVersionDeterminismIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void projectManifestIsByteIdenticalAcrossMavenVersions() throws Exception {
        File basedir = resources.getBasedir("reactor");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();

        byte[] mine = Files.readAllBytes(basedir.toPath().resolve("target/repo-provenance.json"));
        String version = maven.getMavenVersion();
        PROJECT_MANIFESTS.put(version, mine);

        for (Map.Entry<String, byte[]> peer : PROJECT_MANIFESTS.entrySet()) {
            if (!peer.getKey().equals(version)) {
                assertArrayEquals(
                        "project manifest differs between Maven " + version + " and " + peer.getKey(),
                        mine, peer.getValue());
            }
        }
    }
}
