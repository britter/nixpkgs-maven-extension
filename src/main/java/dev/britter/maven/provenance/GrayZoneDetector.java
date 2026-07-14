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
     * True if the coordinates look like a surefire/failsafe provider — or a support library from the
     * provider's own dependency closure — that the test plugin resolves dynamically from the
     * project's test dependencies.
     *
     * <p>The provider ({@code surefire-junit4}, {@code surefire-junit-platform}, {@code surefire-testng},
     * ...) and its transitive {@code common-*} support libs ({@code common-junit4}, {@code common-java5},
     * ...) are resolved together into the provider classloader, not the plugin realm, so they share one
     * classification. Matching only the provider left its {@code common-*} closure to fall through to
     * IMPLICIT, which strands the pinned version in neither the FOD nor the default-plugins repo (issue
     * #11). Plugin-realm artifacts ({@code surefire-api}, {@code surefire-booter}, ...) are excluded here
     * because the realm closure already captures them.
     */
    public static boolean isDynamicTestProvider(String groupId, String artifactId) {
        if (!"org.apache.maven.surefire".equals(groupId)) {
            return false;
        }
        return artifactId.startsWith("surefire-junit")
                || artifactId.startsWith("surefire-testng")
                || artifactId.startsWith("common-");
    }
}
