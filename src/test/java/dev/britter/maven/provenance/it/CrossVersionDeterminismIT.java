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

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.Assert.assertArrayEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
 * The central invariant (design §2, §9.2): the manifest must be <em>byte-identical</em> across
 * Maven versions, given the same project sources.
 *
 * <p>The runner executes this method once per Maven version. Each run copies its manifest to a
 * shared, version-keyed file under {@code target/}; whichever run executes second finds the other
 * version's manifest already present and asserts the two are byte-for-byte equal.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class CrossVersionDeterminismIT {

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public CrossVersionDeterminismIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void manifestIsByteIdenticalAcrossMavenVersions() throws Exception {
        File basedir = resources.getBasedir("reactor");
        maven.forProject(basedir).withCliOption("-B").execute("clean", "package").assertErrorFreeLog();
        Path produced = basedir.toPath().resolve("target/repo-provenance.json");

        Path shared = Paths.get("target/cross-version");
        Files.createDirectories(shared);
        Path mine = shared.resolve(maven.getMavenVersion() + ".json");
        Files.copy(produced, mine, REPLACE_EXISTING);

        byte[] myManifest = Files.readAllBytes(mine);
        try (Stream<Path> others = Files.list(shared)) {
            List<Path> peers = others.filter(p -> !p.equals(mine)).toList();
            for (Path peer : peers) {
                assertArrayEquals(
                        "manifest differs between Maven " + mine.getFileName() + " and "
                                + peer.getFileName(),
                        myManifest, Files.readAllBytes(peer));
            }
        }
    }
}
