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

/**
 * Provenance evidence for a single plugin or build/core extension in one project (design §6.3).
 *
 * @param projectId   the {@code groupId:artifactId} of the project the plugin was declared in
 * @param kind        {@code "plugin"} or {@code "extension"}
 * @param key         the {@code groupId:artifactId} of the plugin/extension
 * @param version     the effective version
 * @param provenance  the classification of that version
 * @param sourceModelId the {@link org.apache.maven.model.InputSource#getModelId()} that defined the
 *                      version, or {@code null} when there was no model location (lifecycle binding)
 */
public record PluginEvidence(
        String projectId,
        String kind,
        String key,
        String version,
        Provenance provenance,
        String sourceModelId) {
}
