package dev.britter.maven.provenance;

import java.io.File;

/**
 * An artifact that was resolved into the local repository during the build, together with the
 * on-disk file it produced. Immutable so it can be shared freely across the worker threads that
 * record resolutions.
 *
 * @param groupId    artifact groupId
 * @param artifactId artifact artifactId
 * @param version    the resolved version actually present in the local repository
 * @param type       artifact type / extension (e.g. {@code jar}, {@code pom})
 * @param classifier artifact classifier, or {@code null} when absent
 * @param file       the resolved file in the local repository, or {@code null} if unresolved
 */
public record ResolvedArtifact(
        String groupId,
        String artifactId,
        String version,
        String type,
        String classifier,
        File file) {

    /**
     * Canonical coordinate string {@code groupId:artifactId:version:type[:classifier]}, used for
     * diagnostics and stable ordering.
     */
    public String coordinates() {
        String base = groupId + ":" + artifactId + ":" + version + ":" + type;
        return classifier == null ? base : base + ":" + classifier;
    }
}
