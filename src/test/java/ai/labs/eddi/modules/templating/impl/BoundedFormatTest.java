/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BoundedFormat — the size estimate itself is cheap")
class BoundedFormatTest {

    @Test
    @DisplayName("a % followed by a long run of zeros is estimated in linear time")
    void zeroRunDoesNotBacktrackQuadratically() {
        // '0' is a flag or the start of the width; a regex that accepts both tries
        // every split of the run, 10^10 steps for this one.
        String format = "%" + "0".repeat(100_000);

        long estimate = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> BoundedFormat.estimate(format, new Object[0], Long.MAX_VALUE));

        assertEquals(format.length(), estimate);
    }

    @Test
    @DisplayName("a repeated unfinished specifier is estimated in linear time")
    void repeatedUnfinishedSpecifier() {
        String format = "%0".repeat(50_000);

        assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> BoundedFormat.estimate(format, new Object[0], Long.MAX_VALUE));
    }

    @Test
    @DisplayName("a zero-padded width is still read as flag plus width")
    void zeroPaddedWidthStillCounts() {
        long estimate = BoundedFormat.estimate("%0100d", new Object[]{1}, Long.MAX_VALUE);

        assertEquals(100, estimate);
    }

    @Test
    @DisplayName("a huge width is still refused before formatting")
    void hugeWidthStillRefused() {
        assertTrue(BoundedFormat.estimate("%2000000000d", new Object[]{1}, 1_000) > 1_000);
    }
}
