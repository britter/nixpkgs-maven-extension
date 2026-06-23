package dev.britter.maven.provenance;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import dev.britter.maven.provenance.report.ReportWriter;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
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
    private final ProvenanceAnalyzer analyzer = new ProvenanceAnalyzer();
    private final ReportWriter reportWriter = new ReportWriter();

    @Inject
    public ProvenanceLifecycleParticipant(ResolutionRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void afterSessionEnd(MavenSession session) {
        try {
            List<String> warnings = new ArrayList<>();
            List<PluginEvidence> evidence = analyzer.analyze(session);

            ReportConfig config = ReportConfig.from(session);
            reportWriter.write(config.reportPath(), evidence, warnings);

            LOGGER.info("repo-provenance: wrote provenance report to {} ({} plugins/extensions, "
                    + "{} artifact resolutions observed)",
                    config.reportPath(), evidence.size(), recorder.artifactCount());
        } catch (Exception e) {
            // Never fail the build because of this observational extension.
            LOGGER.warn("repo-provenance: failed to produce outputs, build is unaffected", e);
        }
    }
}
