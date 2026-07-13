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

package dev.britter.maven.provenance.manifest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import dev.britter.maven.provenance.LocalRepoPaths;
import dev.britter.maven.provenance.ResolvedArtifact;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Builds canonical manifest entries from the PROJECT artifacts, applying §5.3 completeness so the
 * PROJECT set is self-contained for offline resolution: for every PROJECT artifact it also includes
 * the artifact's {@code .pom}, its full descriptor-read closure — the parent-POM lineage <em>and</em>
 * any import-scope BOM POMs referenced (transitively) by the artifact or its parents — and the
 * checksum sidecars of each file.
 *
 * <p>It only reads files already present in the local repository (sidecars on disk, {@code <parent>}
 * and {@code <dependencyManagement>} import coordinates from the POMs) — it resolves nothing and has
 * no side effects. Every file is attributed to exactly one entry so the manifest stays a clean
 * partition: an artifact's own {@code .pom} is folded into that artifact's entry, while parent POMs
 * and imported BOM POMs (distinct coordinates) become their own {@code pom} entries.
 */
public final class ManifestBuilder {

    /** Checksum sidecar extensions Maven may write next to a repository file. */
    private static final List<String> CHECKSUM_EXTENSIONS =
            List.of(".sha1", ".md5", ".sha256", ".sha512");

    private final Path localRepoBase;

    public ManifestBuilder(Path localRepoBase) {
        this.localRepoBase = localRepoBase;
    }

