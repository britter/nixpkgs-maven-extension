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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.io.File;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.project.DependencyResolutionRequest;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RequestTrace;
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
            default -> {
                // Other events (metadata, installing, deploying, ...) are not footprint.
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

        // Attribute the resolution to its trigger so project dependencies (all scopes, incl. test)
        // are captured as PROJECT roots independently of MavenProject.getArtifacts()'s scope filter.
        if (isProjectDependencyResolution(event.getTrace())) {
            recorder.recordProjectDependencyFile(file);
        }
    }

    /**
     * True if this resolution was triggered by project dependency resolution rather than plugin
     * resolution. Maven roots the {@link RequestTrace} with a {@link DependencyResolutionRequest}
     * for project dependencies and with an {@code org.apache.maven.model.Plugin} for plugins; the
     * root's data type is the stable discriminator across Maven 3.9.x (design §"attribute each
     * resolution"). A null or third-party trace is unattributed and treated as not-project.
     */
    private static boolean isProjectDependencyResolution(RequestTrace trace) {
        Object rootData = null;
        for (RequestTrace node = trace; node != null; node = node.getParent()) {
            rootData = node.getData();
        }
        return rootData instanceof DependencyResolutionRequest;
    }
}
