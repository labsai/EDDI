/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HITL autonomy maintenance schedule")
class HitlAutonomyScheduleTest {

    private static ScheduleConfiguration schedule(String name, String agentId, Map<String, Object> metadata) {
        var s = new ScheduleConfiguration();
        s.setName(name);
        s.setAgentId(agentId);
        s.setMetadata(metadata);
        return s;
    }

    @Nested
    @DisplayName("HitlAutonomySchedules")
    class Markers {

        @Test
        @DisplayName("the maintenance metadata is recognised, and nothing else is")
        void isMaintenance() {
            assertTrue(HitlAutonomySchedules.isMaintenance(HitlAutonomySchedules.maintenanceMetadata()));
            assertFalse(HitlAutonomySchedules.isMaintenance(null));
            assertFalse(HitlAutonomySchedules.isMaintenance(Map.of()));
            assertFalse(HitlAutonomySchedules.isMaintenance(Map.of(HitlAutonomySchedules.METADATA_TYPE_KEY, "other")));
        }

        @Test
        @DisplayName("a client body claims the reserved schedule by marker or by name, case-insensitively and trimmed")
        void claims() {
            assertFalse(HitlAutonomySchedules.claims(null));
            assertFalse(HitlAutonomySchedules.claims(schedule(null, null, null)));
            assertFalse(HitlAutonomySchedules.claims(schedule("nightly report", null, Map.of("x", "y"))));
            assertTrue(HitlAutonomySchedules.claims(schedule("anything", null, Map.of(HitlAutonomySchedules.METADATA_TYPE_KEY, "whatever"))));
            assertTrue(HitlAutonomySchedules.claims(schedule("  HITL-Autonomy-Maintenance ", "agent-1", null)));
        }

        @Test
        @DisplayName("a stored schedule is the server's own by marker, or by the reserved name without an agent")
        void isSystem() {
            assertFalse(HitlAutonomySchedules.isSystem(null));
            assertFalse(HitlAutonomySchedules.isSystem(schedule("nightly", null, null)));
            assertFalse(HitlAutonomySchedules.isSystem(schedule(null, null, Map.of("x", "y"))));
            assertTrue(HitlAutonomySchedules.isSystem(schedule("nightly", null, HitlAutonomySchedules.maintenanceMetadata())));
            assertTrue(HitlAutonomySchedules.isSystem(schedule(HitlAutonomySchedules.SCHEDULE_NAME, null, null)));
            assertFalse(HitlAutonomySchedules.isSystem(schedule(HitlAutonomySchedules.SCHEDULE_NAME, "agent-1", null)),
                    "an agent's own schedule with that name is not the system one");
        }
    }

    @Nested
    @DisplayName("HitlAutonomyMaintenanceSchedule")
    class Installer {

        private IScheduleStore store;
        private HitlAutonomyMaintenanceSchedule installer;

        @BeforeEach
        void setUp() {
            store = mock(IScheduleStore.class);
            installer = new HitlAutonomyMaintenanceSchedule(store, new OversightSettings(Duration.ofDays(400), Duration.ofDays(90), true,
                    Duration.ofDays(180), Duration.ofHours(2), Duration.ofSeconds(15), 500));
        }

        private ScheduleConfiguration other() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("k", "v");
            return schedule("other", "a", metadata);
        }

        @Test
        @DisplayName("the schedule found by name with the marker counts as installed without scanning")
        void foundByName() throws Exception {
            when(store.readScheduleByName(HitlAutonomySchedules.SCHEDULE_NAME))
                    .thenReturn(Optional.of(schedule(HitlAutonomySchedules.SCHEDULE_NAME, null, HitlAutonomySchedules.maintenanceMetadata())));

            assertTrue(installer.installed());
            verify(store, never()).readAllSchedules(anyInt(), anyInt(), eq(true));
        }

