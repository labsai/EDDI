/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private SimpleMeterRegistry meterRegistry;
    private SchedulePollerService poller;

    /**
     * {@code eddi.schedule.claim.conflict} is documented to operators as "instances
     * racing for the same schedule", so WHICH paths bump it is part of the
     * contract, not an implementation detail. The counter is therefore asserted
     * wherever a claim is refused or fails.
     */
    private double claimConflicts() {
        return meterRegistry.counter("eddi.schedule.claim.conflict").count();
    }

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);
        meterRegistry = new SimpleMeterRegistry();

        poller = new SchedulePollerService(scheduleStore, fireExecutor, meterRegistry, true, // enabled
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
        verify(scheduleStore).markCompleted(eq("sched-1"), any(), any()); // nextFire recomputed
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

        var scheduleCaptor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
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
        verify(scheduleStore).markFailed(eq("sched-1"), any(), any());
        verify(scheduleStore, never()).markDeadLettered(any(), any());
    }

    @Test
    void poll_fireFailed_deadLettersAfterMaxRetries() throws Exception {
        var schedule = makeCronSchedule("sched-1", "0 9 * * *", "Hello");
        schedule.setFailCount(4); // 4 previous failures, this is attempt 5 = maxRetries
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-1", FireStatus.FAILED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).markDeadLettered(eq("sched-1"), any());
        verify(scheduleStore, never()).markFailed(any(), any(), any());
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
        }).when(scheduleStore).markFailed(any(), any(), any());

        poller.pollDueSchedules();

        assertTrue(markFailedCompleted.get(),
                "failCount was never incremented: the schedule stays CLAIMED with nextFire in the past and re-fires forever");
        verify(scheduleStore).markFailed(eq("sched-int"), any(), any());
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

        verify(scheduleStore).markCompleted(eq("hb-1"), any(), eq(due.plusSeconds(300)));
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
        verify(scheduleStore).markCompleted(eq("one-1"), any(), isNull());
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
        verify(scheduleStore).markCompleted(eq("good"), any(), any());
        verify(scheduleStore).markFailed(eq("bad"), any(), any());
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

        var nextFire = ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).markCompleted(eq("hb-drift"), any(), nextFire.capture());
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

        var clamped = ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).markCompleted(eq("hb-overrun"), any(), clamped.capture());
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

        var kept = ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).markCompleted(eq("hb-late"), any(), kept.capture());
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

        var cutoff = ArgumentCaptor.forClass(Instant.class);
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

    /**
     * {@code eddi.schedule.enabled=false} switches the whole poller off, and the
     * retention sweep is part of the poller. An operator who disabled scheduling on
     * an instance — the usual reason being that another instance owns it — must not
     * find this one still deleting rows from the shared fire-log table.
     */
    @Test
    void pruneFireLogs_disabledPollerDoesNotPrune() throws Exception {
        var disabled = pollerWithRetention(false, Duration.ofDays(90));

        disabled.pruneFireLogs();

        verify(scheduleStore, never()).deleteFireLogsOlderThan(any());
    }

    /**
     * A negative retention is the same "keep everything" instruction zero is, and
     * has to be treated as one: subtracting it would put the cutoff in the FUTURE
     * and delete the entire fire log, including the rows written moments ago.
     */
    @Test
    void pruneFireLogs_negativeRetentionKeepsEverything() throws Exception {
        var keepAll = pollerWithRetention(true, Duration.ofDays(-1));

        keepAll.pruneFireLogs();

        verify(scheduleStore, never()).deleteFireLogsOlderThan(any());
    }

    @Test
    void pruneFireLogs_unsetRetentionKeepsEverything() throws Exception {
        var keepAll = pollerWithRetention(true, null);

        keepAll.pruneFireLogs();

        verify(scheduleStore, never()).deleteFireLogsOlderThan(any());
    }

    /**
     * The metric counts ROWS, and it is what tells an operator whether retention is
     * working at all. A sweep that deleted nothing must leave it alone, or a flat
     * table and a rising counter would say the opposite of the truth.
     */
    @Test
    void pruneFireLogs_countsDeletedRowsAndOnlyWhenSomethingWasDeleted() throws Exception {
        var registry = new SimpleMeterRegistry();
        var counting = new SchedulePollerService(scheduleStore, fireExecutor, registry, true, Duration.ofMinutes(5), 5, 15, 4,
                Optional.of("test-instance"), "UTC", Duration.ofDays(90));
        counting.init();

        when(scheduleStore.deleteFireLogsOlderThan(any())).thenReturn(0);
        counting.pruneFireLogs();
        assertEquals(0.0, registry.counter("eddi.schedule.firelog.pruned").count(),
                "an empty sweep must not report pruned rows");

        when(scheduleStore.deleteFireLogsOlderThan(any())).thenReturn(12);
        counting.pruneFireLogs();
        assertEquals(12.0, registry.counter("eddi.schedule.firelog.pruned").count(),
                "the counter measures rows removed, not sweeps run");
    }

    private SchedulePollerService pollerWithRetention(boolean enabled, Duration retention) {
        var service = new SchedulePollerService(scheduleStore, fireExecutor, new SimpleMeterRegistry(), enabled, Duration.ofMinutes(5), 5,
                15, 4, Optional.of("test-instance"), "UTC", retention);
        service.init();
        return service;
    }

    /**
     * A skip re-arms from the schedule's own cadence when there is no due time to
     * keep. {@code nextFire} is null on a row that has never been armed, and
     * treating null as "already due" would hand {@code markSkipped} a null instant
     * — which both stores write as a null {@code nextFire}, i.e. a schedule
     * {@code findDueSchedules} can never match again.
     */
    @Test
    void poll_skippedCronWithNoDueTime_reArmsFromTheCronCadence() throws Exception {
        var schedule = makeCronSchedule("sched-skip-6", "0 9 * * *", "hi");
        schedule.setNextFire(null);
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-skip-6", FireStatus.SKIPPED.name()));

        poller.pollDueSchedules();

        var nextFire = ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).markSkipped(eq("sched-skip-6"), any(), nextFire.capture());
        assertNotNull(nextFire.getValue(), "a null nextFire would strand the schedule permanently");
        assertTrue(nextFire.getValue().isAfter(Instant.now()), "re-armed into the past: " + nextFire.getValue());
    }

    @Test
    void poll_skippedHeartbeatWithNoDueTime_anchorsOnNowPlusTheInterval() throws Exception {
        var schedule = makeHeartbeatSchedule("sched-skip-7", 3600, "heartbeat");
        schedule.setNextFire(null);
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-skip-7", FireStatus.SKIPPED.name()));

        Instant before = Instant.now();
        poller.pollDueSchedules();

        var nextFire = ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).markSkipped(eq("sched-skip-7"), any(), nextFire.capture());
        assertFalse(nextFire.getValue().isBefore(before.plusSeconds(3600)),
                "with no due time to anchor on, the interval runs from now: " + nextFire.getValue());
        assertTrue(nextFire.getValue().isBefore(Instant.now().plusSeconds(3660)), "nextFire: " + nextFire.getValue());
    }

    /**
     * A store failure while re-arming a skip must not escalate. The schedule stays
     * CLAIMED and becomes reclaimable when its lease expires; turning the failure
     * into a FAILED/dead-letter path instead would punish a healthy schedule for a
     * database blip, which is exactly what routing skips away from
     * {@code onFireFailed} exists to prevent.
     */
    @Test
    void poll_skipReArmFailure_isSwallowedAndNeverCountsAsAFailure() throws Exception {
        var schedule = makeHeartbeatSchedule("sched-skip-8", 3600, "heartbeat");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-skip-8", FireStatus.SKIPPED.name()));
        doThrow(new IResourceStore.ResourceStoreException("db down")).when(scheduleStore).markSkipped(any(), any(), any());

        assertDoesNotThrow(() -> poller.pollDueSchedules());

        verify(scheduleStore, never()).markFailed(any(), any(), any());
        verify(scheduleStore, never()).markDeadLettered(any(), any());
        verify(scheduleStore, never()).markCompleted(any(), any(), any());
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

        var now = ArgumentCaptor.forClass(Instant.class);
        var leaseExpiry = ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).tryClaim(eq("manual-1"), eq("test-instance"), now.capture(), leaseExpiry.capture());
        assertEquals(now.getValue().minus(Duration.ofMinutes(5)), leaseExpiry.getValue(),
                "a manual fire must not be able to steal a live claim");
        assertEquals("manual-1_" + now.getValue(), schedule.getFireId(), "the in-memory copy must mirror the persisted fireId");
    }

    /**
     * A refused manual claim is not cluster contention, and the counter is the
     * reason the two callers of the CAS claim are deliberately different methods:
     * {@code eddi.schedule.claim.conflict} is documented as "instances racing for
     * the same schedule", and a manual fire is refused for states the poller never
     * even fetches (dead-lettered, or FAILED still inside its backoff). Moving the
     * increment down into the shared {@code tryClaimFor} would make the metric
     * react to an operator pressing "Fire now".
     */
    @Test
    void claimForManualFire_refusesWithoutClaimingClusterContention() throws Exception {
        var schedule = makeCronSchedule("manual-2", "0 9 * * *", "hi");
        when(scheduleStore.tryClaim(eq("manual-2"), any(), any(), any())).thenReturn(false);

        assertFalse(poller.claimForManualFire(schedule));

        assertEquals(0.0, claimConflicts(),
                "an operator pressing Fire now on an unclaimable schedule is not two instances racing");
    }

    /**
     * A store failure is not a claim conflict.
     * <p>
     * The shared claim helper caught every exception and returned false, so a
     * database blip reached the operator as a 409 "already being fired (claimed by
     * another instance or the poller)" — a statement about the cluster that nothing
     * had checked — and bumped {@code eddi.schedule.claim.conflict}, the metric
     * operators are told means instances racing for the same schedule. Propagating
     * lets {@code fireNow}'s existing handler answer 500, which is what happened.
     */
    @Test
    void claimForManualFire_propagatesAStoreFailureInsteadOfReportingAConflict() throws Exception {
        var schedule = makeCronSchedule("manual-8", "0 9 * * *", "hi");
        when(scheduleStore.tryClaim(eq("manual-8"), any(), any(), any()))
                .thenThrow(new IResourceStore.ResourceStoreException("db down"));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> poller.claimForManualFire(schedule));

        assertEquals(0.0, claimConflicts(),
                "a database blip must not be recorded as instances racing for the schedule");
    }

    /**
     * The POLLER keeps swallowing a store failure — one unclaimable schedule must
     * never abort the rest of the batch — which is why the two callers of the CAS
     * claim are deliberately different methods.
     * <p>
     * Swallowing it is not the same as calling it a conflict, though. A database
     * blip leaves {@code eddi.schedule.claim.conflict} alone on this path too: the
     * counter answers "how often did two instances race", and nobody checked the
     * cluster here.
     */
    @Test
    void poll_stillTreatsAStoreFailureAsAnUnclaimedScheduleWithoutAbortingTheBatch() throws Exception {
        var failing = makeCronSchedule("batch-1", "0 9 * * *", "hi");
        var claimable = makeCronSchedule("batch-2", "0 9 * * *", "hi");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(failing, claimable));
        when(scheduleStore.tryClaim(eq("batch-1"), any(), any(), any()))
                .thenThrow(new IResourceStore.ResourceStoreException("db down"));
        when(scheduleStore.tryClaim(eq("batch-2"), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("batch-2", FireStatus.COMPLETED.name()));

        assertDoesNotThrow(() -> poller.pollDueSchedules());

        verify(fireExecutor, never()).fire(argThat(s -> "batch-1".equals(s.getId())), any(), anyInt());
        verify(fireExecutor).fire(argThat(s -> "batch-2".equals(s.getId())), any(), anyInt());
        assertEquals(0.0, claimConflicts(), "a store error is not a claim conflict, on either path");
    }

    /**
     * The other half of the same contract: a CAS claim that a peer genuinely won IS
     * what the metric counts, and it is counted on the poll path — where "another
     * instance got it" is actually true.
     */
    @Test
    void poll_aLostCasClaimIsTheOneThingCountedAsAClaimConflict() throws Exception {
        var contested = makeCronSchedule("contested-1", "0 9 * * *", "hi");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(contested));
        when(scheduleStore.tryClaim(eq("contested-1"), any(), any(), any())).thenReturn(false);

        poller.pollDueSchedules();

        verifyNoInteractions(fireExecutor);
        assertEquals(1.0, claimConflicts(), "a lost CAS claim on the poll path is exactly what the metric means");
    }

    @Test
    void recordManualFireOutcome_completedReArmsThroughTheSameStateMachine() throws Exception {
        var schedule = makeCronSchedule("manual-3", "0 9 * * *", "hi");

        poller.recordManualFireOutcome(schedule, makeFireLog("manual-3", FireStatus.COMPLETED.name()));

        verify(scheduleStore).markCompleted(eq("manual-3"), any(), any());
        verify(scheduleStore, never()).markFailed(anyString(), any(), any());
    }

    @Test
    void recordManualFireOutcome_failedRecordsAFailureSoRetryAndBackoffStillApply() throws Exception {
        var schedule = makeCronSchedule("manual-4", "0 9 * * *", "hi");

        poller.recordManualFireOutcome(schedule, makeFireLog("manual-4", FireStatus.FAILED.name()));

        verify(scheduleStore).markFailed(eq("manual-4"), any(), any());
        verify(scheduleStore, never()).markCompleted(anyString(), any(), any());
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

        verify(scheduleStore).markFailed(eq("manual-5"), any(), any());
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

        verify(scheduleStore).markCompleted(eq("manual-6"), any(), eq(Instant.parse("2099-01-01T01:00:00Z")));
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
        verify(scheduleStore).markCompleted(eq("manual-7"), any(), isNull());
    }

    // --- Skipped fires (a dropped turn is not a failed one) ---

    /**
     * The regression that made recording a skip as FAILED worse than the bug it
     * fixed.
     * <p>
     * A HEARTBEAT defaults to {@code conversationStrategy=persistent}, so while its
     * conversation is paused on a HITL approval — or while a human is simply
     * chatting in it — EVERY fire is skipped. Fed to {@code onFireFailed} those
     * skips accumulate failCount, and with the defaults (max-retries 5, backoff
     * 15s×4^(n-1)) the fifth one DEAD_LETTERS the schedule about 21 minutes into a
     * pause that HITL approval timeouts routinely allow to run for hours. The
     * cadence was then dead until an operator posted /retry, and
     * {@code eddi.schedule.fire.deadlettered} — documented "alert on any increase"
     * — fired for a perfectly healthy schedule.
     */
    @Test
    void poll_consecutiveSkips_neverDeadLetterTheSchedule() throws Exception {
        var schedule = makeHeartbeatSchedule("sched-skip", 60, "heartbeat");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-skip", FireStatus.SKIPPED.name()));

        // Ten consecutive skips — twice max-retries. The schedule object keeps its
        // failCount across them because nothing may increment it.
        for (int i = 0; i < 10; i++) {
            poller.pollDueSchedules();
        }

        verify(scheduleStore, never()).markDeadLettered(any(), any());
        verify(scheduleStore, never()).markFailed(any(), any(), any());
        verify(scheduleStore, times(10)).markSkipped(eq("sched-skip"), any(), any());
        assertEquals(0, schedule.getFailCount(), "a skip must not count as a failed attempt");
    }

    /**
     * A skip must not CLEAR the failure state either. Routing it through
     * {@code markCompleted} would have been the cheap fix, but a schedule that
     * alternates between failing and being skipped — a wedged conversation makes
     * exactly that shape — would then reset its failCount on every other fire and
     * could never reach max-retries.
     */
    @Test
    void poll_skippedFire_doesNotClearAnExistingFailureCount() throws Exception {
        var schedule = makeHeartbeatSchedule("sched-skip-2", 60, "heartbeat");
        schedule.setFailCount(3);
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-skip-2", FireStatus.SKIPPED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore, never()).markCompleted(any(), any(), any());
        verify(scheduleStore).markSkipped(eq("sched-skip-2"), any(), any());
    }

    /**
     * A skipped heartbeat whose due time has ARRIVED — every polled skip, by
     * definition, since {@code findDueSchedules} filters on {@code nextFire <= now}
     * — is re-armed on the drift-proof anchor, like any other: due + interval, not
     * now + interval.
     */
    @Test
    void poll_skippedHeartbeat_isReArmedOnTheDueTimeAnchor() throws Exception {
        var schedule = makeHeartbeatSchedule("sched-skip-3", 3600, "heartbeat");
        Instant due = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        schedule.setNextFire(due);
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-skip-3", FireStatus.SKIPPED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).markSkipped(eq("sched-skip-3"), any(), eq(due.plusSeconds(3600)));
    }

    /**
     * The counterpart, and the reason the anchor alone is not enough: a due time
     * that has NOT arrived must survive the skip untouched.
     * <p>
     * Only the poller is guaranteed to fire an overdue schedule — {@code tryClaim}
     * has no {@code nextFire <= now} guard, so {@code POST /{id}/fire} claims at
     * any time. Rolling the anchor forward there would take an operator's "fire
     * now" on a daily heartbeat at 09:00, have the coordinator skip it because a
     * human is chatting in that persistent conversation, and silently cancel
     * tonight's 23:00 delivery: nothing was delivered by the manual attempt,
     * nothing counted as a failure, and the next fire is a day later than the
     * operator's own config says. A skip consumed no fire, so it must consume no
     * cadence either.
     */
    @Test
    void poll_skippedHeartbeat_doesNotConsumeADueTimeThatIsStillInTheFuture() throws Exception {
        var schedule = makeHeartbeatSchedule("sched-skip-5", 3600, "heartbeat");
        schedule.setNextFire(Instant.parse("2099-01-01T00:00:00Z"));
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-skip-5", FireStatus.SKIPPED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore).markSkipped(eq("sched-skip-5"), any(), eq(Instant.parse("2099-01-01T00:00:00Z")));
    }

    /**
     * A one-shot has no next cadence to re-arm to. Leaving it PENDING with
     * {@code nextFire} in the past would be a tight re-fire loop for as long as the
     * conversation stays busy, so a skipped one-shot goes through the retry machine
     * after all — its single delivery genuinely never happened, and backoff plus
     * dead-lettering is the bounded, visible answer.
     */
    @Test
    void poll_skippedOneShot_fallsBackToRetryBecauseThereIsNoCadence() throws Exception {
        var oneShot = makeCronSchedule("sched-skip-4", null, "once");
        oneShot.setCronExpression(null);
        oneShot.setOneTimeAt("2099-01-01T00:00:00Z");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(oneShot));
        when(scheduleStore.tryClaim(any(), any(), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-skip-4", FireStatus.SKIPPED.name()));

        poller.pollDueSchedules();

        verify(scheduleStore, never()).markSkipped(any(), any(), any());
        verify(scheduleStore).markFailed(eq("sched-skip-4"), any(), any());
    }

    /**
     * The manual path shares the state machine, so "fire now" pressed while the
     * conversation is busy must not count a failure either — and, since the manual
     * path is the one that can claim a schedule whose due time is still ahead, must
     * leave that due time exactly where it was. This is the shape the operator
     * actually hits: fire a daily heartbeat by hand in the morning, get skipped,
     * and still get tonight's scheduled run.
     */
    @Test
    void recordManualFireOutcome_skippedFireReArmsWithoutCountingAFailure() throws Exception {
        var schedule = makeHeartbeatSchedule("manual-skip", 3600, "hi");
        schedule.setNextFire(Instant.parse("2099-01-01T00:00:00Z"));

        poller.recordManualFireOutcome(schedule, makeFireLog("manual-skip", FireStatus.SKIPPED.name()));

        verify(scheduleStore).markSkipped(eq("manual-skip"), any(), eq(Instant.parse("2099-01-01T00:00:00Z")));
        verify(scheduleStore, never()).markFailed(any(), any(), any());
        verify(scheduleStore, never()).markCompleted(any(), any(), any());
        verify(scheduleStore, never()).markDeadLettered(any(), any());
    }

    /**
     * A manual fire of a schedule that IS overdue still rolls the cadence forward:
     * the guard is "has this fire's moment arrived", not "was it manual".
     */
    @Test
    void recordManualFireOutcome_skippedFireOfAnOverdueHeartbeatStillAdvancesTheCadence() throws Exception {
        var schedule = makeHeartbeatSchedule("manual-skip-overdue", 3600, "hi");
        Instant due = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        schedule.setNextFire(due);

        poller.recordManualFireOutcome(schedule, makeFireLog("manual-skip-overdue", FireStatus.SKIPPED.name()));

        verify(scheduleStore).markSkipped(eq("manual-skip-overdue"), any(), eq(due.plusSeconds(3600)));
        verify(scheduleStore, never()).markFailed(any(), any(), any());
    }

    /**
     * Every outcome write must name the claim it belongs to.
     * <p>
     * Lease stealing is deliberate: {@code findDueSchedules}/{@code tryClaim} hand
     * a CLAIMED row whose lease expired to a second instance while the first fire
     * may still be running. When that first fire finally finishes, an outcome
     * written by schedule id ALONE lands on the replacement's claim — releasing it
     * back to PENDING with the replacement still executing, so the next poll starts
     * a third copy of the same fire into the same persistent conversation. Passing
     * the claim's {@code fireId} makes the stale write match nothing, which is the
     * point: the row no longer belongs to that fire.
     * <p>
     * All four transitions are checked together because fencing three of them and
     * forgetting the fourth just moves the damage: an unfenced
     * {@code markDeadLettered} would kill a healthy replacement fire on the losing
     * fire's last attempt.
     */
    @Test
    void recordFireOutcome_everyTransitionIsFencedByTheClaimsFireId() throws Exception {
        var completed = makeCronSchedule("fence-c", "0 9 * * *", "hi");
        completed.setFireId("fence-c_claim");
        poller.recordManualFireOutcome(completed, makeFireLog("fence-c", FireStatus.COMPLETED.name()));
        verify(scheduleStore).markCompleted(eq("fence-c"), eq("fence-c_claim"), any());

        var failed = makeCronSchedule("fence-f", "0 9 * * *", "hi");
        failed.setFireId("fence-f_claim");
        poller.recordManualFireOutcome(failed, makeFireLog("fence-f", FireStatus.FAILED.name()));
        verify(scheduleStore).markFailed(eq("fence-f"), eq("fence-f_claim"), any());

        var skipped = makeCronSchedule("fence-s", "0 9 * * *", "hi");
        skipped.setFireId("fence-s_claim");
        poller.recordManualFireOutcome(skipped, makeFireLog("fence-s", FireStatus.SKIPPED.name()));
        verify(scheduleStore).markSkipped(eq("fence-s"), eq("fence-s_claim"), any());

        var deadLettered = makeCronSchedule("fence-d", "0 9 * * *", "hi");
        deadLettered.setFireId("fence-d_claim");
        deadLettered.setFailCount(4); // one short of the maxRetries=5 this poller was built with
        poller.recordManualFireOutcome(deadLettered, makeFireLog("fence-d", FireStatus.FAILED.name()));
        verify(scheduleStore).markDeadLettered("fence-d", "fence-d_claim");
    }

    /**
     * And the fireId the poller fences with is the one the CAS claim just wrote —
     * not null, and not a stale value the schedule was carrying before the claim.
     */
    @Test
    void poll_fencesTheOutcomeWithTheFireIdTheClaimPersisted() throws Exception {
        var schedule = makeCronSchedule("sched-fenced", "0 9 * * *", "Hello");
        schedule.setFireId("stale-from-a-previous-fire");
        when(scheduleStore.findDueSchedules(any(), any(), anyInt())).thenReturn(List.of(schedule));
        when(scheduleStore.tryClaim(eq("sched-fenced"), eq("test-instance"), any(), any())).thenReturn(true);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(makeFireLog("sched-fenced", FireStatus.COMPLETED.name()));

        poller.pollDueSchedules();

        var fenced = ArgumentCaptor.forClass(String.class);
        verify(scheduleStore).markCompleted(eq("sched-fenced"), fenced.capture(), any());
        assertNotNull(fenced.getValue(), "an unfenced outcome write can clobber another fire's live claim");
        assertEquals(schedule.getFireId(), fenced.getValue(), "the fence must be the claim's own fireId");
        assertTrue(fenced.getValue().startsWith("sched-fenced_"), "the claim rewrote the stale fireId: " + fenced.getValue());
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
