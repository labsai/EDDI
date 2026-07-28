/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule;

import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the portable {@link IScheduleStore#deleteSchedulesByUserId} default,
 * so a backend that does not override it still erases correctly rather than
 * silently skipping the user's schedules.
 */
class IScheduleStoreErasureDefaultTest {

    private IScheduleStore store;

    private static ScheduleConfiguration schedule(String id, String userId) {
        var config = new ScheduleConfiguration();
        config.setId(id);
        config.setUserId(userId);
        return config;
    }

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IScheduleStore.class);
        when(store.deleteSchedulesByUserId(anyString())).thenCallRealMethod();
    }

    @Test
    @DisplayName("the default deletes only the requested user's schedules")
    void deletesOnlyTheRequestedUsersSchedules() throws Exception {
        when(store.readAllSchedules(anyInt())).thenReturn(List.of(
                schedule("s-1", "user-1"),
                schedule("s-2", "user-2"),
                schedule("s-3", "user-1"),
                schedule("s-4", null)));

        assertEquals(2, store.deleteSchedulesByUserId("user-1"));

        verify(store).deleteSchedule("s-1");
        verify(store).deleteSchedule("s-3");
        verify(store, never()).deleteSchedule("s-2");
        verify(store, never()).deleteSchedule("s-4");
    }

    /**
     * {@code LIMIT 0} returns nothing on PostgreSQL and everything on MongoDB, so
     * the scan must pass an explicit positive bound — otherwise erasure would
     * silently delete nothing on one of the two supported backends.
     */
    @Test
    @DisplayName("the scan is bounded by an explicit positive limit")
    void scanUsesAnExplicitPositiveLimit() throws Exception {
        when(store.readAllSchedules(anyInt())).thenReturn(List.of());

        store.deleteSchedulesByUserId("user-1");

        verify(store).readAllSchedules(IScheduleStore.ERASURE_SCAN_LIMIT);
        assertTrue(IScheduleStore.ERASURE_SCAN_LIMIT > 0);
    }

    @Test
    @DisplayName("a blank user deletes nothing")
    void blankUserDeletesNothing() throws Exception {
        assertEquals(0, store.deleteSchedulesByUserId("   "));
        verify(store, never()).readAllSchedules(anyInt());
        verify(store, never()).deleteSchedule(anyString());
    }
}
