/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.CATEGORY;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.PATTERN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AutonomyOverlayService")
class AutonomyOverlayServiceTest {

    private IAutonomyStateStore store;
    private AutonomyOverlayService overlay;

    @BeforeEach
    void setUp() {
        store = mock(IAutonomyStateStore.class);
        overlay = new AutonomyOverlayService(store, OversightSettings.defaults());
    }

    private static AutonomyState suspended(String category, String pattern) {
        return AutonomyState.initial(AGENT, category).withSuspension(
                new AutonomyState.Suspension(true, pattern, NOW, AutonomyState.Suspension.Trigger.MANUAL, null, null, "bob", null, null, "x"));
    }

    @Test
    @DisplayName("an unknown agent id has no suspensions and never reads the store")
    void nullAgent() {
        assertTrue(overlay.suspendedPatterns(null).isEmpty());
        verify(store, never()).listSuspended(null);
    }

    @Test
    @DisplayName("the list is read once and then cached; invalidate forces a re-read; invalidating null is a no-op")
    void cachesPerAgent() {
        when(store.listSuspended(AGENT)).thenReturn(List.of(suspended(CATEGORY, PATTERN)));

        assertEquals(Map.of(PATTERN, CATEGORY), overlay.suspendedPatterns(AGENT));
        assertEquals(Map.of(PATTERN, CATEGORY), overlay.suspendedPatterns(AGENT));
        verify(store, times(1)).listSuspended(AGENT);

        overlay.invalidate(null);
        overlay.invalidate(AGENT);
        overlay.suspendedPatterns(AGENT);
        verify(store, times(2)).listSuspended(AGENT);
    }

    @Test
    @DisplayName("states without an active suspension or a pattern are skipped; the first category of a pattern wins")
    void filtersStates() {
        AutonomyState lifted = suspended("lifted", "p-lifted");
        lifted = lifted.withSuspension(lifted.suspension().lifted("bob", NOW, "ok"));
        when(store.listSuspended(AGENT)).thenReturn(
                List.of(lifted, suspended("nopattern", null), suspended("first", "dup"), suspended("second", "dup"),
                        AutonomyState.initial(AGENT, "plain")));

        assertEquals(Map.of("dup", "first"), overlay.suspendedPatterns(AGENT));
    }

    @Test
    @DisplayName("an agent with no suspensions gets an empty map that is cached too")
    void emptyIsCached() {
        when(store.listSuspended(AGENT)).thenReturn(List.of());
        assertTrue(overlay.suspendedPatterns(AGENT).isEmpty());
        assertTrue(overlay.suspendedPatterns(AGENT).isEmpty());
        verify(store, times(1)).listSuspended(AGENT);
    }

    @Test
    @DisplayName("a store failure falls back to the last known list, or to none when there never was one")
    void storeFailure() {
        when(store.listSuspended("fresh")).thenThrow(new IllegalStateException("db down"));
        assertTrue(overlay.suspendedPatterns("fresh").isEmpty(), "no last known list: nothing extra is gated");

        when(store.listSuspended(AGENT)).thenReturn(List.of(suspended(CATEGORY, PATTERN))).thenThrow(new IllegalStateException("db down"));
        overlay.suspendedPatterns(AGENT);
        overlay.invalidate(AGENT);
        assertEquals(Map.of(PATTERN, CATEGORY), overlay.suspendedPatterns(AGENT), "the last known list is used");
    }

    @Test
    @DisplayName("missing settings or a missing ttl fall back to 15 seconds and do not fail construction")
    void settingsDefaults() {
        var noTtl = new OversightSettings(Duration.ofDays(1), Duration.ofDays(1), true, Duration.ofDays(1), Duration.ofHours(1), null, 10);
        when(store.listSuspended(AGENT)).thenReturn(List.of());
        new AutonomyOverlayService(store, noTtl).suspendedPatterns(AGENT);
        new AutonomyOverlayService(store, null).suspendedPatterns(AGENT);
        // The PostConstruct hook is idempotent once the caches exist.
        overlay.init();
        verify(store, times(2)).listSuspended(AGENT);
    }

    @Test
    @DisplayName("apply puts the suspensions on a config, and leaves a config that already carries them alone")
    void apply() {
        when(store.listSuspended(AGENT)).thenReturn(List.of(suspended(CATEGORY, PATTERN)));
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));

        ToolApprovalsConfig overlaid = overlay.apply(AGENT, cfg);

        assertNotSame(cfg, overlaid);
        assertEquals(List.of(PATTERN), overlaid.getSuspendedPatterns());
        assertSame(overlaid, overlay.apply(AGENT, overlaid));
    }

    @Test
    @DisplayName("applyTo and applyToCarrier tolerate a missing service or memory")
    void nullSafe() {
        var cfg = new ToolApprovalsConfig();
        var memory = mock(IConversationMemory.class);

        assertSame(cfg, AutonomyOverlayService.applyTo(null, memory, cfg));
        assertSame(cfg, AutonomyOverlayService.applyTo(overlay, null, cfg));
        AutonomyOverlayService.applyToCarrier(null, memory);
        AutonomyOverlayService.applyToCarrier(overlay, null);

        verify(memory, never()).setAgentToolApprovalsConfig(any());
    }

    @Test
    @DisplayName("applyToCarrier replaces the memory's carrier only when the overlay changed it")
    void carrier() {
        var memory = mock(IConversationMemory.class);
        var carrier = new ToolApprovalsConfig();
        carrier.setRequireApproval(List.of("http.post:*"));
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getAgentToolApprovalsConfig()).thenReturn(carrier);

        when(store.listSuspended(AGENT)).thenReturn(List.of());
        AutonomyOverlayService.applyToCarrier(overlay, memory);
        verify(memory, never()).setAgentToolApprovalsConfig(any());

        overlay.invalidate(AGENT);
        when(store.listSuspended(AGENT)).thenReturn(List.of(suspended(CATEGORY, PATTERN)));
        AutonomyOverlayService.applyToCarrier(overlay, memory);
        verify(memory, times(1)).setAgentToolApprovalsConfig(argThat(c -> c != carrier
                && c.getSuspendedPatterns().equals(List.of(PATTERN))));

        assertEquals(List.of(PATTERN), AutonomyOverlayService.applyTo(overlay, memory, carrier).getSuspendedPatterns());
    }
}
