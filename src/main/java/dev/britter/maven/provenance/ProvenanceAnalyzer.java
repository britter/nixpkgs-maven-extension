package dev.britter.maven.provenance;

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Extension;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

/**
 * Computes per-plugin/extension version provenance across every project in the reactor (design
 * §5.1, §7.2). The result is the evidence stream that backs the diagnostics report and, later,
 * drives artifact classification.
 */
public class ProvenanceAnalyzer {

    /** Builds the provenance evidence for all plugins and build extensions in the reactor. */
    public List<PluginEvidence> analyze(MavenSession session) {
        List<PluginEvidence> evidence = new ArrayList<>();
        for (MavenProject project : session.getAllProjects()) {
            String projectId = project.getGroupId() + ":" + project.getArtifactId();
            for (Plugin plugin : project.getBuildPlugins()) {
                evidence.add(pluginEvidence(projectId, plugin));
            }
            if (project.getBuild() != null) {
                for (Extension extension : project.getBuild().getExtensions()) {
                    evidence.add(extensionEvidence(projectId, extension));
                }
            }
        }
        return evidence;
    }

    private PluginEvidence pluginEvidence(String projectId, Plugin plugin) {
        InputLocation location = plugin.getLocation("version");
        return new PluginEvidence(
                projectId,
                "plugin",
                plugin.getGroupId() + ":" + plugin.getArtifactId(),
                plugin.getVersion(),
                VersionProvenanceResolver.classify(location),
                modelId(location));
    }

    private PluginEvidence extensionEvidence(String projectId, Extension extension) {
        InputLocation location = extension.getLocation("version");
        return new PluginEvidence(
                projectId,
                "extension",
                extension.getGroupId() + ":" + extension.getArtifactId(),
                extension.getVersion(),
                VersionProvenanceResolver.classify(location),
                modelId(location));
    }

    private static String modelId(InputLocation location) {
        if (location == null) {
            return null;
        }
        InputSource source = location.getSource();
        return source == null ? null : source.getModelId();
    }
}
