package dev.britter.maven.provenance.manifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.britter.maven.provenance.LocalRepoPaths;
import dev.britter.maven.provenance.ResolvedArtifact;
import dev.britter.maven.provenance.json.JsonWriter;

/**
 * Builds and writes the manifest: the canonical, Maven-version-independent description of the
 * PROJECT-determined set (design §6, {@code manifest.schema.json}). The document is deterministic —
 * {@code projectArtifacts} sorted by coordinates, each entry's {@code files} sorted, UTF-8/LF, no
 * timestamps — so two builds of the same sources produce byte-identical output.
 */
public final class ManifestWriter {

    private static final String SCHEMA_VERSION = "1";

    /** Groups PROJECT artifacts into canonical manifest entries. */
    public List<ManifestArtifact> build(List<ResolvedArtifact> projectArtifacts, Path localRepoBase) {
        // Group by full coordinates so an artifact's files (primary, .pom, checksums) collapse into
        // a single entry.
        Map<String, ManifestArtifact> byCoordinates = new TreeMap<>();
        Map<String, List<String>> filesByCoordinates = new LinkedHashMap<>();

        for (ResolvedArtifact artifact : projectArtifacts) {
            String relative = LocalRepoPaths.relativize(localRepoBase, artifact.file());
            if (relative == null) {
                continue;
            }
            String key = coordinateKey(artifact);
            filesByCoordinates.computeIfAbsent(key, k -> new ArrayList<>()).add(relative);
            byCoordinates.putIfAbsent(key, new ManifestArtifact(
                    artifact.groupId(),
                    artifact.artifactId(),
                    artifact.version(),
                    artifact.type(),
                    artifact.classifier(),
                    List.of()));
        }

        List<ManifestArtifact> entries = new ArrayList<>();
        for (Map.Entry<String, ManifestArtifact> entry : byCoordinates.entrySet()) {
            ManifestArtifact a = entry.getValue();
            List<String> files = filesByCoordinates.get(entry.getKey()).stream()
                    .distinct()
                    .sorted()
                    .toList();
            entries.add(new ManifestArtifact(
                    a.groupId(), a.artifactId(), a.version(), a.type(), a.classifier(), files));
        }
        entries.sort(ManifestArtifact.CANONICAL_ORDER);
        return entries;
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

    private static String coordinateKey(ResolvedArtifact a) {
        return a.groupId() + ":" + a.artifactId() + ":" + a.version() + ":" + a.type() + ":"
                + (a.classifier() == null ? "" : a.classifier());
    }
}
