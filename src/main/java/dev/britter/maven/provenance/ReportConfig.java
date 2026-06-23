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

import org.apache.maven.execution.MavenSession;

/**
 * Resolves the output locations of the manifest and the diagnostics report from system properties,
 * falling back to the reactor execution root's build directory (design §6.1, §6.3).
 */
public final class ReportConfig {

    /** System property overriding the project manifest path (absolute). */
    public static final String OUTPUT_PROPERTY = "repoprovenance.output";
    /** System property overriding the implicit manifest path (absolute). */
    public static final String IMPLICIT_OUTPUT_PROPERTY = "repoprovenance.implicitOutput";
    /** System property overriding the diagnostics report path (absolute). */
    public static final String REPORT_PROPERTY = "repoprovenance.report";

    static final String MANIFEST_FILE_NAME = "repo-provenance.json";
    static final String IMPLICIT_MANIFEST_FILE_NAME = "repo-provenance-implicit.json";
    static final String REPORT_FILE_NAME = "repo-provenance-report.json";

    private final Path manifestPath;
    private final Path implicitManifestPath;
    private final Path reportPath;

    private ReportConfig(Path manifestPath, Path implicitManifestPath, Path reportPath) {
        this.manifestPath = manifestPath;
        this.implicitManifestPath = implicitManifestPath;
        this.reportPath = reportPath;
    }

    public static ReportConfig from(MavenSession session) {
        Path buildDir = Paths.get(session.getTopLevelProject().getBuild().getDirectory());
        Path manifest = resolve(session.getSystemProperties().getProperty(OUTPUT_PROPERTY),
                buildDir.resolve(MANIFEST_FILE_NAME));
        Path implicitManifest = resolve(
                session.getSystemProperties().getProperty(IMPLICIT_OUTPUT_PROPERTY),
                buildDir.resolve(IMPLICIT_MANIFEST_FILE_NAME));
        Path report = resolve(session.getSystemProperties().getProperty(REPORT_PROPERTY),
                buildDir.resolve(REPORT_FILE_NAME));
        return new ReportConfig(manifest, implicitManifest, report);
    }

    private static Path resolve(String override, Path fallback) {
        return override != null && !override.isBlank() ? Paths.get(override) : fallback;
    }

    public Path manifestPath() {
        return manifestPath;
    }

    public Path implicitManifestPath() {
        return implicitManifestPath;
    }

    public Path reportPath() {
        return reportPath;
    }
}
