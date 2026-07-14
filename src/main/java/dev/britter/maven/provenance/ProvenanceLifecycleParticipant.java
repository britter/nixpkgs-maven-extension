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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import dev.britter.maven.provenance.manifest.ManifestArtifact;
import dev.britter.maven.provenance.manifest.ManifestBuilder;
import dev.britter.maven.provenance.manifest.ManifestWriter;
import dev.britter.maven.provenance.report.ReportWriter;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.PlexusContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives classification and output at the end of the build.
 *
 * <p>By {@link #afterSessionEnd(MavenSession)} every module has been built, so the project
 * dependency closures and executed plugin realms are fully populated and the manifest can be
 * written once for the whole reactor (design §6.1).
 *
 * <p>All work is guarded so that a failure of this extension can never fail the build: the
 * extension is an ~80% helper and the consumer falls back to the unpartitioned local repository
 * when it cannot do its job (design §11).
 */
@Named
@Singleton
public class ProvenanceLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProvenanceLifecycleParticipant.class);

    private final ResolutionRecorder recorder;
    private final PlexusContainer container;
    private final ProvenanceAnalyzer analyzer = new ProvenanceAnalyzer();
    private final ManifestWriter manifestWriter = new ManifestWriter();
    private final ReportWriter reportWriter = new ReportWriter();

    @Inject
    public ProvenanceLifecycleParticipant(ResolutionRecorder recorder, PlexusContainer container) {
        this.recorder = recorder;
        this.container = container;
    }

    @Override
    public void afterSessionEnd(MavenSession session) {
        try {
            List<PluginEvidence> evidence = analyzer.analyze(session);
            List<ResolvedArtifact> universe = recorder.distinctArtifacts();

            Path localRepoBase = Paths.get(session.getLocalRepository().getBasedir());
            ReactorInspector inspector = new ReactorInspector(container);
            Set<String> projectFiles = inspector
                    .collectProjectFiles(session, evidence, universe,
                            recorder.projectDependencyFiles(), recorder.pluginResolutionFiles());
            List<ResolvedArtifact> projectArtifacts =
                    Classifier.projectArtifacts(universe, projectFiles);

            // The IMPLICIT set is the positive closure of the IMPLICIT (Maven-determined) roots plus
            // everything else Maven resolved that the project does not explain. An artifact reachable
            // from an implicit plugin realm belongs here even when it is also a project dependency —
            // it is Maven-version-specific and needed to build that realm offline (issue #9) — so the
            // complement is unioned with the implicit realm closure rather than used alone.
            Set<String> implicitRealmFiles = inspector
                    .collectImplicitRealmFiles(evidence, recorder.pluginResolutionFiles());
            Set<ResolvedArtifact> projectSet = new HashSet<>(projectArtifacts);
            List<ResolvedArtifact> implicitArtifacts = universe.stream()
                    .filter(a -> !projectSet.contains(a)
                            || (a.file() != null
                            && implicitRealmFiles.contains(a.file().getAbsolutePath())))
                    .toList();

            // Each manifest is self-contained for its own closures, so each build claims into its own
            // set: a primary artifact shared between the two sets (reachable from a project root AND
            // an implicit realm) must appear in both, not be subtracted from the implicit manifest
            // (issue #9). The overlap is harmless for the downstream no-clobber merge (design §6).
            ManifestBuilder builder = new ManifestBuilder(localRepoBase);
            List<ManifestArtifact> projectEntries = builder.build(projectArtifacts, new HashSet<>());
            List<ManifestArtifact> implicitEntries = builder.build(implicitArtifacts, new HashSet<>());

            ReportConfig config = ReportConfig.from(session);
            manifestWriter.write(config.manifestPath(), projectEntries);
            manifestWriter.write(config.implicitManifestPath(), implicitEntries);

            List<String> observedArtifacts = universe.stream()
                    .map(ResolvedArtifact::coordinates)
                    .distinct()
                    .toList();
            reportWriter.write(config.reportPath(), evidence, observedArtifacts);

            LOGGER.info("repo-provenance: wrote project manifest to {} and implicit manifest to {} "
                    + "({} PROJECT / {} IMPLICIT of {} observed artifacts)",
                    config.manifestPath(), config.implicitManifestPath(),
                    projectArtifacts.size(), implicitArtifacts.size(), universe.size());
        } catch (Exception e) {
            // Never fail the build because of this observational extension.
            LOGGER.warn("repo-provenance: failed to produce outputs, build is unaffected", e);
        }
    }
}

