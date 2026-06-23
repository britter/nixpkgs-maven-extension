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

package dev.britter.maven.provenance.json;

import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON serializer that emits canonical output: two-space indentation,
 * LF line endings, keys written in the exact order supplied (callers are responsible for ordering),
 * and no trailing whitespace. Producing JSON by hand — rather than pulling in Jackson — keeps the
 * extension free of third-party runtime dependencies and gives us full control over byte-for-byte
 * determinism (design §6.2).
 *
 * <p>Supported value types: {@link String}, {@link Number}, {@link Boolean}, {@code null},
 * {@link Map} (object), and {@link List} (array). Maps preserve insertion order, so use an ordered
 * map (e.g. {@link java.util.LinkedHashMap}) for stable output.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    /** Serializes {@code value} to a canonical JSON document terminated with a single LF. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.toString());
        } else if (value instanceof Number n) {
            sb.append(n.toString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(sb, indent + 1);
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(": ");
            writeValue(sb, entry.getValue(), indent + 1);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i + 1 < list.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int level) {
        sb.append("  ".repeat(level));
    }
}
