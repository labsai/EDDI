/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memory-misconfiguration warning is suppressed per agent, not per turn.
 * <p>
 * Two ways the first version got this wrong. The dedup key was skipped entirely
 * for a null agent id, so the defensive path was the one case that logged on
 * every turn — the exact flood the cache exists to prevent. And the plain set
 * behind it grew for the JVM lifetime, which is unbounded on a platform that
 * creates dynamic and ephemeral agents with fresh ids at runtime; it is a
 * bounded, expiring cache now, so suppression is deliberately best-effort.
 * <p>
 * The cache is static and JVM-wide, so every test here uses ids unique to this
 * class — asserting on an id another test may have warmed would be flaky by
 * construction.
 */
@DisplayName("memory-misconfiguration warning dedup")
class ContextualToolsProviderWarnDedupTest {

    @Test
    @DisplayName("an agent warns once, then is suppressed")
    void warnsOncePerAgent() {
        assertTrue(ContextualToolsProvider.shouldWarnAboutMemoryMisconfiguration("warn-dedup-agent-1"),
                "the first sighting must warn");
        assertFalse(ContextualToolsProvider.shouldWarnAboutMemoryMisconfiguration("warn-dedup-agent-1"),
                "the second sighting must be suppressed");
    }

    @Test
    @DisplayName("distinct agents each get their own warning")
    void distinctAgentsWarnIndependently() {
        assertTrue(ContextualToolsProvider.shouldWarnAboutMemoryMisconfiguration("warn-dedup-agent-2"));
        assertTrue(ContextualToolsProvider.shouldWarnAboutMemoryMisconfiguration("warn-dedup-agent-3"));
    }

    /**
     * A null agent id used to bypass deduplication entirely and log on every turn.
     * It maps to a stable fallback key instead — which also means all null-id
     * callers share one suppression slot, the right trade for a defensive path that
     * a real conversation should never hit.
     */
    @Test
    @DisplayName("a null agent id is deduplicated too, not logged per turn")
    void nullAgentIdDoesNotFloodTheLog() {
        // Whether the FIRST call warns depends on whether anything else in this JVM
        // already consumed the shared fallback slot — so only the invariant is
        // asserted: a second consecutive sighting must be suppressed.
        ContextualToolsProvider.shouldWarnAboutMemoryMisconfiguration(null);
        assertFalse(ContextualToolsProvider.shouldWarnAboutMemoryMisconfiguration(null),
                "every null-id turn warning again is the flood the cache exists to prevent");
    }
}
