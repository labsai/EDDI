/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.IUserMemoryStore.RecallWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code most_accessed} slot split is shared by the MongoDB and PostgreSQL
 * stores precisely so a recall order means one thing on both backends. These
 * tests pin the split itself.
 */
@DisplayName("RecallWindow — most_accessed slot split")
class RecallWindowTest {

    /**
     * The defect: {@code Math.max(1, maxEntries / 5)} handed the ONLY slot of a
     * one-entry window to recency, leaving zero access slots. The access-count
     * query was then skipped entirely and {@code most_accessed} returned the most
     * RECENT entry — the exact opposite of the requested ordering. maxEntries is
     * reachable as 1 from the agent's {@code maxRecallEntries}, from the REST query
     * parameter and from the MCP tool argument.
     */
    @Test
    @DisplayName("a one-slot window spends its slot on access count, not recency")
    void windowOfOneKeepsItsAccessSlot() {
        RecallWindow window = RecallWindow.forMaxEntries(1);

        assertEquals(1, window.accessSlots());
        assertEquals(0, window.recencySlots());
    }

    @Test
    @DisplayName("every window from 1 upwards keeps at least one access slot")
    void everyBoundedWindowKeepsAnAccessSlot() {
        for (int maxEntries = 1; maxEntries <= 200; maxEntries++) {
            RecallWindow window = RecallWindow.forMaxEntries(maxEntries);

            assertTrue(window.accessSlots() >= 1, "maxEntries=" + maxEntries + " left no access slots: " + window);
            assertEquals(maxEntries, window.accessSlots() + window.recencySlots(),
                    "the two slices must exactly fill the window at maxEntries=" + maxEntries);
        }
    }

    @Test
    @DisplayName("windows of two or more still reserve recency slots")
    void reservationSurvivesForRealWindows() {
        assertEquals(new RecallWindow(1, 1), RecallWindow.forMaxEntries(2));
        assertEquals(new RecallWindow(8, 2), RecallWindow.forMaxEntries(10));
        assertEquals(new RecallWindow(40, 10), RecallWindow.forMaxEntries(50));
    }

    @Test
    @DisplayName("maxEntries <= 0 means unlimited by access count and no separate recency pass")
    void nonPositiveWindowIsUnlimited() {
        // -1 is the "no limit" sentinel; 0 means "do not issue that query at all",
        // which is right because the unlimited access pass already saw everything.
        assertEquals(new RecallWindow(-1, 0), RecallWindow.forMaxEntries(0));
        assertEquals(new RecallWindow(-1, 0), RecallWindow.forMaxEntries(-5));
    }
}
