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
