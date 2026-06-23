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

/**
 * The provenance classification of a plugin/extension version or an artifact (design §5).
 */
public enum Provenance {

    /** Determined by the project model lineage: stable across Maven versions. */
    PROJECT,

    /** Supplied by Maven (super-POM pluginManagement or a default lifecycle binding). */
    IMPLICIT,

    /**
     * Provenance could not be determined with confidence. Triggers a {@code warning} in the report
     * so the consumer can fall back rather than trust a confident-but-wrong manifest (design §11).
     */
    UNKNOWN
}
