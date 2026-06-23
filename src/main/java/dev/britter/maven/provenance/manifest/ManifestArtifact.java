package dev.britter.maven.provenance.manifest;

import java.util.Comparator;
import java.util.List;

/**
 * One entry of the manifest's {@code projectArtifacts}: an artifact identified by full coordinates,
 * with the repository-relative files that belong to it (design §6.2, {@code manifest.schema.json}).
 *
 * @param groupId    artifact groupId
 * @param artifactId artifact artifactId
 * @param version    resolved version present in the local repository
 * @param type       artifact type / packaging (e.g. {@code jar}, {@code pom})
 * @param classifier optional classifier, or {@code null} when absent
 * @param files      sorted, repository-relative POSIX paths belonging to this artifact
 */
public record ManifestArtifact(
        String groupId,
        String artifactId,
        String version,
        String type,
        String classifier,
        List<String> files) {

    /**
     * Canonical ordering for {@code projectArtifacts}: by
     * {@code (groupId, artifactId, version, type, classifier)} (design §6.2). A missing classifier
     * sorts before any present one.
     */
    public static final Comparator<ManifestArtifact> CANONICAL_ORDER = Comparator
            .comparing(ManifestArtifact::groupId)
            .thenComparing(ManifestArtifact::artifactId)
            .thenComparing(ManifestArtifact::version)
            .thenComparing(ManifestArtifact::type)
            .thenComparing(a -> a.classifier() == null ? "" : a.classifier());
}
