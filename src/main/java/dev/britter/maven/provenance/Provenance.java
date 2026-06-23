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
