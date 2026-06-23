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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.britter.maven.provenance.PluginEvidence;
import dev.britter.maven.provenance.json.JsonWriter;

/**
 * Writes the Maven-specific diagnostics report (design §6.3): warnings and per-plugin/extension
 * provenance evidence. Unlike the manifest, this file is explicitly <em>not</em> part of the
 * deterministic contract and may differ across Maven versions.
 */
public final class ReportWriter {

    /** Builds and writes the report document to {@code reportPath}. */
    public void write(
            Path reportPath,
            List<PluginEvidence> evidence,
            List<String> observedArtifacts,
            List<GrayZoneArtifact> grayZone,
            List<String> warnings)
            throws IOException {
        String json = JsonWriter.write(buildDocument(evidence, observedArtifacts, grayZone, warnings));
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, json, StandardCharsets.UTF_8);
    }

    private Map<String, Object> buildDocument(
            List<PluginEvidence> evidence,
            List<String> observedArtifacts,
            List<GrayZoneArtifact> grayZone,
            List<String> warnings) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("warnings", new ArrayList<>(warnings));
        List<Object> plugins = new ArrayList<>();
        for (PluginEvidence e : evidence) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("project", e.projectId());
            entry.put("kind", e.kind());
            entry.put("key", e.key());
            entry.put("version", e.version());
            entry.put("provenance", e.provenance().name());
            entry.put("sourceModelId", e.sourceModelId());
            plugins.add(entry);
        }
        doc.put("pluginProvenance", plugins);
        // Dynamically resolved plugin dependencies (e.g. the surefire provider), surfaced distinctly
        // so consumers can decide how to supply them (design §8).
        List<Object> gray = new ArrayList<>();
        for (GrayZoneArtifact g : grayZone) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("coordinates", g.coordinates());
            entry.put("provenance", g.provenance().name());
            entry.put("reason", g.reason());
            gray.add(entry);
        }
        doc.put("grayZoneArtifacts", gray);
        // Maven-specific diagnostics: the full set of artifacts observed hitting the local
        // repository, sorted so it is independent of (parallel) resolution order.
        doc.put("observedArtifacts", new ArrayList<>(observedArtifacts));
        return doc;
    }
}
