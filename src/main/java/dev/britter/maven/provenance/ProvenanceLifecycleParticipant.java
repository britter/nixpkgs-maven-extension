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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import dev.britter.maven.provenance.manifest.ManifestArtifact;
import dev.britter.maven.provenance.manifest.ManifestBuilder;
import dev.britter.maven.provenance.manifest.ManifestWriter;
import dev.britter.maven.provenance.report.GrayZoneArtifact;
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
            List<String> warnings = new ArrayList<>();
            List<PluginEvidence> evidence = analyzer.analyze(session);
            List<ResolvedArtifact> universe = recorder.distinctArtifacts();

            Path localRepoBase = Paths.get(session.getLocalRepository().getBasedir());
            Set<String> projectFiles = new ReactorInspector(container)
                    .collectProjectFiles(session, evidence, universe);
            List<ResolvedArtifact> projectArtifacts =
                    Classifier.projectArtifacts(universe, projectFiles);

            // The IMPLICIT set is the rest of the observed universe. Because we observe what Maven
            // actually resolved, this naturally includes the deep plugin-realm closure (design §6.3).
            Set<ResolvedArtifact> projectSet = new HashSet<>(projectArtifacts);
            List<ResolvedArtifact> implicitArtifacts = universe.stream()
                    .filter(a -> !projectSet.contains(a))
                    .toList();

            // Build the project manifest first; seed the implicit build with the project's claimed
            // files so the two manifests are a disjoint partition with PROJECT precedence (design §6).
            ManifestBuilder builder = new ManifestBuilder(localRepoBase);
            Set<String> claimed = new HashSet<>();
            List<ManifestArtifact> projectEntries = builder.build(projectArtifacts, claimed);
            List<ManifestArtifact> implicitEntries = builder.build(implicitArtifacts, claimed);

            ReportConfig config = ReportConfig.from(session);
            manifestWriter.write(config.manifestPath(), projectEntries);
            manifestWriter.write(config.implicitManifestPath(), implicitEntries);

            List<String> observedArtifacts = universe.stream()
                    .map(ResolvedArtifact::coordinates)
                    .distinct()
                    .toList();
            List<GrayZoneArtifact> grayZone = grayZone(universe, projectFiles);
            reportWriter.write(
                    config.reportPath(), evidence, observedArtifacts, grayZone, warnings);

            LOGGER.info("repo-provenance: wrote project manifest to {} and implicit manifest to {} "
                    + "({} PROJECT / {} IMPLICIT of {} observed artifacts)",
                    config.manifestPath(), config.implicitManifestPath(),
                    projectArtifacts.size(), implicitArtifacts.size(), universe.size());
        } catch (Exception e) {
            // Never fail the build because of this observational extension.
            LOGGER.warn("repo-provenance: failed to produce outputs, build is unaffected", e);
        }
    }

    /** Identifies dynamically-resolved test providers and records how they were classified (§8). */
    private static List<GrayZoneArtifact> grayZone(
            List<ResolvedArtifact> universe, Set<String> projectFiles) {
        return universe.stream()
                .filter(a -> GrayZoneDetector.isDynamicTestProvider(a.groupId(), a.artifactId()))
                .map(a -> new GrayZoneArtifact(
                        a.coordinates(),
                        a.file() != null && projectFiles.contains(a.file().getAbsolutePath())
                                ? Provenance.PROJECT
                                : Provenance.IMPLICIT,
                        "surefire/failsafe provider resolved dynamically by an implicit plugin"))
                .distinct()
                .toList();
    }
}

