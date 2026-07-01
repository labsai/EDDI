/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SchedulePollerService — Extended Branch Coverage Tests")
class SchedulePollerServiceBranchTest {

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SchedulePollerService poller;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);
        poller = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(),
                true, Duration.ofMinutes(5), 3, 15, 4,
                Optional.of("test-instance"), "UTC");
        poller.init();
    }

    @Nested
    @DisplayName("init — instanceId resolution")
    class InitInstanceId {

        @Test
        @DisplayName("blank configured instanceId falls back to hostname")
        void blankConfiguredId() {
            var p = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(),
                    true, Duration.ofMinutes(5), 3, 15, 4,
                    Optional.of("   "), "UTC");
            p.init();
            assertNotNull(p.getInstanceId());
            assertFalse(p.getInstanceId().isBlank());
        }

        @Test
        @DisplayName("empty Optional falls back to hostname")
        void emptyOptional() {
            var p = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(),
                    true, Duration.ofMinutes(5), 3, 15, 4,
                    Optional.empty(), "UTC");
            p.init();
            assertNotNull(p.getInstanceId());
        }
    }

    @Nested
    @DisplayName("pollDueSchedules — error handling")
    class PollErrors {

        @Test
        @DisplayName("exception during findDueSchedules is caught")
        void findDueSchedulesException() throws Exception {
            when(scheduleStore.findDueSchedules(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("db error"));

            assertDoesNotThrow(() -> poller.pollDueSchedules());
        }
    }

    @Nested
    @DisplayName("processSchedule — exception paths")
    class ProcessScheduleExceptions {

        @Test
        @DisplayName("exception during fire triggers onFireFailed")
        void fireException() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setFailCount(0);
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt())).thenThrow(new RuntimeException("fire exploded"));

            poller.pollDueSchedules();

            verify(scheduleStore).markFailed(eq("sched-1"), any());
        }

        @Test
        @DisplayName("exception during fire + onFireFailed exception — logs both")
        void fireAndFailHandlerException() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setFailCount(0);
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt())).thenThrow(new RuntimeException("fire error"));
            doThrow(new RuntimeException("mark failed error")).when(scheduleStore).markFailed(any(), any());

            assertDoesNotThrow(() -> poller.pollDueSchedules());
        }
    }

    @Nested
    @DisplayName("onFireCompleted — markCompleted exception")
    class OnFireCompletedErrors {

        @Test
        @DisplayName("exception during markCompleted is caught")
        void markCompletedException() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));
            doThrow(new RuntimeException("mark error")).when(scheduleStore).markCompleted(any(), any());

            assertDoesNotThrow(() -> poller.pollDueSchedules());
        }
    }

    @Nested
    @DisplayName("computeNextFire")
    class ComputeNextFire {

        @Test
        @DisplayName("null triggerType defaults to CRON")
        void nullTriggerType() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setTriggerType(null);
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

            poller.pollDueSchedules();

            verify(scheduleStore).markCompleted(eq("sched-1"), any());
        }

        @Test
        @DisplayName("CRON with null cronExpression yields null nextFire")
        void cronNullExpression() throws Exception {
            var schedule = makeCronSchedule("sched-1", null);
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

            poller.pollDueSchedules();

            verify(scheduleStore).markCompleted(eq("sched-1"), isNull());
        }

        @Test
        @DisplayName("CRON with blank cronExpression yields null nextFire")
        void cronBlankExpression() throws Exception {
            var schedule = makeCronSchedule("sched-1", "   ");
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

            poller.pollDueSchedules();

            verify(scheduleStore).markCompleted(eq("sched-1"), isNull());
        }

        @Test
        @DisplayName("HEARTBEAT with null interval and no cron yields null")
        void heartbeatNullInterval() throws Exception {
            var schedule = makeHeartbeatSchedule("hb-1", null);
            schedule.setCronExpression(null);
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("hb-1", FireStatus.COMPLETED.name()));

            poller.pollDueSchedules();

            verify(scheduleStore).markCompleted(eq("hb-1"), isNull());
        }

        @Test
        @DisplayName("HEARTBEAT with zero interval and cron set — uses cron")
        void heartbeatZeroIntervalWithCron() throws Exception {
            var schedule = makeHeartbeatSchedule("hb-1", 0L);
            schedule.setCronExpression("0 9 * * *");
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("hb-1", FireStatus.COMPLETED.name()));

            poller.pollDueSchedules();

            // nextFire should be non-null (from cron parser)
            verify(scheduleStore).markCompleted(eq("hb-1"), argThat(next -> next != null));
        }

        @Test
        @DisplayName("HEARTBEAT with negative interval and blank cron yields null")
        void heartbeatNegativeInterval() throws Exception {
            var schedule = makeHeartbeatSchedule("hb-1", -5L);
            schedule.setCronExpression("  ");
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("hb-1", FireStatus.COMPLETED.name()));

            poller.pollDueSchedules();

            verify(scheduleStore).markCompleted(eq("hb-1"), isNull());
        }
    }

    @Nested
    @DisplayName("resolveTimeZone")
    class ResolveTimeZone {

        @Test
        @DisplayName("invalid time zone falls back to default")
        void invalidTimezone() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setTimeZone("Invalid/Zone");
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

            assertDoesNotThrow(() -> poller.pollDueSchedules());
        }

        @Test
        @DisplayName("null time zone uses default")
        void nullTimezone() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setTimeZone(null);
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

            assertDoesNotThrow(() -> poller.pollDueSchedules());
        }

        @Test
        @DisplayName("blank time zone uses default")
        void blankTimezone() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setTimeZone("   ");
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

            assertDoesNotThrow(() -> poller.pollDueSchedules());
        }
    }

    @Nested
    @DisplayName("onFireFailed — backoff and dead-letter")
    class OnFireFailed {

        @Test
        @DisplayName("exception during onFireFailed handling is caught")
        void failHandlerException() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setFailCount(0);
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));
            doThrow(new RuntimeException("mark error")).when(scheduleStore).markFailed(any(), any());

            assertDoesNotThrow(() -> poller.pollDueSchedules());
        }

        @Test
        @DisplayName("failCount >= maxRetries dead-letters the schedule")
        void deadLetter() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setFailCount(2); // maxRetries=3, newFailCount=3 → dead-letter
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));

            poller.pollDueSchedules();

            verify(scheduleStore).markDeadLettered("sched-1");
            verify(scheduleStore, never()).markFailed(any(), any());
        }

        @Test
        @DisplayName("failCount < maxRetries marks failed with backoff")
        void exponentialBackoff() throws Exception {
            var schedule = makeCronSchedule("sched-1", "0 9 * * *");
            schedule.setFailCount(1); // newFailCount=2 < 3
            when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
            when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));

            poller.pollDueSchedules();

            verify(scheduleStore).markFailed(eq("sched-1"), any());
            verify(scheduleStore, never()).markDeadLettered(any());
        }
    }

    // --- Helpers ---

    private static ScheduleConfiguration makeCronSchedule(String id, String cron) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test Schedule");
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setCronExpression(cron);
        s.setMessage("test");
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        s.setNextFire(Instant.now().minusSeconds(60));
        return s;
    }

    private static ScheduleConfiguration makeHeartbeatSchedule(String id, Long intervalSec) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test Heartbeat");
        s.setTriggerType(TriggerType.HEARTBEAT);
        s.setAgentId("agent-1");
        s.setHeartbeatIntervalSeconds(intervalSec);
        s.setMessage("test");
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        s.setNextFire(Instant.now().minusSeconds(60));
        return s;
    }

    private static ScheduleFireLog makeFireLog(String scheduleId, String status) {
        return new ScheduleFireLog("log-1", scheduleId, "fire-1", Instant.now(), Instant.now(), Instant.now(),
                status, "test-instance", "conv-1", null, 1, 0.0);
    }
}