    /**
     * Builds canonical entries, claiming each file into {@code claimedFiles} so that no file appears
     * in more than one entry. Files already present in {@code claimedFiles} (e.g. claimed by another
     * manifest) are excluded, and entries left with no files are dropped. This keeps a single
     * manifest internally exactly-once, and makes two manifests a disjoint partition when the second
     * build is seeded with the first manifest's files.
     */
    public List<ManifestArtifact> build(List<ResolvedArtifact> artifacts, Set<String> claimedFiles) {
        // coordinate key -> (artifact metadata, files). TreeMap keeps a stable iteration order.
        Map<String, Entry> entries = new TreeMap<>();

        for (ResolvedArtifact artifact : artifacts) {
            if (artifact.file() == null) {
                continue;
            }
            String key = coordinateKey(
                    artifact.groupId(), artifact.artifactId(), artifact.version(),
                    artifact.type(), artifact.classifier());
            Entry entry = entries.computeIfAbsent(key, k -> new Entry(
                    artifact.groupId(), artifact.artifactId(), artifact.version(),
                    artifact.type(), artifact.classifier()));

            // The artifact's primary file plus its checksum sidecars.
            addWithSidecars(entry.files, artifact.file());

            // The artifact's own POM (and its sidecars) belong to this entry (design §5.3), unless
            // the artifact already IS the POM.
            File pom = pomFile(artifact.file().getParentFile(), artifact.artifactId(),
                    artifact.version());
            if (!"pom".equals(artifact.type()) && pom != null) {
                addWithSidecars(entry.files, pom);
            }

            // Complete the descriptor-read closure: parent lineage + imported BOM POMs.
            addDescriptorClosure(pom != null ? pom : artifact.file(), entries);
        }

        List<ManifestArtifact> candidates = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.files.isEmpty()) {
                continue;
            }
            candidates.add(new ManifestArtifact(
                    e.groupId, e.artifactId, e.version, e.type, e.classifier,
                    e.files.stream().sorted().toList()));
        }
        candidates.sort(ManifestArtifact.CANONICAL_ORDER);

        // Claim files in canonical order: the first entry to reference a file keeps it; later
        // entries — and files already claimed by a previously built manifest — drop it.
        List<ManifestArtifact> result = new ArrayList<>();
        for (ManifestArtifact candidate : candidates) {
            List<String> kept = new ArrayList<>();
            for (String file : candidate.files()) {
                if (claimedFiles.add(file)) {
                    kept.add(file);
                }
            }
            if (!kept.isEmpty()) {
                result.add(new ManifestArtifact(candidate.groupId(), candidate.artifactId(),
                        candidate.version(), candidate.type(), candidate.classifier(), kept));
            }
        }
        return result;
    }

    /**
     * Completes the descriptor-read closure of {@code pomFile}: its full {@code <parent>} lineage
     * and any import-scope BOM POMs referenced (transitively) by it or its parents. Each ancestor
     * and each imported BOM becomes its own pom-typed entry. Reads only files already on disk.
     */
    private void addDescriptorClosure(File pomFile, Map<String, Entry> entries) {
        // The leaf POM plus its on-disk parent lineage, each parsed once; each ancestor becomes a
        // pom entry.
        Document leaf = parse(pomFile);
        if (leaf == null) {
            return;
        }
        List<Document> chain = new ArrayList<>();
        chain.add(leaf);
        ParentCoordinates parent = readParent(leaf);
        while (parent != null) {
            File parentPom = pomFile(artifactDir(parent), parent.artifactId, parent.version);
            if (parentPom == null || !parentPom.isFile()) {
                break;
            }
            String key = coordinateKey(parent.groupId, parent.artifactId, parent.version, "pom", null);
            if (entries.containsKey(key)) {
                // Shared ancestor already recorded — its lineage and imports are covered too.
                break;
            }
            Entry entry = new Entry(parent.groupId, parent.artifactId, parent.version, "pom", null);
            addWithSidecars(entry.files, parentPom);
            entries.put(key, entry);
            Document parentDoc = parse(parentPom);
            if (parentDoc == null) {
                break;
            }
            chain.add(parentDoc);
            parent = readParent(parentDoc);
        }

        // Import-scope BOM versions are resolved against the properties visible to the leaf's
        // effective model: every property declared along the chain, child overriding parent.
        Map<String, String> properties = new HashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            properties.putAll(readProperties(chain.get(i)));
        }

        // Each import-scope BOM declared anywhere in the chain becomes its own pom entry, and its
        // own descriptor closure (parents + nested imports) is captured recursively.
        for (Document pom : chain) {
            for (ParentCoordinates bom : readImportBoms(pom, properties)) {
                File bomPom = pomFile(artifactDir(bom), bom.artifactId, bom.version);
                if (bomPom == null || !bomPom.isFile()) {
                    continue;
                }
                String key = coordinateKey(bom.groupId, bom.artifactId, bom.version, "pom", null);
                if (entries.containsKey(key)) {
                    continue;
                }
                Entry entry = new Entry(bom.groupId, bom.artifactId, bom.version, "pom", null);
                addWithSidecars(entry.files, bomPom);
                entries.put(key, entry);
                addDescriptorClosure(bomPom, entries);
            }
        }
    }

    private void addWithSidecars(Set<String> files, File file) {
        addFile(files, file);
        for (String extension : CHECKSUM_EXTENSIONS) {
            addFile(files, new File(file.getParentFile(), file.getName() + extension));
        }
    }

    private void addFile(Set<String> files, File file) {
        if (file != null && file.isFile()) {
            String relative = LocalRepoPaths.relativize(localRepoBase, file);
            if (relative != null) {
                files.add(relative);
            }
        }
    }

    private File artifactDir(ParentCoordinates parent) {
        Path dir = localRepoBase;
        for (String segment : parent.groupId.split("\\.")) {
            dir = dir.resolve(segment);
        }
        return dir.resolve(parent.artifactId).resolve(parent.version).toFile();
    }

    private static File pomFile(File dir, String artifactId, String version) {
        if (dir == null) {
            return null;
        }
        return new File(dir, artifactId + "-" + version + ".pom");
    }

    /** Reads the {@code <parent>} coordinates from a POM, or {@code null} if there is none. */
    private static ParentCoordinates readParent(Document document) {
        NodeList parents = document.getElementsByTagName("parent");
        for (int i = 0; i < parents.getLength(); i++) {
            Node node = parents.item(i);
            // Only the project-level <parent> (a direct child of <project>) is the lineage.
            if (node.getParentNode() != null
                    && "project".equals(node.getParentNode().getNodeName())
                    && node instanceof Element element) {
                String groupId = childText(element, "groupId");
                String artifactId = childText(element, "artifactId");
                String version = childText(element, "version");
                if (groupId != null && artifactId != null && version != null) {
                    return new ParentCoordinates(groupId, artifactId, version);
                }
            }
        }
        return null;
    }

    /** Reads {@code <project>/<properties>} as a name-to-value map (empty if absent). */
    private static Map<String, String> readProperties(Document document) {
        Map<String, String> properties = new HashMap<>();
        NodeList list = document.getElementsByTagName("properties");
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node.getParentNode() != null
                    && "project".equals(node.getParentNode().getNodeName())
                    && node instanceof Element element) {
                NodeList children = element.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    if (children.item(j) instanceof Element property) {
                        properties.put(property.getNodeName(), property.getTextContent().trim());
                    }
                }
            }
        }
        return properties;
    }

    /**
     * Reads the import-scope BOMs — {@code <dependencyManagement>} dependencies with
     * {@code <scope>import</scope>} — declared directly in {@code pomFile}, interpolating any
     * {@code ${property}} in the version from {@code properties}.
     *
     * <p>ponytail: interpolation covers literal and {@code <properties>}-defined versions (the real
     * cases, e.g. plexus:18's {@code ${junit5Version}}). Versions that stay unresolved after one
     * pass — {@code ${project.*}} self-imports, computed or plugin-injected values — are skipped
     * rather than guessed. This is strictly additive (no BOM was captured before), so skipping one
     * is never worse than today; upgrade to full model interpolation only if a real BOM needs it.
     */
    private static List<ParentCoordinates> readImportBoms(
            Document document, Map<String, String> properties) {
        List<ParentCoordinates> boms = new ArrayList<>();
        NodeList dependencies = document.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            if (!(dependencies.item(i) instanceof Element dependency)) {
                continue;
            }
            // Only <dependencyManagement> imports (the dependency's grandparent is that element).
            Node deps = dependency.getParentNode();
            Node mgmt = deps == null ? null : deps.getParentNode();
            if (mgmt == null || !"dependencyManagement".equals(mgmt.getNodeName())) {
                continue;
            }
            if (!"import".equals(childText(dependency, "scope"))) {
                continue;
            }
            String groupId = childText(dependency, "groupId");
            String artifactId = childText(dependency, "artifactId");
            String version = interpolate(childText(dependency, "version"), properties);
            if (groupId != null && artifactId != null
                    && version != null && !version.contains("${")) {
                boms.add(new ParentCoordinates(groupId, artifactId, version));
            }
        }
        return boms;
    }

    /** Replaces {@code ${name}} tokens using {@code properties}; leaves unknown tokens intact. */
    private static String interpolate(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) {
                result.append(value, i, value.length());
                break;
            }
            result.append(value, i, start);
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                result.append(value.substring(start));
                break;
            }
            String replacement = properties.get(value.substring(start + 2, end));
            // Leave an unknown token intact so the caller can detect and skip the import.
            result.append(replacement != null ? replacement : value.substring(start, end + 1));
            i = end + 1;
        }
        return result.toString();
    }

    /** Parses a POM into a DOM, or {@code null} if it is missing or unparseable. */
    private static Document parse(File pomFile) {
        if (pomFile == null || !pomFile.isFile()) {
            return null;
        }
        try (InputStream in = Files.newInputStream(pomFile.toPath())) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(in);
        } catch (Exception e) {
            // A POM we cannot parse simply contributes no closure; the build is never affected.
            return null;
        }
    }

    private static String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getParentNode() == parent) {
                return nodes.item(i).getTextContent().trim();
            }
        }
        return null;
    }

    private static String coordinateKey(
            String groupId, String artifactId, String version, String type, String classifier) {
        return groupId + ":" + artifactId + ":" + version + ":" + type + ":"
                + (classifier == null ? "" : classifier);
    }

    /** Mutable accumulator for one manifest entry while files are gathered. */
    private static final class Entry {
        final String groupId;
        final String artifactId;
        final String version;
        final String type;
        final String classifier;
        final Set<String> files = new LinkedHashSet<>();

        Entry(String groupId, String artifactId, String version, String type, String classifier) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.type = type;
            this.classifier = classifier;
        }
    }

    private record ParentCoordinates(String groupId, String artifactId, String version) {
    }
}
