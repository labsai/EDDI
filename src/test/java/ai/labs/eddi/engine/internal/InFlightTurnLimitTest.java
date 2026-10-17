/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightTurnLimitTest {

    private static final long MB = 1024L * 1024L;

    @Test
    @DisplayName("auto scales with the heap: (heap - 256 MB) / 2 MB per turn")
    void autoScalesWithTheHeap() {
        assertEquals(128, InFlightTurnLimit.resolve("auto", 512 * MB, 2), "the Helm chart's former 1 GiB limit at 50 %");
        assertEquals(640, InFlightTurnLimit.resolve("auto", 1536 * MB, 2));
        assertEquals(1408, InFlightTurnLimit.resolve("auto", 3072 * MB, 2), "a 4 GiB container at 75 %");
        assertEquals(2944, InFlightTurnLimit.resolve("auto", 6144 * MB, 2));
    }

    @Test
    @DisplayName("the per-turn budget is the knob: 1 MB doubles the limit")
    void perTurnBudgetScalesTheLimit() {
        assertEquals(2816, InFlightTurnLimit.resolve("auto", 3072 * MB, 1));
        assertEquals(704, InFlightTurnLimit.resolve("auto", 3072 * MB, 4));
    }

    @Test
    @DisplayName("a tiny heap still admits some turns")
    void tinyHeapHasAFloor() {
        assertEquals(InFlightTurnLimit.MIN_AUTO, InFlightTurnLimit.resolve("auto", 200 * MB, 2));
    }

    @Test
    @DisplayName("an unbounded heap falls back to a fixed limit")
    void unboundedHeapFallsBack() {
        assertEquals(InFlightTurnLimit.UNBOUNDED_HEAP_FALLBACK, InFlightTurnLimit.resolve("auto", Long.MAX_VALUE, 2));
    }

    @Test
    @DisplayName("an explicit number wins, 0 disables, and auto is case-insensitive or blank")
    void explicitValues() {
        assertEquals(1000, InFlightTurnLimit.resolve("1000", 512 * MB, 2));
        assertEquals(0, InFlightTurnLimit.resolve("0", 512 * MB, 2));
        assertEquals(-1, InFlightTurnLimit.resolve(" -1 ", 512 * MB, 2));
        assertEquals(128, InFlightTurnLimit.resolve(" AUTO ", 512 * MB, 2));
        assertEquals(128, InFlightTurnLimit.resolve("", 512 * MB, 2));
        assertEquals(128, InFlightTurnLimit.resolve(null, 512 * MB, 2));
    }

    @Test
    @DisplayName("a setting that is neither auto nor a number fails, naming the property")
    void invalidSettingFails() {
        var e = assertThrows(IllegalArgumentException.class, () -> InFlightTurnLimit.resolve("lots", 512 * MB, 2));
        assertTrue(e.getMessage().contains("eddi.conversation.max-in-flight-turns"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> InFlightTurnLimit.resolve("auto", 512 * MB, 0));
    }
}
