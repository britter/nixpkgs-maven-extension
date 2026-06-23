package dev.britter.maven.provenance;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.maven.execution.MavenSession;

/**
 * Resolves the output locations of the manifest and the diagnostics report from system properties,
 * falling back to the reactor execution root's build directory (design §6.1, §6.3).
 */
public final class ReportConfig {

    /** System property overriding the manifest path (absolute). */
    public static final String OUTPUT_PROPERTY = "repoprovenance.output";
    /** System property overriding the diagnostics report path (absolute). */
    public static final String REPORT_PROPERTY = "repoprovenance.report";

    static final String MANIFEST_FILE_NAME = "repo-provenance.json";
    static final String REPORT_FILE_NAME = "repo-provenance-report.json";

    private final Path manifestPath;
    private final Path reportPath;

    private ReportConfig(Path manifestPath, Path reportPath) {
        this.manifestPath = manifestPath;
        this.reportPath = reportPath;
    }

    public static ReportConfig from(MavenSession session) {
        Path buildDir = Paths.get(session.getTopLevelProject().getBuild().getDirectory());
        Path manifest = resolve(session.getSystemProperties().getProperty(OUTPUT_PROPERTY),
                buildDir.resolve(MANIFEST_FILE_NAME));
        Path report = resolve(session.getSystemProperties().getProperty(REPORT_PROPERTY),
                buildDir.resolve(REPORT_FILE_NAME));
        return new ReportConfig(manifest, report);
    }

    private static Path resolve(String override, Path fallback) {
        return override != null && !override.isBlank() ? Paths.get(override) : fallback;
    }

    public Path manifestPath() {
        return manifestPath;
    }

    public Path reportPath() {
        return reportPath;
    }
}
