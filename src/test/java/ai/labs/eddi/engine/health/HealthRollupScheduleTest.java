/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthRollupSchedule and the erasure's dirty marks")
class HealthRollupScheduleTest {

    private IScheduleStore schedules;

    @BeforeEach
    void setUp() {
        schedules = mock(IScheduleStore.class);
    }

    private static HealthSettings every(Duration interval) {
        return new HealthSettings(true, 10, Duration.ofDays(90), Duration.ofHours(48), 10, interval, Duration.ofDays(730), Duration.ofSeconds(4),
                Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("creates the heartbeat schedule when it is missing")
    void creates() throws Exception {
        when(schedules.readScheduleByName(HealthSchedules.SCHEDULE_NAME)).thenReturn(Optional.empty());
        when(schedules.readAllSchedules(anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());
        new HealthRollupSchedule(schedules, every(Duration.ofMinutes(5))).ensure();
        ArgumentCaptor<ScheduleConfiguration> created = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(schedules).createSchedule(created.capture());
        assertEquals(300L, created.getValue().getHeartbeatIntervalSeconds());
        assertEquals(HealthSchedules.rollupMetadata(), created.getValue().getMetadata());
    }

    @Test
    @DisplayName("updates the interval of an installed schedule when eddi.health.rollup-interval changed, and leaves an unchanged one alone")
    void updatesInterval() throws Exception {
        ScheduleConfiguration installed = new ScheduleConfiguration();
        installed.setId("s1");
        installed.setName(HealthSchedules.SCHEDULE_NAME);
        installed.setMetadata(Map.of(HealthSchedules.METADATA_TYPE_KEY, HealthSchedules.METADATA_TYPE_ROLLUP));
        installed.setHeartbeatIntervalSeconds(60L);
        when(schedules.readScheduleByName(HealthSchedules.SCHEDULE_NAME)).thenReturn(Optional.of(installed));

        new HealthRollupSchedule(schedules, every(Duration.ofMinutes(10))).ensure();
        verify(schedules).updateSchedule(eq("s1"), any());
        assertEquals(600L, installed.getHeartbeatIntervalSeconds());

        new HealthRollupSchedule(schedules, every(Duration.ofMinutes(10))).ensure();
        verify(schedules, never()).createSchedule(any());
    }

    @Test
    @DisplayName("an erasure reads the user's (agent, day)s before the delete and marks them dirty")
    @SuppressWarnings("unchecked")
    void erasureMarksDays() {
        IHealthTurnStore turns = mock(IHealthTurnStore.class);
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        Instance<HealthDirtyDays> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(dirty);
        Map<String, Set<LocalDate>> days = Map.of("agent", Set.of(LocalDate.of(2026, 11, 10)));
        when(turns.agentDaysOfUser(eq("alice"), anyInt())).thenReturn(days);
        when(turns.deleteByUserId("alice")).thenReturn(3L);
        HealthTurnGdprParticipant participant = new HealthTurnGdprParticipant(turns);
        participant.dirtyDays = instance;

        assertEquals(3L, participant.erase("alice"));
        verify(dirty).noteAll(days);
    }
}
