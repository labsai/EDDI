/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Agent Health settings and retention")
class HealthTurnRetentionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T00:30:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("the sweep deletes the days before today minus the retention")
    void sweep() {
        IHealthTurnStore store = mock(IHealthTurnStore.class);
        new HealthTurnRetention(store, HealthSettings.defaults(), CLOCK).sweep();
        verify(store).deleteOlderThan(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("a failing sweep is logged, never thrown")
    void failure() {
        IHealthTurnStore store = mock(IHealthTurnStore.class);
        when(store.deleteOlderThan(any())).thenThrow(new HealthStoreException("down", null));
        assertDoesNotThrow(() -> new HealthTurnRetention(store, HealthSettings.defaults(), CLOCK).sweep());
    }

    @Test
    @DisplayName("the sweep runs with collection switched off")
    void runsWhenDisabled() {
        IHealthTurnStore store = mock(IHealthTurnStore.class);
        new HealthTurnRetention(store, new HealthSettings(false, 10, Duration.ofDays(7)), CLOCK).sweep();
        verify(store).deleteOlderThan(LocalDate.of(2026, 11, 3));
    }

    @Test
    @DisplayName("defaults: on, a 10,000 queue, 90 days; nonsense is bounded")
    void settings() {
        HealthSettings defaults = HealthSettings.defaults();
        assertTrue(defaults.enabled());
        assertEquals(10_000, defaults.queueCapacity());
        assertEquals(90, defaults.factsRetentionDays());

        HealthSettings odd = new HealthSettings(false, 0, Duration.ofHours(1));
        assertFalse(odd.enabled());
        assertEquals(1, odd.queueCapacity());
        assertEquals(1, odd.factsRetentionDays(), "partial days round up");
        assertEquals(90, new HealthSettings(true, 5, Duration.ZERO).factsRetentionDays());
        assertEquals(90, new HealthSettings(true, 5, null).factsRetentionDays());
        assertEquals(2, new HealthSettings(true, 5, Duration.ofHours(25)).factsRetentionDays());
    }
}
