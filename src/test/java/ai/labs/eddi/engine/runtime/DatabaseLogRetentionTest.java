/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@code eddi.logs.db-retention-days} was documented as configurable while no
 * code ever deleted a persisted log entry.
 */
class DatabaseLogRetentionTest {

    private final IDatabaseLogs databaseLogs = mock(IDatabaseLogs.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("a positive retention deletes the older entries and counts them")
    void deletesEntriesOlderThanTheRetention() {
        when(databaseLogs.deleteOlderThan(30)).thenReturn(12L);
        var retention = new DatabaseLogRetention(databaseLogs, 30, true, registry);

        assertEquals(12, retention.sweep());

        verify(databaseLogs).deleteOlderThan(30);
        assertEquals(12.0, registry.counter("eddi.logs.db.retention.deleted").count());
    }

    @Test
    @DisplayName("off by default (-1) and for 0: nothing is deleted")
    void disabledRetentionDeletesNothing() {
        assertEquals(0, new DatabaseLogRetention(databaseLogs, -1, true, registry).sweep());
        assertEquals(0, new DatabaseLogRetention(databaseLogs, 0, true, registry).sweep());
        verifyNoInteractions(databaseLogs);
    }

    @Test
    @DisplayName("still applies after persistence was switched off — stored entries are not exempt")
    void appliesWhenPersistenceIsOff() {
        when(databaseLogs.deleteOlderThan(7)).thenReturn(3L);

        assertEquals(3, new DatabaseLogRetention(databaseLogs, 7, false, registry).sweep());
    }

    @Test
    @DisplayName("a failing sweep is logged and retried on the next run, never thrown into the scheduler")
    void failureIsContained() {
        when(databaseLogs.deleteOlderThan(30)).thenThrow(new IllegalStateException("db down"));

        assertEquals(0, new DatabaseLogRetention(databaseLogs, 30, true, registry).sweep());
    }
}
