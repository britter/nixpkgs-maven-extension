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
 * Recognises the §8 gray-zone artifacts: dynamically resolved plugin dependencies that an IMPLICIT
 * plugin selects at runtime based on the project. The canonical case is the surefire/failsafe test
 * <em>provider</em> (JUnit-platform, JUnit4, JUnit47, TestNG), whose version is tied to the plugin
 * version, so by §5.2 it is correctly IMPLICIT. The extension does not reclassify these — it surfaces
 * them distinctly in the report so a consumer can decide how to supply them.
 *
 * <p>Pure predicate; unit-testable in isolation.
 */
public final class GrayZoneDetector {

    private GrayZoneDetector() {
    }

    /**
     * True if the coordinates look like a surefire/failsafe provider that the test plugin resolves
     * dynamically from the project's test dependencies.
     */
    public static boolean isDynamicTestProvider(String groupId, String artifactId) {
        if (!"org.apache.maven.surefire".equals(groupId)) {
            return false;
        }
        return artifactId.startsWith("surefire-junit")
                || artifactId.equals("surefire-testng")
                || artifactId.startsWith("surefire-testng");
    }
}
