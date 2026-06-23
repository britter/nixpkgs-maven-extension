package dev.britter.maven.provenance;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.io.File;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.artifact.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observes the build's interaction with the local repository.
 *
 * <p>Maven wires its {@code EventSpyDispatcher} as a {@code RepositoryListener} on the resolver
 * session, so every {@link RepositoryEvent} is delivered here. We only read these events; we never
 * mutate the session or the resolution result, which keeps the extension strictly observational
 * (design §3, §11).
 *
 * <p>{@link #onEvent(Object)} is invoked concurrently in parallel reactor builds, so it must only
 * touch thread-safe collaborators such as {@link ResolutionRecorder}.
 */
@Named
@Singleton
public class ProvenanceEventSpy extends AbstractEventSpy {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProvenanceEventSpy.class);

    private final ResolutionRecorder recorder;

    @Inject
    public ProvenanceEventSpy(ResolutionRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void init(Context context) {
        LOGGER.debug("repo-provenance: extension loaded, observing local-repository resolutions");
    }

    @Override
    public void onEvent(Object event) {
        if (event instanceof RepositoryEvent repositoryEvent) {
            onRepositoryEvent(repositoryEvent);
        }
    }

    private void onRepositoryEvent(RepositoryEvent event) {
        switch (event.getType()) {
            case ARTIFACT_RESOLVED, ARTIFACT_DOWNLOADED -> recordArtifact(event);
            case METADATA_RESOLVED, METADATA_DOWNLOADED -> recorder.recordMetadata(event.getFile());
            default -> {
                // Other events (installing, deploying, descriptor invalid, ...) are not footprint.
            }
        }
    }

    private void recordArtifact(RepositoryEvent event) {
        Artifact artifact = event.getArtifact();
        if (artifact == null) {
            return;
        }
        // event.getFile() is the file in the local repository; fall back to the artifact's file.
        File file = event.getFile() != null ? event.getFile() : artifact.getFile();
        String classifier = artifact.getClassifier();
        recorder.recordArtifact(new ResolvedArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                artifact.getExtension(),
                classifier == null || classifier.isEmpty() ? null : classifier,
                file));
    }
}
