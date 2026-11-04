/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Must agree with {@code compareVersions} in the Manager's updates.ts. */
class ReleaseVersionsTest {

    @ParameterizedTest
    @CsvSource({"6.6.0,6.7.0", "6.6.0,6.10.0", "6.6,6.6.1", "6.7.0-RC1,6.7.0", "6.7.0-SNAPSHOT,6.7.0", "6.7.0-RC1,6.7.0-RC2",
            "6.7.0-RC.2,6.7.0-RC.10", "v6.6.0,v6.6.1", "6.6.0,7"})
    @DisplayName("a newer release is detected")
    void newer(String installed, String latest) {
        assertTrue(ReleaseVersions.isNewer(installed, latest));
    }

    @ParameterizedTest
    @CsvSource({"6.7.0,6.7.0", "6.7.0,6.6.9", "6.7.0,6.7.0-RC1", "6.7.0+build.7,6.7.0", "6.7,6.7.0"})
    @DisplayName("an equal or older release is not newer")
    void notNewer(String installed, String latest) {
        assertFalse(ReleaseVersions.isNewer(installed, latest));
    }

    @ParameterizedTest
    @CsvSource({"unknown,6.7.0", "6.6.0,latest", "'',6.7.0", "6.6.0,''"})
    @DisplayName("something that is not a version never raises a notice")
    void unparseable(String installed, String latest) {
        assertFalse(ReleaseVersions.isNewer(installed, latest));
    }

    @Test
    @DisplayName("normalize strips whitespace and a leading v")
    void normalizes() {
        assertEquals("6.7.0", ReleaseVersions.normalize(" v6.7.0 "));
        assertTrue(ReleaseVersions.isVersion("V6.7.0-RC1"));
        assertFalse(ReleaseVersions.isVersion("6.7.0; drop"));
    }
}
