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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for the pure gray-zone provider predicate (design §8). */
public class GrayZoneDetectorTest {

    @Test
    public void recognisesSurefireProviders() {
        assertTrue(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "surefire-junit-platform"));
        assertTrue(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "surefire-junit47"));
        assertTrue(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "surefire-testng"));
    }

    @Test
    public void recognisesProviderSupportLibraries() {
        // The provider's own dependency closure is resolved into the provider classloader alongside
        // it and shares its classification (issue #11). These common-* support libs must be flagged
        // so a pinned surefire keeps them PROJECT rather than dropping them to IMPLICIT.
        assertTrue(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "common-junit4"));
        assertTrue(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "common-junit48"));
        assertTrue(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "common-junit3"));
        assertTrue(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "common-java5"));
    }

    @Test
    public void doesNotFlagOrdinaryArtifacts() {
        assertFalse(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "surefire-api"));
        assertFalse(GrayZoneDetector.isDynamicTestProvider(
                "org.junit.jupiter", "junit-jupiter-api"));
        assertFalse(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.commons", "commons-lang3"));
    }
}
