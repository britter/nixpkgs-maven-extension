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
 * the artifact's {@code .pom}, its full parent-POM lineage, and the checksum sidecars of each file.
 *
 * <p>It only reads files already present in the local repository (sidecars on disk, {@code <parent>}
 * coordinates from the POMs) — it resolves nothing and has no side effects. Every file is attributed
 * to exactly one entry so the manifest stays a clean partition: an artifact's own {@code .pom} is
 * folded into that artifact's entry, while parent POMs (distinct coordinates) become their own
 * {@code pom} entries.
 */
public final class ManifestBuilder {

    /** Checksum sidecar extensions Maven may write next to a repository file. */
    private static final List<String> CHECKSUM_EXTENSIONS =
            List.of(".sha1", ".md5", ".sha256", ".sha512");

    private final Path localRepoBase;

    public ManifestBuilder(Path localRepoBase) {
        this.localRepoBase = localRepoBase;
    }

    /** Builds the sorted, completed manifest entries for the given PROJECT artifacts. */
    public List<ManifestArtifact> build(List<ResolvedArtifact> projectArtifacts) {
        // coordinate key -> (artifact metadata, files). TreeMap keeps a stable iteration order.
        Map<String, Entry> entries = new TreeMap<>();

        for (ResolvedArtifact artifact : projectArtifacts) {
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

            // Walk the parent-POM lineage; each ancestor becomes its own pom entry.
            addParentLineage(pom != null ? pom : artifact.file(), entries);
        }

        List<ManifestArtifact> result = new ArrayList<>();
        for (Entry e : entries.values()) {
            List<String> files = e.files.stream().sorted().toList();
            if (files.isEmpty()) {
                continue;
            }
            result.add(new ManifestArtifact(
                    e.groupId, e.artifactId, e.version, e.type, e.classifier, files));
        }
        result.sort(ManifestArtifact.CANONICAL_ORDER);
        return result;
    }

    /** Adds a parent POM (and recursively its ancestors) as pom-typed entries. */
    private void addParentLineage(File pomFile, Map<String, Entry> entries) {
        ParentCoordinates parent = readParent(pomFile);
        while (parent != null) {
            String key = coordinateKey(parent.groupId, parent.artifactId, parent.version, "pom", null);
            File parentPom = pomFile(artifactDir(parent), parent.artifactId, parent.version);
            if (parentPom == null || !parentPom.isFile()) {
                break;
            }
            if (entries.containsKey(key)) {
                // Already recorded (shared ancestor) — its lineage is covered too.
                return;
            }
            Entry entry = new Entry(parent.groupId, parent.artifactId, parent.version, "pom", null);
            addWithSidecars(entry.files, parentPom);
            entries.put(key, entry);
            parent = readParent(parentPom);
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
    private static ParentCoordinates readParent(File pomFile) {
        if (pomFile == null || !pomFile.isFile()) {
            return null;
        }
        try (InputStream in = Files.newInputStream(pomFile.toPath())) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(in);
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
        } catch (Exception e) {
            // A POM we cannot parse simply contributes no lineage; the build is never affected.
            return null;
        }
        return null;
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
