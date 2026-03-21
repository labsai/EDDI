package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.schedule.IScheduleStore;
import ai.labs.eddi.configs.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.configs.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.configs.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.configs.schedule.model.ScheduleFireLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SchedulePollerService}.
 * Tests the poll → claim → fire → complete/fail/dead-letter flow.
 */
class SchedulePollerServiceTest {

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SchedulePollerService poller;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);

        poller = new SchedulePollerService(
                scheduleStore,
                fireExecutor,
                new SimpleMeterRegistry(),
                true,                    // enabled
                Duration.ofMinutes(5),   // leaseTimeout
                5,                       // maxRetries
                15,                      // backoffBaseSeconds
                4,                       // backoffMultiplier
                "test-instance",         // instanceId
                "UTC",                   // defaultTimeZone
                60                       // minIntervalSeconds
        );
        poller.init();
    }

    // --- Constructor / Init ---

    @Test
    void init_setsInstanceId() {
        assertEquals("test-instance", poller.getInstanceId());
    }

    @Test
    void init_isEnabled() {
        assertTrue(poller.isEnabled());
    }

    @Test
    void init_disabledScheduler() {
        var disabled = new SchedulePollerService(
                scheduleStore, fireExecutor, new SimpleMeterRegistry(),
                false, Duration.ofMinutes(5), 5, 15, 4, "", "UTC", 60);
        disabled.init();
        assertFalse(disabled.isEnabled());
    }

    @Test
    void init_autoDetectsHostnameIfNotConfigured() {
        var autoId = new SchedulePollerService(
                scheduleStore, fireExecutor, new SimpleMeterRegistry(),
                true, Duration.ofMinutes(5), 5, 15, 4, "", "UTC", 60);
        autoId.init();
        assertNotNull(autoId.getInstanceId());
        assertFalse(autoId.getInstanceId().isBlank());
    }

    // --- Polling ---

    @Test
    void poll_skipsWhenDisabled() throws Exception {
        var disabled = new SchedulePollerService(
                scheduleStore, fireExecutor, new SimpleMeterRegistry(),
                false, Duration.ofMinutes(5), 5, 15, 4, "", "UTC", 60);
        disabled.init();

        disabled.pollDueSchedules();

        verifyNoInteractions(scheduleStore);
        verifyNoInteractions(fireExecutor);
    }

    @Test
    void poll_noDueSchedules() throws Exception {
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of());

        poller.pollDueSchedules();

        verify(scheduleStore).findDueSchedules(any(), any(), eq(5));
        verifyNoInteractions(fireExecutor);
    }

    @Test
    void poll_claimAndFire_cron_success() throws Exception {
        var schedule = makeCronSchedule("sched-1", "0 9 * * *", "Hello");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(eq("sched-1"), eq("test-instance"), any())).thenReturn(true);
        when(fireExecutor.fire(eq(schedule), eq("test-instance"), eq(1)))
                .thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).tryClaim(eq("sched-1"), eq("test-instance"), any());
        verify(fireExecutor).fire(eq(schedule), eq("test-instance"), eq(1));
        verify(scheduleStore).markCompleted(eq("sched-1"), any()); // nextFire recomputed
    }

    @Test
    void poll_claimConflict_skips() throws Exception {
        var schedule = makeCronSchedule("sched-1", "0 9 * * *", "Hello");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(false);

        poller.pollDueSchedules();

        verify(scheduleStore).tryClaim(eq("sched-1"), eq("test-instance"), any());
        verifyNoInteractions(fireExecutor);
    }

    @Test
    void poll_fireFailed_marksFailedWithBackoff() throws Exception {
        var schedule = makeCronSchedule("sched-1", "0 9 * * *", "Hello");
        schedule.setFailCount(0);
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));

        poller.pollDueSchedules();

        // Should mark failed with exponential backoff: 15 * 4^0 = 15 seconds
        verify(scheduleStore).markFailed(eq("sched-1"), any());
        verify(scheduleStore, never()).markDeadLettered(any());
    }

    @Test
    void poll_fireFailed_deadLettersAfterMaxRetries() throws Exception {
        var schedule = makeCronSchedule("sched-1", "0 9 * * *", "Hello");
        schedule.setFailCount(4); // 4 previous failures, this is attempt 5 = maxRetries
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).markDeadLettered("sched-1");
        verify(scheduleStore, never()).markFailed(any(), any());
    }

    // --- Heartbeat scheduling ---

    @Test
    void poll_heartbeat_completedSetsIntervalBasedNextFire() throws Exception {
        var schedule = makeHeartbeatSchedule("hb-1", 300L, "check");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(makeFireLog("hb-1", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        // Should pass a non-null nextFire to markCompleted (now + 300s)
        verify(scheduleStore).markCompleted(eq("hb-1"), argThat(nextFire -> {
            assertNotNull(nextFire);
            long diff = Duration.between(Instant.now(), nextFire).getSeconds();
            return diff > 295 && diff <= 305; // ~300 seconds from now
        }));
    }

    @Test
    void poll_oneShot_completedPassesNullNextFire() throws Exception {
        var schedule = makeCronSchedule("one-1", null, "do-it");
        schedule.setOneTimeAt(Instant.now().minusSeconds(60).toString());
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(makeFireLog("one-1", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        // One-shot: null nextFire → MongoScheduleStore disables automatically
        verify(scheduleStore).markCompleted(eq("one-1"), isNull());
    }

    // --- Helpers ---

    private static ScheduleConfiguration makeCronSchedule(String id, String cron, String message) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test Schedule");
        s.setTriggerType(TriggerType.CRON);
        s.setBotId("bot-1");
        s.setCronExpression(cron);
        s.setMessage(message);
        s.setEnvironment("unrestricted");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        s.setNextFire(Instant.now().minusSeconds(60));
        return s;
    }

    private static ScheduleConfiguration makeHeartbeatSchedule(String id, long intervalSec, String message) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test Heartbeat");
        s.setTriggerType(TriggerType.HEARTBEAT);
        s.setBotId("bot-1");
        s.setHeartbeatIntervalSeconds(intervalSec);
        s.setMessage(message);
        s.setEnvironment("unrestricted");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        s.setNextFire(Instant.now().minusSeconds(60));
        return s;
    }

    private static ScheduleFireLog makeFireLog(String scheduleId, String status) {
        return new ScheduleFireLog(
                "log-1", scheduleId, "fire-1",
                Instant.now(), Instant.now(), Instant.now(),
                status, "test-instance", "conv-1",
                null, 1, 0.0
        );
    }
}
