package dev.britter.maven.provenance.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    String raw() {
        return json;
    }
}
