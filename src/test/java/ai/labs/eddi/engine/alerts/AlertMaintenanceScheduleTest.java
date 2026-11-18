/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AlertMaintenanceSchedule — the system schedule is recognised by its marker, not its name")
class AlertMaintenanceScheduleTest {

    private IScheduleStore store;
    private AlertMaintenanceSchedule schedule;

    @BeforeEach
    void setUp() {
        store = mock(IScheduleStore.class);
        schedule = new AlertMaintenanceSchedule(store, AlertSettings.defaults());
    }

    private static ScheduleConfiguration row(String id, Map<String, Object> metadata) {
        ScheduleConfiguration s = new ScheduleConfiguration();
        s.setId(id);
        s.setName(AlertSchedules.SCHEDULE_NAME);
        s.setMetadata(metadata);
        return s;
    }

    @Test
    @DisplayName("creates the schedule when none exists")
    void createsWhenMissing() throws Exception {
        when(store.readScheduleByName(AlertSchedules.SCHEDULE_NAME)).thenReturn(Optional.empty());

        schedule.ensure();

        ArgumentCaptor<ScheduleConfiguration> created = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(store).createSchedule(created.capture());
        assertTrue(AlertSchedules.isMaintenanceSchedule(created.getValue().getMetadata()));
    }

    @Test
    @DisplayName("leaves the marked schedule alone")
    void keepsTheMarkedSchedule() throws Exception {
        when(store.readScheduleByName(AlertSchedules.SCHEDULE_NAME)).thenReturn(Optional.of(row("sys", AlertSchedules.maintenanceMetadata())));

        schedule.ensure();

        verify(store, never()).createSchedule(any());
    }

    @Test
    @DisplayName("a hand-made row of the same name without the marker does not count: the real schedule is created")
    void sameNameWithoutMarkerIsNotInstalled() throws Exception {
        ScheduleConfiguration impostor = row("user-1", null);
        when(store.readScheduleByName(AlertSchedules.SCHEDULE_NAME)).thenReturn(Optional.of(impostor));
        when(store.readAllSchedules(anyInt(), anyInt(), anyBoolean())).thenReturn(List.of(impostor));

        schedule.ensure();

        verify(store).createSchedule(any());
    }

    @Test
    @DisplayName("…unless the scan finds the marked schedule behind it")
    void scanFindsTheMarkedSchedule() throws Exception {
        ScheduleConfiguration impostor = row("user-1", Map.of());
        when(store.readScheduleByName(AlertSchedules.SCHEDULE_NAME)).thenReturn(Optional.of(impostor));
        when(store.readAllSchedules(anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(impostor, row("sys", AlertSchedules.maintenanceMetadata())));

        schedule.ensure();

        verify(store, never()).createSchedule(any());
    }

    @Test
    @DisplayName("duplicates made by nodes that started at once are removed, the oldest one is kept")
    void removesDuplicatesKeepingTheOldest() throws Exception {
        ScheduleConfiguration newer = row("b", AlertSchedules.maintenanceMetadata());
        newer.setCreatedAt(Instant.parse("2026-11-18T10:00:02Z"));
        ScheduleConfiguration oldest = row("a", AlertSchedules.maintenanceMetadata());
        oldest.setCreatedAt(Instant.parse("2026-11-18T10:00:01Z"));
        ScheduleConfiguration newest = row("c", AlertSchedules.maintenanceMetadata());
        newest.setCreatedAt(Instant.parse("2026-11-18T10:00:03Z"));
        ScheduleConfiguration other = row("user-1", null);
        when(store.readAllSchedules(anyInt(), anyInt(), anyBoolean())).thenReturn(List.of(newer, other, oldest, newest));

        schedule.removeDuplicates();

        verify(store).deleteSchedule("b");
        verify(store).deleteSchedule("c");
        verify(store, never()).deleteSchedule("a");
        verify(store, never()).deleteSchedule("user-1");
    }

    @Test
    @DisplayName("an enabled, healthy copy survives a disabled or dead-lettered older one")
    void prefersAHealthySurvivor() throws Exception {
        ScheduleConfiguration disabledOlder = row("old-disabled", AlertSchedules.maintenanceMetadata());
        disabledOlder.setCreatedAt(Instant.parse("2026-11-18T10:00:01Z"));
        disabledOlder.setEnabled(false);
        ScheduleConfiguration deadOlder = row("old-dead", AlertSchedules.maintenanceMetadata());
        deadOlder.setCreatedAt(Instant.parse("2026-11-18T10:00:02Z"));
        deadOlder.setFireStatus(ScheduleConfiguration.FireStatus.DEAD_LETTERED);
        ScheduleConfiguration healthy = row("young-healthy", AlertSchedules.maintenanceMetadata());
        healthy.setCreatedAt(Instant.parse("2026-11-18T10:00:09Z"));
        when(store.readAllSchedules(anyInt(), anyInt(), anyBoolean())).thenReturn(List.of(disabledOlder, deadOlder, healthy));

        schedule.removeDuplicates();

        verify(store).deleteSchedule("old-disabled");
        verify(store).deleteSchedule("old-dead");
        verify(store, never()).deleteSchedule("young-healthy");
    }

    @Test
    @DisplayName("a single maintenance schedule is never deleted")
    void aSingleScheduleIsKept() throws Exception {
        when(store.readAllSchedules(anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(row("only", AlertSchedules.maintenanceMetadata())));

        schedule.removeDuplicates();

        verify(store, never()).deleteSchedule(any());
    }

    @Test
    @DisplayName("the node that creates the schedule last cleans up the copy another node made")
    void creatingChecksForDuplicates() throws Exception {
        when(store.readScheduleByName(AlertSchedules.SCHEDULE_NAME)).thenReturn(Optional.empty());
        ScheduleConfiguration mine = row("mine", AlertSchedules.maintenanceMetadata());
        mine.setCreatedAt(Instant.parse("2026-11-18T10:00:02Z"));
        ScheduleConfiguration theirs = row("theirs", AlertSchedules.maintenanceMetadata());
        theirs.setCreatedAt(Instant.parse("2026-11-18T10:00:01Z"));
        when(store.readAllSchedules(anyInt(), anyInt(), anyBoolean())).thenReturn(List.of(theirs, mine));

        schedule.ensure();

        verify(store).deleteSchedule("mine");
        verify(store, never()).deleteSchedule("theirs");
    }

    @Test
    @DisplayName("the reserved name is matched trimmed and case-insensitively")
    void reservedName() {
        assertTrue(AlertSchedules.isReservedName(" Alerts-Maintenance "));
        assertEquals(false, AlertSchedules.isReservedName("alerts-maintenance-2"));
        assertEquals(false, AlertSchedules.isReservedName(null));
    }
}
