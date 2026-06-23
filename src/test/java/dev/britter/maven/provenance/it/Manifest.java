package dev.britter.maven.provenance.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only reader for the canonical manifest. Because the manifest is canonical JSON with a known
 * field order ({@code groupId, artifactId, version, type, ...}), the integration tests read it with
 * targeted regexes rather than a JSON parser dependency.
 */
final class Manifest {

    private static final Pattern ARTIFACT = Pattern.compile(
            "\"groupId\"\\s*:\\s*\"(.*?)\".*?\"artifactId\"\\s*:\\s*\"(.*?)\""
                    + ".*?\"version\"\\s*:\\s*\"(.*?)\"",
            Pattern.DOTALL);

    private final String json;
    private final Set<String> keys;

    private Manifest(String json) {
        this.json = json;
        this.keys = parseKeys(json);
    }

    static Manifest read(Path manifestFile) throws IOException {
        return new Manifest(Files.readString(manifestFile, StandardCharsets.UTF_8));
    }

    private static Set<String> parseKeys(String json) {
        Set<String> result = new LinkedHashSet<>();
        Matcher m = ARTIFACT.matcher(json);
        while (m.find()) {
            result.add(m.group(1) + ":" + m.group(2));
            result.add(m.group(1) + ":" + m.group(2) + ":" + m.group(3));
        }
        return result;
    }

    /** True if an artifact with the given {@code groupId:artifactId} (or {@code g:a:v}) is listed. */
    boolean contains(String key) {
        return keys.contains(key);
    }

    String raw() {
        return json;
    }
}
