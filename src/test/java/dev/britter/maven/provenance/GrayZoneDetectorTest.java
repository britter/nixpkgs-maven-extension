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
    public void doesNotFlagOrdinaryArtifacts() {
        assertFalse(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.maven.surefire", "surefire-api"));
        assertFalse(GrayZoneDetector.isDynamicTestProvider(
                "org.junit.jupiter", "junit-jupiter-api"));
        assertFalse(GrayZoneDetector.isDynamicTestProvider(
                "org.apache.commons", "commons-lang3"));
    }
}
