package dev.britter.maven.provenance;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.aether.RepositoryEvent;

/**
 * Shared, build-scoped collector of everything resolved into the local repository.
 *
 * <p>This singleton is written concurrently: in a parallel reactor build ({@code mvn -T})
 * {@link RepositoryEvent}s fire on several worker threads at once. All mutable state must therefore
 * be held in thread-safe structures and updated without check-then-act races. Reads happen only at
 * session end, after every worker thread has finished (a happens-before barrier), so the
 * classification that consumes this data can run single-threaded over a stable snapshot.
 */
@Named
@Singleton
public class ResolutionRecorder {

    private final Queue<ResolvedArtifact> artifacts = new ConcurrentLinkedQueue<>();

    /** Records a single artifact resolution. Safe to call from any thread. */
    public void recordArtifact(ResolvedArtifact artifact) {
        artifacts.add(artifact);
    }

    /** Number of artifact resolutions recorded so far. Intended for diagnostics. */
    public int artifactCount() {
        return artifacts.size();
    }
}
