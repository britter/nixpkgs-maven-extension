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

package dev.britter.maven.provenance.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only reader for the diagnostics report. Rather than pull in a JSON parser, it extracts the
 * provenance recorded for a given plugin/extension key by locating its block in the canonical JSON
 * (the format we control). Used by the integration tests to assert on the evidence stream.
 */
final class ProvenanceReport {

    private final String json;

    private ProvenanceReport(String json) {
        this.json = json;
    }

    static ProvenanceReport read(Path reportFile) throws IOException {
        return new ProvenanceReport(Files.readString(reportFile, StandardCharsets.UTF_8));
    }

    boolean exists(Path reportFile) {
        return Files.exists(reportFile);
    }

    /**
     * Returns the provenance recorded for the given {@code groupId:artifactId} key, or {@code null}
     * if the key is not present in the report.
     */
    String provenanceOf(String key) {
        // Match the "provenance" field that follows the matching "key" entry in the same object.
        Pattern pattern = Pattern.compile(
                "\"key\"\\s*:\\s*\"" + Pattern.quote(key) + "\".*?\"provenance\"\\s*:\\s*\"(\\w+)\"",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Returns the sorted contents of the {@code observedArtifacts} array (the coordinate strings of
     * every artifact the build resolved into the local repository).
     */
    List<String> observedArtifacts() {
        Matcher array = Pattern.compile("\"observedArtifacts\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL)
                .matcher(json);
        if (!array.find()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Matcher items = Pattern.compile("\"(.*?)\"").matcher(array.group(1));
        while (items.find()) {
            result.add(items.group(1));
        }
        result.sort(String::compareTo);
        return result;
    }

    /**
     * Returns the provenance recorded in {@code grayZoneArtifacts} for the first entry whose
     * coordinates contain {@code coordinatesSubstring}, or {@code null} if none.
     */
    String grayZoneProvenanceOf(String coordinatesSubstring) {
        Pattern pattern = Pattern.compile(
                "\"coordinates\"\\s*:\\s*\"[^\"]*" + Pattern.quote(coordinatesSubstring)
                        + "[^\"]*\".*?\"provenance\"\\s*:\\s*\"(\\w+)\"",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    String raw() {
        return json;
    }
}
