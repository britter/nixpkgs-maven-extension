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

package dev.britter.maven.provenance;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the pure version-provenance decision (design §5.1). The model-id strings used here
 * are the ones actually observed from Maven 3.9.x (see {@code VersionProvenanceResolver}).
 */
public class VersionProvenanceResolverTest {

    @Test
    public void projectPomIsProject() {
        assertEquals(Provenance.PROJECT, VersionProvenanceResolver.classify("com.example:app:1.0"));
    }

    @Test
    public void externalParentPomIsProject() {
        // A version pinned by an external parent POM is fixed by the lineage -> PROJECT (§5.1).
        assertEquals(Provenance.PROJECT,
                VersionProvenanceResolver.classify("com.corp:parent:7"));
    }

    @Test
    public void defaultLifecycleBindingIsImplicit() {
        assertEquals(Provenance.IMPLICIT, VersionProvenanceResolver.classify(
                "org.apache.maven:maven-core:3.9.12:default-lifecycle-bindings"));
    }

    @Test
    public void defaultLifecycleBindingIsImplicitRegardlessOfMavenVersion() {
        // Same plugin, different Maven release -> still IMPLICIT (this is the determinism guarantee).
        assertEquals(Provenance.IMPLICIT, VersionProvenanceResolver.classify(
                "org.apache.maven:maven-core:3.9.6:default-lifecycle-bindings"));
    }

    @Test
    public void superPomIsImplicit() {
        assertEquals(Provenance.IMPLICIT, VersionProvenanceResolver.classify(
                "org.apache.maven:maven-model-builder:3.9.12:super-pom"));
    }

    @Test
    public void nullLocationIsImplicit() {
        // No model location at all -> pure lifecycle binding (§5.1).
        assertEquals(Provenance.IMPLICIT,
                VersionProvenanceResolver.classify((org.apache.maven.model.InputLocation) null));
    }

    @Test
    public void nullModelIdIsUnknown() {
        assertEquals(Provenance.UNKNOWN, VersionProvenanceResolver.classify((String) null));
    }
}
