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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SchedulePollerService}. Tests the poll → claim → fire →
 * complete/fail/dead-letter flow.
 */
class SchedulePollerServiceTest {

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SchedulePollerService poller;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);

        poller = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), true, // enabled
                Duration.ofMinutes(5), // leaseTimeout
                5, // maxRetries
                15, // backoffBaseSeconds
                4, // backoffMultiplier
                Optional.of("test-instance"), // instanceId
                "UTC" // defaultTimeZone
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
        var disabled = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), false, Duration.ofMinutes(5), 5, 15, 4,
                Optional.empty(), "UTC");
        disabled.init();
        assertFalse(disabled.isEnabled());
    }

    @Test
    void init_autoDetectsHostnameIfNotConfigured() {
        var autoId = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), true, Duration.ofMinutes(5), 5, 15, 4,
                Optional.empty(), "UTC");
        autoId.init();
        assertNotNull(autoId.getInstanceId());
        assertFalse(autoId.getInstanceId().isBlank());
    }

    // --- Polling ---

    @Test
    void poll_skipsWhenDisabled() throws Exception {
        var disabled = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), false, Duration.ofMinutes(5), 5, 15, 4,
                Optional.empty(), "UTC");
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
        when(fireExecutor.fire(eq(schedule), eq("test-instance"), eq(1))).thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).tryClaim(eq("sched-1"), eq("test-instance"), any());
        verify(fireExecutor).fire(eq(schedule), eq("test-instance"), eq(1));
        verify(scheduleStore).markCompleted(eq("sched-1"), any()); // nextFire recomputed
    }

    @Test
    void poll_claimSuccess_populatesFireIdOnInMemorySchedule() throws Exception {
        // tryClaim() only returns a boolean; it does not hand back the fireId it
        // persisted. The in-memory schedule handed to fireExecutor.fire() must carry
        // the SAME fireId the store just wrote (scheduleId + "_" + now), or fire-log
        // correlation and the agent-context fireId are wrong for every claimed fire.
        var schedule = makeCronSchedule("sched-1", "0 9 * * *", "Hello");
        assertNull(schedule.getFireId(), "fireId must be unset before claiming");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(eq("sched-1"), eq("test-instance"), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        var scheduleCaptor = org.mockito.ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(fireExecutor).fire(scheduleCaptor.capture(), eq("test-instance"), eq(1));
        String firedFireId = scheduleCaptor.getValue().getFireId();
        assertNotNull(firedFireId, "fireId must be populated after a successful claim");
        assertTrue(firedFireId.startsWith("sched-1_"), "fireId must be derived from the claimed schedule id");
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
    void poll_stalledFire_doesNotHangPollCycle() throws Exception {
        // dispatchClaimed() must bound its wait on each fire: a stalled downstream
        // call must not block this poll cycle forever, or this instance would stop
        // claiming/firing ANY schedule until the process is restarted. The wait is
        // bounded by leaseTimeout — use a short one so the test itself stays fast.
        var shortLeasePoller = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), true,
                Duration.ofMillis(200), // leaseTimeout — the bound under test
                5, 15, 4, Optional.of("test-instance"), "UTC");
        shortLeasePoller.init();

        var schedule = makeCronSchedule("sched-stalled", "0 9 * * *", "Hello");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
        // Simulate a downstream call that never returns within the poll cycle.
        when(fireExecutor.fire(any(), any(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(Duration.ofSeconds(30));
            return makeFireLog("sched-stalled", FireStatus.COMPLETED.name());
        });

        long start = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(5), shortLeasePoller::pollDueSchedules,
                "a stalled fire task must not hang the poll cycle beyond the lease timeout");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5000, "poll cycle took " + elapsedMs + "ms — expected it to return around the 200ms lease timeout");
    }

    @Test
    void poll_fireFailed_marksFailedWithBackoff() throws Exception {
        var schedule = makeCronSchedule("sched-1", "0 9 * * *", "Hello");
        schedule.setFailCount(0);
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));

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
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));

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
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("hb-1", FireStatus.COMPLETED.name()));

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
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("one-1", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        // One-shot: null nextFire → MongoScheduleStore disables automatically
        verify(scheduleStore).markCompleted(eq("one-1"), isNull());
    }

    // --- Finding #17: concurrent dispatch + error isolation ---

    @Test
    void poll_claimsAllBeforeDispatch_andFiresEachClaimed() throws Exception {
        var s1 = makeCronSchedule("s1", "0 9 * * *", "a");
        var s2 = makeCronSchedule("s2", "0 9 * * *", "b");
        var s3 = makeCronSchedule("s3", "0 9 * * *", "c");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(s1, s2, s3));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenAnswer(inv -> makeFireLog(((ScheduleConfiguration) inv.getArgument(0)).getId(), FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        // Every due schedule is claimed (CAS runs on the poll thread) and fired.
        verify(scheduleStore).tryClaim(eq("s1"), any(), any());
        verify(scheduleStore).tryClaim(eq("s2"), any(), any());
        verify(scheduleStore).tryClaim(eq("s3"), any(), any());
        verify(fireExecutor).fire(eq(s1), any(), anyInt());
        verify(fireExecutor).fire(eq(s2), any(), anyInt());
        verify(fireExecutor).fire(eq(s3), any(), anyInt());
    }

    @Test
    void poll_oneFailingFireDoesNotBlockOthers() throws Exception {
        var good = makeCronSchedule("good", "0 9 * * *", "a");
        var bad = makeCronSchedule("bad", "0 9 * * *", "b");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(bad, good));
        when(scheduleStore.tryClaim(any(), any(), any())).thenReturn(true);
        // The "bad" fire throws; the "good" fire must still complete.
        when(fireExecutor.fire(eq(bad), any(), anyInt())).thenThrow(new RuntimeException("boom"));
        when(fireExecutor.fire(eq(good), any(), anyInt())).thenReturn(makeFireLog("good", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        // good completes; bad is marked failed via the per-fire error isolation path.
        verify(scheduleStore).markCompleted(eq("good"), any());
        verify(scheduleStore).markFailed(eq("bad"), any());
    }

    @Test
    void poll_onlyClaimedSchedulesAreFired() throws Exception {
        var mine = makeCronSchedule("mine", "0 9 * * *", "a");
        var theirs = makeCronSchedule("theirs", "0 9 * * *", "b");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(mine, theirs));
        when(scheduleStore.tryClaim(eq("mine"), any(), any())).thenReturn(true);
        when(scheduleStore.tryClaim(eq("theirs"), any(), any())).thenReturn(false); // lost the CAS
        when(fireExecutor.fire(eq(mine), any(), anyInt())).thenReturn(makeFireLog("mine", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        verify(fireExecutor).fire(eq(mine), any(), anyInt());
        verify(fireExecutor, never()).fire(eq(theirs), any(), anyInt());
    }

    // --- Helpers ---

    private static ScheduleConfiguration makeCronSchedule(String id, String cron, String message) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test Schedule");
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setCronExpression(cron);
        s.setMessage(message);
        s.setEnvironment("production");
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
        s.setAgentId("agent-1");
        s.setHeartbeatIntervalSeconds(intervalSec);
        s.setMessage(message);
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        s.setNextFire(Instant.now().minusSeconds(60));
        return s;
    }

    private static ScheduleFireLog makeFireLog(String scheduleId, String status) {
        return new ScheduleFireLog("log-1", scheduleId, "fire-1", Instant.now(), Instant.now(), Instant.now(), status, "test-instance", "conv-1",
                null, 1, 0.0);
    }
}
