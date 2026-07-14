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

package dev.britter.maven.provenance;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;

/**
 * Gathers the PROJECT root files of the whole reactor (design §5.2/§7.1): the project dependency
 * closures, the realms of PROJECT plugins, and project-supplied plugin dependencies. The union of
 * these absolute file paths is what {@link Classifier} treats as reachable-from-the-project.
 *
 * <p>Realm membership is read from the live {@code ClassWorld} rather than by re-resolving plugins,
 * so this introduces no new resolution and no side effects — it only observes realms Maven already
 * built. Plugin realms are named {@code plugin>groupId:artifactId:version} and build-extension
 * realms {@code extension>groupId:artifactId:version} (verified against Maven 3.9.x).
 */
public final class ReactorInspector {

    private static final List<String> REALM_PREFIXES = List.of("plugin>", "extension>");

    private final PlexusContainer container;

    public ReactorInspector(PlexusContainer container) {
        this.container = container;
    }

    /**
     * Computes the set of absolute file paths reachable from any PROJECT root across all reactor
     * modules.
     *
     * @param session                the build session
     * @param evidence                the per-plugin provenance evidence (used to pick PROJECT plugins)
     * @param universe                every artifact observed hitting the local repository (used to
     *                                map project-declared plugin dependency coordinates back to files)
     * @param projectDependencyFiles  absolute paths of files observed being resolved as project
     *                                dependencies (all scopes), from the resolution events
     * @param pluginResolutionFiles   absolute paths of files observed being resolved under each
     *                                plugin, keyed by plugin GAV, from the resolution events
     */
    public Set<String> collectProjectFiles(
            MavenSession session, List<PluginEvidence> evidence, List<ResolvedArtifact> universe,
            Set<String> projectDependencyFiles, Map<String, Set<String>> pluginResolutionFiles) {
        Set<String> projectFiles = new HashSet<>();

        // 1. Project dependency closures across every module. The primary source is the resolution
        //    events attributed to project-dependency resolution, which cover every scope (test
        //    included, unlike MavenProject.getArtifacts() whose scope filter is reset to runtime by
        //    a later package-phase mojo — issue #1). getArtifacts() is kept as a complementary
        //    signal; the union only ever adds real project deps.
        // ponytail: keep getArtifacts() too — union only adds real project deps, covers null-trace edge.
        projectFiles.addAll(projectDependencyFiles);
        for (MavenProject project : session.getAllProjects()) {
            for (Artifact artifact : project.getArtifacts()) {
                addFile(projectFiles, artifact.getFile());
            }
        }

        // 2. Full resolution closures of PROJECT plugins/extensions. The realm alone omits
        //    transitive artifacts Maven reads while resolving the plugin but mediates out of the
        //    final realm; the offline build still needs those to resolve the plugin (issue #3). So
        //    we take the union of (a) the realm URLs — covering plugin> and extension> realms — and
        //    (b) every file observed resolving under the plugin's GAV (the mediated-out closure).
        Set<String> projectPluginGavs = evidence.stream()
                .filter(e -> e.provenance() == Provenance.PROJECT)
                .map(e -> e.key() + ":" + e.version())
                .collect(Collectors.toSet());
        projectFiles.addAll(realmClosureFiles(projectPluginGavs, pluginResolutionFiles));

        // Dynamically resolved test providers (the canonical §8 gray zone) are versioned with — and
        // selected by — the surefire/failsafe plugin, but live in no realm or trace we can follow.
        // They therefore follow that plugin's provenance: PROJECT iff the test plugin is PROJECT
        // (so pinning surefire moves the provider into the project set, design group I). Keying on a
        // version-pinned plugin keeps the project manifest deterministic.
        boolean testPluginIsProject = evidence.stream().anyMatch(e ->
                e.provenance() == Provenance.PROJECT
                        && (e.key().equals("org.apache.maven.plugins:maven-surefire-plugin")
                        || e.key().equals("org.apache.maven.plugins:maven-failsafe-plugin")));
        if (testPluginIsProject) {
            for (ResolvedArtifact artifact : universe) {
                if (GrayZoneDetector.isDynamicTestProvider(artifact.groupId(), artifact.artifactId())) {
                    addFile(projectFiles, artifact.file());
                }
            }
        }

        // 3. Project-supplied plugin dependencies are project-controlled even when the plugin is
        //    IMPLICIT (§7.3); include their files by matching declared coordinates in the universe.
        Set<String> declaredPluginDepGavs = new HashSet<>();
        for (MavenProject project : session.getAllProjects()) {
            for (Plugin plugin : project.getBuildPlugins()) {
                for (Dependency dependency : plugin.getDependencies()) {
                    declaredPluginDepGavs.add(dependency.getGroupId() + ":"
                            + dependency.getArtifactId() + ":" + dependency.getVersion());
                }
            }
        }
        if (!declaredPluginDepGavs.isEmpty()) {
            for (ResolvedArtifact artifact : universe) {
                String gav = artifact.groupId() + ":" + artifact.artifactId() + ":"
                        + artifact.version();
                if (declaredPluginDepGavs.contains(gav)) {
                    addFile(projectFiles, artifact.file());
                }
            }
        }

        return projectFiles;
    }

    /**
     * Absolute file paths reachable from the IMPLICIT (Maven-determined) plugin/extension realms and
     * their resolution closures. An artifact in one of these realms belongs in the implicit manifest
     * even when it is also reachable from a project root (issue #9): it is genuinely
     * Maven-version-specific and required to build that realm offline. Symmetric to step 2 of
     * {@link #collectProjectFiles} but for IMPLICIT-provenance plugins.
     */
    public Set<String> collectImplicitRealmFiles(
            List<PluginEvidence> evidence, Map<String, Set<String>> pluginResolutionFiles) {
        Set<String> implicitPluginGavs = evidence.stream()
                .filter(e -> e.provenance() == Provenance.IMPLICIT)
                .map(e -> e.key() + ":" + e.version())
                .collect(Collectors.toSet());
        return realmClosureFiles(implicitPluginGavs, pluginResolutionFiles);
    }

    /**
     * The union of (a) every file observed resolving under each given plugin GAV (the mediated-out
     * resolution closure) and (b) the {@code plugin>}/{@code extension>} realm URLs of those GAVs.
     */
    private Set<String> realmClosureFiles(
            Set<String> pluginGavs, Map<String, Set<String>> pluginResolutionFiles) {
        Set<String> files = new HashSet<>();
        for (String gav : pluginGavs) {
            Set<String> closure = pluginResolutionFiles.get(gav);
            if (closure != null) {
                files.addAll(closure);
            }
        }
        for (ClassRealm realm : realms()) {
            String id = realm.getId();
            if (id == null) {
                continue;
            }
            for (String prefix : REALM_PREFIXES) {
                if (id.startsWith(prefix) && pluginGavs.contains(id.substring(prefix.length()))) {
                    for (URL url : realm.getURLs()) {
                        addFile(files, toFile(url));
                    }
                }
            }
        }
        return files;
    }

    @SuppressWarnings("unchecked")
    private List<ClassRealm> realms() {
        return List.copyOf(container.getContainerRealm().getWorld().getRealms());
    }

    private static void addFile(Set<String> set, File file) {
        if (file != null) {
            set.add(file.getAbsolutePath());
        }
    }

    private static File toFile(URL url) {
        if (!"file".equals(url.getProtocol())) {
            return null;
        }
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            return new File(url.getPath());
        }
    }
}
