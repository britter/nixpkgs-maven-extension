package dev.britter.maven.provenance;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.eclipse.aether.RepositoryEvent;
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
        // Recording of resolved artifacts/metadata is implemented in a later increment.
    }
}
