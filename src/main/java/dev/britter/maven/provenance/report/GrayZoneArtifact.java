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

package dev.britter.maven.provenance.report;

import dev.britter.maven.provenance.Provenance;

/**
 * A §8 gray-zone artifact surfaced in the diagnostics report: its coordinates, how the extension
 * classified it, and why it is a gray area.
 *
 * @param coordinates {@code groupId:artifactId:version:type[:classifier]}
 * @param provenance  the classification the extension assigned (typically IMPLICIT)
 * @param reason      a short human-readable explanation
 */
public record GrayZoneArtifact(String coordinates, Provenance provenance, String reason) {
}
