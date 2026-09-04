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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
                "UTC", // defaultTimeZone
                Duration.ofDays(90) // fireLogRetention
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
                Optional.empty(), "UTC", Duration.ofDays(90));
        disabled.init();
        assertFalse(disabled.isEnabled());
    }

    @Test
    void init_autoDetectsHostnameIfNotConfigured() {
        var autoId = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), true, Duration.ofMinutes(5), 5, 15, 4,
                Optional.empty(), "UTC", Duration.ofDays(90));
        autoId.init();
        assertNotNull(autoId.getInstanceId());
        assertFalse(autoId.getInstanceId().isBlank());
    }

    // --- Polling ---

    @Test
    void poll_skipsWhenDisabled() throws Exception {
        var disabled = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), false, Duration.ofMinutes(5), 5, 15, 4,
                Optional.empty(), "UTC", Duration.ofDays(90));
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
        when(scheduleStore.tryClaim(eq("sched-1"), eq("test-instance"), any(), any())).thenReturn(true);
        when(fireExecutor.fire(eq(schedule), eq("test-instance"), eq(1))).thenReturn(makeFireLog("sched-1", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).tryClaim(eq("sched-1"), eq("test-instance"), any(), any());
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
        when(scheduleStore.tryClaim(eq("sched-1"), eq("test-instance"), any(), any())).thenReturn(true);
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
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(false);

        poller.pollDueSchedules();

        verify(scheduleStore).tryClaim(eq("sched-1"), eq("test-instance"), any(), any());
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
                5, 15, 4, Optional.of("test-instance"), "UTC", Duration.ofDays(90));
        shortLeasePoller.init();

        var schedule = makeCronSchedule("sched-stalled", "0 9 * * *", "Hello");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
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
    void poll_multipleStalledFires_shareOneBatchDeadline_doesNotStackWaits() throws Exception {
        // The batch wait must be bounded by ONE shared deadline (~leaseTimeout), NOT
        // leaseTimeout PER stalled future summed sequentially. With N due+claimed
        // schedules whose fire() all stall, a per-future bound would pin the poll
        // thread for up to N * leaseTimeout; the shared deadline keeps the whole
        // cycle near a single leaseTimeout regardless of N.
        var shortLeasePoller = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), true,
                Duration.ofMillis(200), // leaseTimeout — the ONE shared batch bound
                5, 15, 4, Optional.of("test-instance"), "UTC", Duration.ofDays(90));
        shortLeasePoller.init();

        var s1 = makeCronSchedule("stall-1", "0 9 * * *", "a");
        var s2 = makeCronSchedule("stall-2", "0 9 * * *", "b");
        var s3 = makeCronSchedule("stall-3", "0 9 * * *", "c");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(s1, s2, s3));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        // Every fire stalls well past the batch deadline — all three concurrently.
        when(fireExecutor.fire(any(), any(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(Duration.ofSeconds(30));
            return makeFireLog("stalled", FireStatus.COMPLETED.name());
        });

        // If the waits STACKED, this would take ~3 * 200ms + fire time; the shared
        // deadline keeps it near a single 200ms lease. 2s gives generous CI slack
        // while staying far below 30s (the real fire duration) proving the fires do
        // not run to completion serially.
        assertTimeoutPreemptively(Duration.ofSeconds(2), shortLeasePoller::pollDueSchedules,
                "multiple stalled fires must share one batch deadline, not stack to N * leaseTimeout");
    }

    @Test
    void poll_nonInterruptibleStuckFire_doesNotHangPollCycle() throws Exception {
        // Stronger than the sleep-based test above: this fire() SWALLOWS interruption
        // and keeps blocking, mimicking a synchronous DB socket read that the Mongo
        // sync driver does not abort on Thread.interrupt(). If dispatchClaimed relied
        // on try-with-resources ExecutorService.close() (which awaits termination),
        // future.cancel(true) would not free the task and the poll thread would pin
        // forever. shutdownNow() + no awaitTermination must keep the poll cycle live.
        var shortLeasePoller = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), true,
                Duration.ofMillis(200), // leaseTimeout — the per-fire bound
                5, 15, 4, Optional.of("test-instance"), "UTC", Duration.ofDays(90));
        shortLeasePoller.init();

        var schedule = makeCronSchedule("sched-wedged", "0 9 * * *", "Hello");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);

        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        when(fireExecutor.fire(any(), any(), anyInt())).thenAnswer(inv -> {
            entered.countDown();
            // Block until released, ignoring interruption entirely — the poll cycle
            // must NOT depend on this ever unblocking.
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // swallow — this is the non-interruptible case under test
                }
            }
            return makeFireLog("sched-wedged", FireStatus.COMPLETED.name());
        });

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), shortLeasePoller::pollDueSchedules,
                    "a non-interruptible stuck fire must not pin the poll thread");
            assertEquals(0, entered.getCount(), "the fire task should have started");
        } finally {
            // Let the leaked virtual thread finish so it does not linger past the test.
            release.countDown();
        }
    }

    @Test
    void poll_fireFailed_marksFailedWithBackoff() throws Exception {
        var schedule = makeCronSchedule("sched-1", "0 9 * * *", "Hello");
        schedule.setFailCount(0);
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
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
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).markDeadLettered("sched-1");
        verify(scheduleStore, never()).markFailed(any(), any());
    }

    /**
     * The residual half of the B2 interrupt work. {@code fire()} deliberately
     * re-asserts the interrupt flag before returning, so the poller ran its
     * bookkeeping on a still-interrupted thread — and {@code markFailed} is a Mongo
     * write, which the sync driver aborts with {@code MongoInterruptedException} on
     * connection checkout while that flag is set. {@code onFireFailed} swallows it,
     * so {@code failCount} never incremented: the schedule stayed CLAIMED with
     * {@code nextFire} in the past, was re-claimed on every lease expiry, and could
     * never reach {@code maxRetries} or dead-letter. An interrupt turned a failing
     * schedule into an unbounded re-fire loop.
     * <p>
     * The store stub reproduces the driver's actual interrupt sensitivity, which a
     * plain Mockito mock cannot express — and which is exactly why this went
     * unnoticed: {@code verify(markFailed)} passes either way, because under the
     * bug the call still HAPPENS, it just fails. So the assertion is on whether the
     * write COMPLETED, not on whether it was attempted.
     * <p>
     * The fire runs on the poller's own virtual thread, so this test cannot observe
     * whether the flag survives back to the caller — that half is pinned in
     * ScheduleFireExecutorTest, and this test deliberately claims no more than it
     * checks.
     */
    @Test
    void poll_fireInterrupted_stillRecordsTheFailure() throws Exception {
        var schedule = makeCronSchedule("sched-int", "0 9 * * *", "Hello");
        schedule.setFailCount(0);
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);

        // fire() returns FAILED and leaves the interrupt flag set, as it now does.
        when(fireExecutor.fire(any(), any(), anyInt())).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return makeFireLog("sched-int", FireStatus.FAILED.name());
        });

        // markFailed behaves like the sync Mongo driver: refuses to run interrupted.
        var markFailedCompleted = new AtomicBoolean(false);
        doAnswer(inv -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("interrupted during connection checkout");
            }
            markFailedCompleted.set(true);
            return null;
        }).when(scheduleStore).markFailed(any(), any());

        poller.pollDueSchedules();

        assertTrue(markFailedCompleted.get(),
                "failCount was never incremented: the schedule stays CLAIMED with nextFire in the past and re-fires forever");
        verify(scheduleStore).markFailed(eq("sched-int"), any());
    }

    // --- Heartbeat scheduling ---

    /**
     * This test used to assert {@code now + interval}, which is the DRIFT the
     * "drift-proof" contract on {@code TriggerType.HEARTBEAT} exists to rule out:
     * anchoring on the moment a fire finished pushes the cadence out by the
     * duration of every turn. {@code makeHeartbeatSchedule} arms nextFire 60s in
     * the past, so the correct answer is due + interval — 240s from now, not 300.
     */
    @Test
    void poll_heartbeat_completedAnchorsNextFireOnTheDueTime() throws Exception {
        var schedule = makeHeartbeatSchedule("hb-1", 300L, "check");
        Instant due = schedule.getNextFire();
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("hb-1", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).markCompleted(eq("hb-1"), eq(due.plusSeconds(300)));
    }

    @Test
    void poll_oneShot_completedPassesNullNextFire() throws Exception {
        var schedule = makeCronSchedule("one-1", null, "do-it");
        schedule.setOneTimeAt(Instant.now().minusSeconds(60).toString());
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
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
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenAnswer(inv -> makeFireLog(((ScheduleConfiguration) inv.getArgument(0)).getId(), FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        // Every due schedule is claimed (CAS runs on the poll thread) and fired.
        verify(scheduleStore).tryClaim(eq("s1"), any(), any(), any());
        verify(scheduleStore).tryClaim(eq("s2"), any(), any(), any());
        verify(scheduleStore).tryClaim(eq("s3"), any(), any(), any());
        verify(fireExecutor).fire(eq(s1), any(), anyInt());
        verify(fireExecutor).fire(eq(s2), any(), anyInt());
        verify(fireExecutor).fire(eq(s3), any(), anyInt());
    }

    @Test
    void poll_oneFailingFireDoesNotBlockOthers() throws Exception {
        var good = makeCronSchedule("good", "0 9 * * *", "a");
        var bad = makeCronSchedule("bad", "0 9 * * *", "b");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(bad, good));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
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
        when(scheduleStore.tryClaim(eq("mine"), any(), any(), any())).thenReturn(true);
        when(scheduleStore.tryClaim(eq("theirs"), any(), any(), any())).thenReturn(false); // lost the CAS
        when(fireExecutor.fire(eq(mine), any(), anyInt())).thenReturn(makeFireLog("mine", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        verify(fireExecutor).fire(eq(mine), any(), anyInt());
        verify(fireExecutor, never()).fire(eq(theirs), any(), anyInt());
    }

    // --- Heartbeat drift ---

    /**
     * "Drift-proof" is the documented contract of HEARTBEAT, on both this class and
     * {@code TriggerType.HEARTBEAT}. The implementation added the interval to
     * {@code Instant.now()} AFTER the fire finished, so every heartbeat drifted by
     * the duration of each turn: a 40 s turn on a 60 s heartbeat actually fired
     * every ~100 s. Anchoring on the fire time that was DUE keeps the cadence.
     */
    @Test
    void heartbeat_nextFireIsAnchoredOnTheDueTime_notOnWhenTheFireFinished() throws Exception {
        var schedule = makeHeartbeatSchedule("hb-drift", 60, "tick");
        Instant due = Instant.now().minusSeconds(20); // fired 20s late
        schedule.setNextFire(due);

        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(eq("hb-drift"), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("hb-drift", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        var nextFire = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).markCompleted(eq("hb-drift"), nextFire.capture());
        assertEquals(due.plusSeconds(60), nextFire.getValue(),
                "the cadence must be due + interval, not finish-time + interval");
    }

    /**
     * A fire that overran a whole interval must not schedule itself into the past —
     * that is a tight re-fire loop, not catching up — and the clamp that prevents
     * it must engage <em>only</em> then.
     * <p>
     * Both halves are needed, because either one alone is satisfied by code that is
     * wrong. {@code assertTrue(next.isAfter(now))} was satisfied by the pre-fix
     * {@code Instant.now().plusSeconds(interval)} as well, so it pinned nothing;
     * and the clamp's own value is deliberately identical to that pre-fix formula,
     * so asserting it alone cannot tell the two apart either. What distinguishes
     * them is the boundary: overrun by more than an interval clamps to
     * {@code now + interval}, overrun by less keeps the cadence at
     * {@code due + interval}. Assert both and the test fails whether the anchor is
     * reverted or the clamp is deleted.
     */
    @Test
    void heartbeat_overrunFire_doesNotScheduleIntoThePast() throws Exception {
        // (a) overran by ten intervals: the anchored time is long past, so the clamp
        // engages and the next fire is now + interval — never the anchored past value.
        var overrun = makeHeartbeatSchedule("hb-overrun", 60, "tick");
        overrun.setNextFire(Instant.now().minusSeconds(600));

        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(overrun));
        when(scheduleStore.tryClaim(eq("hb-overrun"), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("hb-overrun", FireStatus.COMPLETED.name()));

        Instant beforePoll = Instant.now();
        poller.pollDueSchedules();
        Instant afterPoll = Instant.now();

        var clamped = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).markCompleted(eq("hb-overrun"), clamped.capture());
        assertFalse(clamped.getValue().isBefore(beforePoll.plusSeconds(60)),
                "an overrun fire must be clamped to now + interval, not left in the past: " + clamped.getValue());
        assertFalse(clamped.getValue().isAfter(afterPoll.plusSeconds(60)),
                "an overrun fire must be clamped to now + interval, not pushed further out: " + clamped.getValue());

        // (b) overran by less than one interval: the anchored time is still in the
        // future, so the clamp must NOT engage and the cadence is kept exactly.
        reset(scheduleStore, fireExecutor);
        var late = makeHeartbeatSchedule("hb-late", 60, "tick");
        Instant due = Instant.now().minusSeconds(10);
        late.setNextFire(due);

        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(late));
        when(scheduleStore.tryClaim(eq("hb-late"), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("hb-late", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        var kept = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).markCompleted(eq("hb-late"), kept.capture());
        assertEquals(due.plusSeconds(60), kept.getValue(),
                "a fire that ran late but inside the interval keeps the cadence — the clamp is for "
                        + "overruns only, and must not quietly re-anchor every fire on now");
    }

    // --- Fire log retention ---

    /**
     * Nothing pruned eddi_schedule_fire_logs: a 60-second heartbeat writes ~525,600
     * rows a year on its own, every HITL pause adds a one-shot schedule whose log
     * outlives it, and readFailedFireLogs scans the lot.
     */
    @Test
    void pruneFireLogs_deletesLogsOlderThanTheRetentionWindow() throws Exception {
        when(scheduleStore.deleteFireLogsOlderThan(any())).thenReturn(12);

        poller.pruneFireLogs();

        var cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).deleteFireLogsOlderThan(cutoff.capture());
        assertTrue(cutoff.getValue().isBefore(Instant.now().minus(Duration.ofDays(89))), "cutoff: " + cutoff.getValue());
    }

    @Test
    void pruneFireLogs_zeroRetentionKeepsEverything() throws Exception {
        var keepAll = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), true, Duration.ofMinutes(5), 5, 15, 4,
                Optional.of("test-instance"), "UTC", Duration.ZERO);
        keepAll.init();

        keepAll.pruneFireLogs();

        verify(scheduleStore, never()).deleteFireLogsOlderThan(any());
    }

    @Test
    void pruneFireLogs_storeFailureDoesNotPropagate() throws Exception {
        when(scheduleStore.deleteFireLogsOlderThan(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> poller.pruneFireLogs());
    }

    // --- Manual fires (REST) ---

    /**
     * A manual fire must claim on the poller's own terms. Passing {@code now} as
     * the lease expiry would match the CAS clause that steals a CRASHED instance's
     * claim ({@code claimedAt <= leaseExpiry}) and so would seize the claim of a
     * fire that is still running — the opposite of what claiming is for.
     */
    @Test
    void claimForManualFire_usesTheConfiguredLeaseAsTheStealThreshold() throws Exception {
        var schedule = makeCronSchedule("manual-1", "0 9 * * *", "hi");
        when(scheduleStore.tryClaim(eq("manual-1"), any(), any(), any())).thenReturn(true);

        assertTrue(poller.claimForManualFire(schedule));

        var now = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var leaseExpiry = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).tryClaim(eq("manual-1"), eq("test-instance"), now.capture(), leaseExpiry.capture());
        assertEquals(now.getValue().minus(Duration.ofMinutes(5)), leaseExpiry.getValue(),
                "a manual fire must not be able to steal a live claim");
        assertEquals("manual-1_" + now.getValue(), schedule.getFireId(), "the in-memory copy must mirror the persisted fireId");
    }

    @Test
    void claimForManualFire_refusesWhenTheScheduleIsAlreadyClaimed() throws Exception {
        var schedule = makeCronSchedule("manual-2", "0 9 * * *", "hi");
        when(scheduleStore.tryClaim(eq("manual-2"), any(), any(), any())).thenReturn(false);

        assertFalse(poller.claimForManualFire(schedule));
    }

    @Test
    void recordManualFireOutcome_completedReArmsThroughTheSameStateMachine() throws Exception {
        var schedule = makeCronSchedule("manual-3", "0 9 * * *", "hi");

        poller.recordManualFireOutcome(schedule, makeFireLog("manual-3", FireStatus.COMPLETED.name()));

        verify(scheduleStore).markCompleted(eq("manual-3"), any());
        verify(scheduleStore, never()).markFailed(anyString(), any());
    }

    @Test
    void recordManualFireOutcome_failedRecordsAFailureSoRetryAndBackoffStillApply() throws Exception {
        var schedule = makeCronSchedule("manual-4", "0 9 * * *", "hi");

        poller.recordManualFireOutcome(schedule, makeFireLog("manual-4", FireStatus.FAILED.name()));

        verify(scheduleStore).markFailed(eq("manual-4"), any());
        verify(scheduleStore, never()).markCompleted(anyString(), any());
    }

    /**
     * A manual fire that threw returns no fire log at all — the claim must still be
     * released, or the schedule stays CLAIMED and blocks the poller until the lease
     * expires.
     */
    @Test
    void recordManualFireOutcome_nullFireLogIsTreatedAsAFailure() throws Exception {
        var schedule = makeCronSchedule("manual-5", "0 9 * * *", "hi");

        assertDoesNotThrow(() -> poller.recordManualFireOutcome(schedule, null));

        verify(scheduleStore).markFailed(eq("manual-5"), any());
    }

    /**
     * Routing a manual fire through the poller's state machine has a consequence
     * only the HEARTBEAT path shows, and nothing pinned it: re-arming uses the
     * drift-proof rule, so the next fire is anchored on the schedule's own
     * {@code nextFire} rather than on the moment the manual turn finished. Firing
     * manually while the next fire is still in the future therefore CONSUMES it and
     * moves the cadence out by one interval. That is deliberate — see
     * {@code recordManualFireOutcome}'s Javadoc — and this test is what keeps it
     * from changing by accident.
     */
    @Test
    void recordManualFireOutcome_heartbeatAnchorsTheNextFireOnTheDueTimeNotOnNow() throws Exception {
        var schedule = makeHeartbeatSchedule("manual-6", 3600, "hi");
        schedule.setNextFire(Instant.parse("2099-01-01T00:00:00Z"));

        poller.recordManualFireOutcome(schedule, makeFireLog("manual-6", FireStatus.COMPLETED.name()));

        verify(scheduleStore).markCompleted("manual-6", Instant.parse("2099-01-01T01:00:00Z"));
    }

    /**
     * The fourth consequence of routing a manual fire through this state machine,
     * and the one an operator is most likely to be surprised by: a successful
     * manual fire CONSUMES a one-shot.
     * <p>
     * {@link SchedulePollerService#computeNextFire} has no {@code oneTimeAt} branch
     * — deliberately, because {@code null} is the signal {@code markCompleted} uses
     * to disable a finished one-shot — so pressing "Fire now" on a pending one-shot
     * is the run, not a rehearsal. Pinning it here means the day someone decides a
     * manual fire should leave a one-shot armed, they have to change this test and
     * the Javadoc that documents it rather than discovering it in production.
     */
    @Test
    void recordManualFireOutcome_oneShotIsConsumed_notLeftArmed() throws Exception {
        var oneShot = new ScheduleConfiguration();
        oneShot.setId("manual-7");
        oneShot.setName("One shot");
        oneShot.setTriggerType(TriggerType.CRON);
        oneShot.setAgentId("agent-1");
        oneShot.setOneTimeAt("2099-01-01T00:00:00Z");
        oneShot.setNextFire(Instant.parse("2099-01-01T00:00:00Z"));
        oneShot.setFireStatus(FireStatus.PENDING);

        poller.recordManualFireOutcome(oneShot, makeFireLog("manual-7", FireStatus.COMPLETED.name()));

        // null nextFire is what markCompleted reads as "one-shot finished — disable
        // it".
        verify(scheduleStore).markCompleted("manual-7", null);
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
