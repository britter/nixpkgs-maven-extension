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

import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;

/**
 * Decides the provenance of a plugin/extension <em>version</em> from the effective model's source
 * tracking (design §5.1).
 *
 * <p>The signal is the {@link InputLocation} of the {@code version} element:
 * <ul>
 *   <li>no location at all → the version was supplied by a default lifecycle binding, not by any
 *       POM → {@link Provenance#IMPLICIT};</li>
 *   <li>a location pointing at Maven's built-in super-POM → {@link Provenance#IMPLICIT};</li>
 *   <li>any other location (the project POM or a parent POM it inherits, including external
 *       corporate parents) → {@link Provenance#PROJECT}.</li>
 * </ul>
 *
 * <p>Provenance keys on the <em>source</em>, not the version value: a project that pins a plugin to
 * exactly the version Maven would have bound by default is still {@link Provenance#PROJECT}
 * (design §5.1 corollary).
 *
 * <p>This class is pure and side-effect free so it can be unit-tested in isolation.
 */
public final class VersionProvenanceResolver {

    private VersionProvenanceResolver() {
    }

    /**
     * Classifies a version given the {@link InputLocation} of its {@code version} element (may be
     * {@code null}).
     */
    public static Provenance classify(InputLocation versionLocation) {
        if (versionLocation == null) {
            // Pure lifecycle binding: the version is not present in any model element.
            return Provenance.IMPLICIT;
        }
        InputSource source = versionLocation.getSource();
        if (source == null) {
            return Provenance.UNKNOWN;
        }
        return classify(source.getModelId());
    }

    /**
     * Classifies a version given the model id of the source that defined it.
     *
     * @param modelId the {@link InputSource#getModelId()} of the defining source, or {@code null}
     */
    public static Provenance classify(String modelId) {
        if (modelId == null) {
            return Provenance.UNKNOWN;
        }
        if (isMavenSuppliedSource(modelId)) {
            return Provenance.IMPLICIT;
        }
        return Provenance.PROJECT;
    }

    /**
     * Recognises a model id that does not belong to the project lineage but is synthesised by
     * Maven itself, and is therefore Maven-version-specific.
     *
     * <p>Verified against Maven 3.9.x by the provenance probe (design §9.1): a real project or
     * parent POM reports its plain {@code groupId:artifactId:version} (three segments), whereas
     * Maven attributes versions it supplies to synthetic sources with a trailing qualifier:
     * <ul>
     *   <li>{@code org.apache.maven:maven-core:<ver>:default-lifecycle-bindings} — the default
     *       lifecycle plugin bindings (e.g. an unpinned {@code maven-compiler-plugin});</li>
     *   <li>{@code ...:super-pom} — the built-in super-POM's {@code pluginManagement}.</li>
     * </ul>
     * Matching on these qualifiers is what keeps the classification stable: it distinguishes the
     * <em>source</em> of the version from its value (design §5.1).
     */
    static boolean isMavenSuppliedSource(String modelId) {
        return modelId.endsWith(":default-lifecycle-bindings")
                || modelId.endsWith(":super-pom");
    }
}
