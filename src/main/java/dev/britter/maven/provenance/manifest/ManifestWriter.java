package dev.britter.maven.provenance.manifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.britter.maven.provenance.ResolvedArtifact;
import dev.britter.maven.provenance.json.JsonWriter;

/**
 * Builds and writes the manifest: the canonical, Maven-version-independent description of the
 * PROJECT-determined set (design §6, {@code manifest.schema.json}). The document is deterministic —
 * {@code projectArtifacts} sorted by coordinates, each entry's {@code files} sorted, UTF-8/LF, no
 * timestamps — so two builds of the same sources produce byte-identical output.
 *
 * <p>{@link ManifestBuilder} does the §5.3 completeness work (POMs, parent lineage, checksums);
 * this class owns the canonical serialization.
 */
public final class ManifestWriter {

    private static final String SCHEMA_VERSION = "1";

    /** Groups and completes PROJECT artifacts into canonical manifest entries. */
    public List<ManifestArtifact> build(List<ResolvedArtifact> projectArtifacts, Path localRepoBase) {
        return new ManifestBuilder(localRepoBase).build(projectArtifacts);
    }

    /** Serializes manifest entries to canonical JSON. */
    public String toJson(List<ManifestArtifact> artifacts) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", SCHEMA_VERSION);
        List<Object> list = new ArrayList<>();
        for (ManifestArtifact a : artifacts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("groupId", a.groupId());
            entry.put("artifactId", a.artifactId());
            entry.put("version", a.version());
            entry.put("type", a.type());
            if (a.classifier() != null) {
                entry.put("classifier", a.classifier());
            }
            entry.put("files", new ArrayList<>(a.files()));
            list.add(entry);
        }
        doc.put("projectArtifacts", list);
        return JsonWriter.write(doc);
    }

    /** Builds and writes the manifest to {@code manifestPath}. */
    public void write(Path manifestPath, List<ResolvedArtifact> projectArtifacts, Path localRepoBase)
            throws IOException {
        String json = toJson(build(projectArtifacts, localRepoBase));
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, json, StandardCharsets.UTF_8);
    }
}
