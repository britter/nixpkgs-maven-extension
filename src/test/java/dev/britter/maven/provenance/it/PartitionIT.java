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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Partition correctness (design §9.3, group D): every PROJECT file is listed exactly once and
 * actually exists in the local repository, and volatile resolution-state files are never PROJECT
 * (design §5.3, group D.15). Uses an isolated local repository so the manifest can be checked
 * against the exact set of files the build produced.
 */
@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.9.6", "3.9.12"})
public class PartitionIT {

    private static final List<String> VOLATILE_MARKERS = List.of(
            "maven-metadata", "_remote.repositories", ".lastUpdated", "resolver-status.properties");

    @Rule
    public final TestResources resources = new TestResources("src/test/resources/it", "target/it-work");

    private final MavenRuntime maven;

    public PartitionIT(MavenRuntimeBuilder builder) throws Exception {
        File extensionJar = new File(System.getProperty("extension.jar"));
        this.maven = builder.forkedBuilder().withExtension(extensionJar).build();
    }

    @Test
    public void projectFilesAreUniqueExistAndExcludeVolatileState() throws Exception {
        File basedir = resources.getBasedir("reactor");
        Path localRepo = basedir.toPath().resolve("target/it-local-repo");

        maven.forProject(basedir)
                .withCliOption("-B")
                .withCliOption("-Dmaven.repo.local=" + localRepo.toAbsolutePath())
                .execute("clean", "package")
                .assertErrorFreeLog();

        Path manifest = basedir.toPath().resolve("target/repo-provenance.json");
        List<String> files = Manifest.read(manifest).allFiles();
        assertFalse("expected a non-empty PROJECT set", files.isEmpty());

        // Exactly once: no PROJECT file is listed twice across the whole manifest.
        Set<String> unique = new HashSet<>(files);
        assertEquals("a PROJECT file is listed more than once", unique.size(), files.size());

        for (String relative : files) {
            // Each PROJECT file actually exists in the local repository (self-contained, §9.3).
            assertTrue("manifest lists a file missing from the local repo: " + relative,
                    Files.exists(localRepo.resolve(relative)));
            // Volatile resolution-state files must never be PROJECT (§5.3, D.15).
            for (String marker : VOLATILE_MARKERS) {
                assertFalse("volatile file must not be PROJECT: " + relative,
                        relative.contains(marker));
            }
        }
    }
}