        @Test
        @DisplayName("a same-named schedule without the marker is not enough; the scan finds a marked one on a later page")
        void foundByScan() throws Exception {
            when(store.readScheduleByName(anyString())).thenReturn(Optional.of(schedule(HitlAutonomySchedules.SCHEDULE_NAME, "agent", null)));
            List<ScheduleConfiguration> fullPage = new ArrayList<>();
            for (int i = 0; i < HitlAutonomyMaintenanceSchedule.SCAN_PAGE; i++) {
                fullPage.add(other());
            }
            when(store.readAllSchedules(HitlAutonomyMaintenanceSchedule.SCAN_PAGE, 0, true)).thenReturn(fullPage);
            when(store.readAllSchedules(HitlAutonomyMaintenanceSchedule.SCAN_PAGE, HitlAutonomyMaintenanceSchedule.SCAN_PAGE, true))
                    .thenReturn(List.of(other(), schedule("x", null, HitlAutonomySchedules.maintenanceMetadata())));

            assertTrue(installer.installed());
        }

        @Test
        @DisplayName("a short page without the marker ends the scan as not installed")
        void notFoundShortPage() throws Exception {
            when(store.readScheduleByName(anyString())).thenReturn(Optional.empty());
            when(store.readAllSchedules(anyInt(), anyInt(), eq(true))).thenReturn(List.of(other()));

            assertFalse(installer.installed());
            verify(store, times(1)).readAllSchedules(anyInt(), anyInt(), eq(true));
        }

        @Test
        @DisplayName("the scan stops at its limit when every page is full")
        void scanLimit() throws Exception {
            when(store.readScheduleByName(anyString())).thenReturn(Optional.empty());
            List<ScheduleConfiguration> fullPage = new ArrayList<>();
            for (int i = 0; i < HitlAutonomyMaintenanceSchedule.SCAN_PAGE; i++) {
                fullPage.add(other());
            }
            when(store.readAllSchedules(anyInt(), anyInt(), eq(true))).thenReturn(fullPage);

            assertFalse(installer.installed());
            verify(store, times(HitlAutonomyMaintenanceSchedule.SCAN_LIMIT / HitlAutonomyMaintenanceSchedule.SCAN_PAGE))
                    .readAllSchedules(anyInt(), anyInt(), eq(true));
        }

        @Test
        @DisplayName("ensure creates a marked heartbeat schedule at the configured interval, first firing within five minutes")
        void createsSchedule() throws Exception {
            when(store.readScheduleByName(anyString())).thenReturn(Optional.empty());
            when(store.readAllSchedules(anyInt(), anyInt(), eq(true))).thenReturn(List.of());
            Instant before = Instant.now();

            installer.onStart(mock(StartupEvent.class));

            var created = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(store).createSchedule(created.capture());
            ScheduleConfiguration s = created.getValue();
            assertEquals(HitlAutonomySchedules.SCHEDULE_NAME, s.getName());
            assertEquals(ScheduleConfiguration.TriggerType.HEARTBEAT, s.getTriggerType());
            assertEquals(7200L, s.getHeartbeatIntervalSeconds());
            assertTrue(s.isEnabled());
            assertTrue(HitlAutonomySchedules.isMaintenance(s.getMetadata()));
            assertFalse(s.getNextFire().isAfter(before.plusSeconds(301)), "capped at five minutes");
        }

        @Test
        @DisplayName("an interval under a minute is raised to a minute, and its first firing waits that long")
        void minimumInterval() throws Exception {
            var fast = new HitlAutonomyMaintenanceSchedule(store, new OversightSettings(Duration.ofDays(400), Duration.ofDays(90), true,
                    Duration.ofDays(180), Duration.ofSeconds(5), Duration.ofSeconds(15), 500));
            when(store.readScheduleByName(anyString())).thenReturn(Optional.empty());
            when(store.readAllSchedules(anyInt(), anyInt(), eq(true))).thenReturn(List.of());

            fast.ensure();

            var created = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(store).createSchedule(created.capture());
            assertEquals(60L, created.getValue().getHeartbeatIntervalSeconds());
        }

        @Test
        @DisplayName("ensure does nothing when installed, and never throws when the store fails")
        void idempotentAndSafe() throws Exception {
            when(store.readScheduleByName(anyString()))
                    .thenReturn(Optional.of(schedule(HitlAutonomySchedules.SCHEDULE_NAME, null, HitlAutonomySchedules.maintenanceMetadata())));
            installer.ensure();
            verify(store, never()).createSchedule(any());

            when(store.readScheduleByName(anyString())).thenThrow(new IResourceStore.ResourceStoreException("down"));
            installer.ensure();
            verify(store, never()).createSchedule(any());
        }
    }
}
