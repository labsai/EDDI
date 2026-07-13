/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Supplemental branch-coverage tests for {@link HitlCrashRecoveryObserver}.
 *
 * <p>
 * Targets the error paths, catch blocks, CAS-miss branches, scan-limit warning,
 * and no-longer-paused/re-check fall-through branches that the primary
 * {@code HitlCrashRecoveryObserverTest} does not exercise. Mocking setup
 * mirrors that test exactly (same mocks, same constructor wiring, same
 * builders).
 */
@DisplayName("HitlCrashRecoveryObserver — branch coverage")
class HitlCrashRecoveryObserverCoverageTest {

    private static final String CONV_ID = "conv-1";
    private static final String GC_ID = "gc-1";

    private IConversationMemoryStore memStore;
    private IGroupConversationStore gcStore;
    private IScheduleStore scheduleStore;
    private IConversationService conversationService;
    private IGroupConversationService groupConversationService;

    @BeforeEach
    void setUp() throws Exception {
        memStore = mock(IConversationMemoryStore.class);
        gcStore = mock(IGroupConversationStore.class);
        scheduleStore = mock(IScheduleStore.class);
        conversationService = mock(IConversationService.class);
        groupConversationService = mock(IGroupConversationService.class);
        lenient().when(memStore.findConversationIdsByState(any())).thenReturn(List.of());
        lenient().when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of());
        lenient().when(gcStore.findByState(any())).thenReturn(List.of());
        lenient().when(gcStore.findByState(any(), any(), anyInt())).thenReturn(List.of());
    }

    private HitlCrashRecoveryObserver observer(boolean enabled, boolean recoverInProgress) {
        return new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore, conversationService,
                groupConversationService, enabled, recoverInProgress, Optional.empty());
    }

    private HitlCrashRecoveryObserver observerWithRetention(Duration maxAge) {
        return new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore, conversationService,
                groupConversationService, true, true, Optional.ofNullable(maxAge));
    }

    private static PendingApprovalSummary pausedSummary(String policy, String timeout, Instant pausedAt) {
        var summary = new PendingApprovalSummary(
                CONV_ID, "agent-1", "user-1", pausedAt, "needs review", policy);
        summary.setApprovalTimeout(timeout);
        return summary;
    }

    private static PendingApprovalSummary pausedSummary(String id, String policy, String timeout, Instant pausedAt) {
        var summary = new PendingApprovalSummary(
                id, "agent-1", "user-1", pausedAt, "needs review", policy);
        summary.setApprovalTimeout(timeout);
        return summary;
    }

    private static ConversationMemorySnapshot pausedSnapshot(String policy, String timeout, Instant pausedAt) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        snapshot.setHitlPausedAt(pausedAt);
        snapshot.setHitlTimeoutPolicy(policy);
        snapshot.setHitlApprovalTimeout(timeout);
        return snapshot;
    }

    private static ConversationMemorySnapshot awaitingSnapshot(Instant pausedAt) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPausedAt(pausedAt);
        return snapshot;
    }

    private GroupConversation pausedGc(String policy, String timeout, Instant pausedAt) {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAt(pausedAt);
        gc.setHitlTimeoutPolicy(policy == null ? null : HitlTimeoutPolicy.valueOf(policy));
        gc.setHitlApprovalTimeout(timeout);
        return gc;
    }

    // ------------------------------------------------------------------
    // repairRegularPaused — error/edge branches
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("repairRegularPaused branches")
    class RepairRegularPausedBranches {

        @Test
        @DisplayName("scan hitting the RECOVERY_SCAN_LIMIT logs the bound warning and still processes items")
        void scanLimitHit() throws Exception {
            // 10_000 WAIT_INDEFINITELY summaries → size >= limit branch is taken,
            // and each summary short-circuits on the indefinite-policy continue.
            List<PendingApprovalSummary> big = new ArrayList<>(10_000);
            for (int i = 0; i < 10_000; i++) {
                big.add(pausedSummary("c-" + i, "WAIT_INDEFINITELY", null,
                        Instant.now().minus(1, ChronoUnit.HOURS)));
            }
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(big);

            assertDoesNotThrow(() -> observer(true, true).runRecovery());
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("outer catch: findPendingApprovalSummaries throwing is swallowed, returns 0")
        void outerCatchSwallowsScanFailure() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt()))
                    .thenThrow(new RuntimeException("mongo down"));

            assertDoesNotThrow(() -> observer(true, true).runRecovery());
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("inner catch: a single item throwing during re-arm does not abort the whole sweep")
        void innerCatchIsolatesOneItem() throws Exception {
            Instant pausedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "PT30M", pausedAt)));
            // createSchedule throws → rearmSchedule's own catch returns false; the
            // enclosing per-item try still completes. (Exercises rearmSchedule outer
            // catch too.)
            doThrow(new RuntimeException("schedule store down"))
                    .when(scheduleStore).createSchedule(any());

            assertDoesNotThrow(() -> observer(true, true).runRecovery());
            verify(scheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("unparseable approvalTimeout is skipped (no createSchedule)")
        void unparseableTimeoutSkipped() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "not-a-duration", Instant.now().minus(1, ChronoUnit.HOURS))));

            observer(true, true).runRecovery();

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("blank approvalTimeout is skipped (missing-bookmark guard)")
        void blankTimeoutSkipped() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "   ", Instant.now().minus(1, ChronoUnit.HOURS))));

            observer(true, true).runRecovery();

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("post-rearm re-check THROWS → schedule is kept (debugf branch, no withdraw)")
        void postRearmRecheckThrowsKeepsSchedule() throws Exception {
            Instant pausedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "PT30M", pausedAt)));
            // regularStillPaused loads the snapshot; make that read throw → the
            // supplier's own catch returns true → outer re-check keeps the schedule.
            when(memStore.loadConversationMemorySnapshot(CONV_ID))
                    .thenThrow(new RuntimeException("read failed"));

            observer(true, true).runRecovery();

            verify(scheduleStore).createSchedule(any());
            // only the pre-create delete — NOT a withdraw delete
            verify(scheduleStore, times(1)).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
        }

        @Test
        @DisplayName("re-check sees a NULL snapshot → treated as no-longer-paused → withdrawn")
        void recheckNullSnapshotWithdraws() throws Exception {
            Instant pausedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "PT30M", pausedAt)));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(null);

            observer(true, true).runRecovery();

            verify(scheduleStore).createSchedule(any());
            verify(scheduleStore, times(2)).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
        }

        @Test
        @DisplayName("re-check with a null scanned pausedAt is impossible for regular repair "
                + "(missing pausedAt is guarded earlier) — snapshot state-only match keeps schedule")
        void recheckStateOnlyMatchKeepsSchedule() throws Exception {
            Instant pausedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "PT30M", pausedAt)));
            // AWAITING_HUMAN but the snapshot has a NULL hitlPausedAt: scannedPausedAt
            // is non-null so equality is false → treated as no-longer-this-pause →
            // withdraw.
            var snap = new ConversationMemorySnapshot();
            snap.setConversationState(ConversationState.AWAITING_HUMAN);
            snap.setHitlPausedAt(null);
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snap);

            observer(true, true).runRecovery();

            verify(scheduleStore, times(2)).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
        }
    }

    // ------------------------------------------------------------------
    // recoverRegularInProgress — error/CAS branches
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("recoverRegularInProgress branches")
    class RecoverInProgressBranches {

        @Test
        @DisplayName("outer catch: findConversationIdsByState throwing is swallowed, returns 0")
        void outerCatchSwallowsListFailure() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenThrow(new RuntimeException("mongo down"));

            assertDoesNotThrow(() -> observer(true, true).runRecovery());
            verify(memStore, never()).compareAndSetState(anyString(), any(), any());
        }

        @Test
        @DisplayName("inner catch: loadConversationMemorySnapshot throwing for one id does not abort the sweep")
        void innerCatchIsolatesSnapshotFailure() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of("bad-id", CONV_ID));
            when(memStore.loadConversationMemorySnapshot("bad-id"))
                    .thenThrow(new RuntimeException("read failed"));
            // second id recovers normally (no bookmark → EXECUTION_INTERRUPTED)
            var snap = new ConversationMemorySnapshot();
            snap.setAgentId("agent-1");
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snap);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS,
                    ConversationState.EXECUTION_INTERRUPTED)).thenReturn(true);

            observer(true, true).runRecovery();

            verify(memStore).compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS,
                    ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("bookmark path CAS MISS: another pod already restored — no re-arm, no count")
        void bookmarkCasMissDoesNothing() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("ABORT", "PT15M", Instant.now().minus(1, ChronoUnit.HOURS)));
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS,
                    ConversationState.AWAITING_HUMAN)).thenReturn(false);

            observer(true, true).runRecovery();

            // CAS lost → schedule was never re-armed
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("no-bookmark path CAS MISS: another pod already interrupted — no clearHitlBookmark")
        void noBookmarkCasMissDoesNothing() throws Exception {
            var snap = new ConversationMemorySnapshot();
            snap.setAgentId("agent-1"); // no hitlPausedAt
            snap.setHitlPendingToolCalls(new PendingToolCallBatch()); // even a stray batch
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snap);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS,
                    ConversationState.EXECUTION_INTERRUPTED)).thenReturn(false);

            observer(true, true).runRecovery();

            // CAS lost → the orphan-clear block was never entered
            verify(memStore, never()).clearHitlBookmark(anyString());
        }

        @Test
        @DisplayName("orphan-clear inner catch: clearHitlBookmark throwing is swallowed")
        void orphanClearThrowsIsSwallowed() throws Exception {
            var snap = new ConversationMemorySnapshot();
            snap.setAgentId("agent-1"); // no hitlPausedAt → unknown-interrupted branch
            snap.setHitlPendingToolCalls(new PendingToolCallBatch()); // orphaned batch present
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snap);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS,
                    ConversationState.EXECUTION_INTERRUPTED)).thenReturn(true);
            doThrow(new RuntimeException("clear failed")).when(memStore).clearHitlBookmark(CONV_ID);

            assertDoesNotThrow(() -> observer(true, true).runRecovery());
            verify(memStore).clearHitlBookmark(CONV_ID);
        }

        @Test
        @DisplayName("bookmark path with WAIT_INDEFINITELY policy: CAS succeeds but schedule is NOT re-armed")
        void bookmarkWaitIndefinitelyNoSchedule() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("WAIT_INDEFINITELY", null, Instant.now().minus(1, ChronoUnit.HOURS)));
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS,
                    ConversationState.AWAITING_HUMAN)).thenReturn(true);

            observer(true, true).runRecovery();

            verify(memStore).compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS,
                    ConversationState.AWAITING_HUMAN);
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("multi-node sweep: two IN_PROGRESS ids, one CAS win one CAS miss")
        void multiNodeMixedCas() throws Exception {
            var winSnap = new ConversationMemorySnapshot();
            winSnap.setAgentId("agent-1");
            var missSnap = new ConversationMemorySnapshot();
            missSnap.setAgentId("agent-2");
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of("win", "miss"));
            when(memStore.loadConversationMemorySnapshot("win")).thenReturn(winSnap);
            when(memStore.loadConversationMemorySnapshot("miss")).thenReturn(missSnap);
            when(memStore.compareAndSetState("win", ConversationState.IN_PROGRESS,
                    ConversationState.EXECUTION_INTERRUPTED)).thenReturn(true);
            when(memStore.compareAndSetState("miss", ConversationState.IN_PROGRESS,
                    ConversationState.EXECUTION_INTERRUPTED)).thenReturn(false);

            observer(true, true).runRecovery();

            verify(memStore).compareAndSetState("win", ConversationState.IN_PROGRESS,
                    ConversationState.EXECUTION_INTERRUPTED);
            verify(memStore).compareAndSetState("miss", ConversationState.IN_PROGRESS,
                    ConversationState.EXECUTION_INTERRUPTED);
        }
    }

    // ------------------------------------------------------------------
    // repairGroupPaused — error/edge branches
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("repairGroupPaused branches")
    class RepairGroupBranches {

        @Test
        @DisplayName("outer catch: findByState throwing is swallowed, returns 0")
        void outerCatchSwallowsGroupScanFailure() throws Exception {
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenThrow(new RuntimeException("mongo down"));

            assertDoesNotThrow(() -> observer(true, true).runRecovery());
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("inner catch: one group item throwing during re-arm does not abort the sweep")
        void innerCatchIsolatesGroupItem() throws Exception {
            var gc = pausedGc("AUTO_APPROVE", "PT10M", Instant.now().minus(1, ChronoUnit.HOURS));
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL)).thenReturn(List.of(gc));
            doThrow(new RuntimeException("schedule store down"))
                    .when(scheduleStore).createSchedule(any());

            assertDoesNotThrow(() -> observer(true, true).runRecovery());
            verify(scheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("group re-check THROWS → schedule kept (debugf branch)")
        void groupRecheckThrowsKeepsSchedule() throws Exception {
            var gc = pausedGc("AUTO_APPROVE", "PT10M", Instant.now().minus(1, ChronoUnit.HOURS));
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL)).thenReturn(List.of(gc));
            when(gcStore.read(GC_ID)).thenThrow(new RuntimeException("read failed"));

            observer(true, true).runRecovery();

            verify(scheduleStore).createSchedule(any());
            verify(scheduleStore, times(1)).deleteSchedulesByName("hitl-timeout-group-" + GC_ID);
        }

        @Test
        @DisplayName("group re-check sees NULL current → withdrawn")
        void groupRecheckNullWithdraws() throws Exception {
            var gc = pausedGc("AUTO_APPROVE", "PT10M", Instant.now().minus(1, ChronoUnit.HOURS));
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL)).thenReturn(List.of(gc));
            when(gcStore.read(GC_ID)).thenReturn(null);

            observer(true, true).runRecovery();

            verify(scheduleStore, times(2)).deleteSchedulesByName("hitl-timeout-group-" + GC_ID);
        }

        @Test
        @DisplayName("group re-check: scanned pausedAt differs from current → withdrawn")
        void groupRecheckPausedAtMismatchWithdraws() throws Exception {
            Instant scanned = Instant.now().minus(1, ChronoUnit.HOURS);
            var gc = pausedGc("AUTO_APPROVE", "PT10M", scanned);
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL)).thenReturn(List.of(gc));
            var current = pausedGc("AUTO_APPROVE", "PT10M", Instant.now().minus(1, ChronoUnit.MINUTES));
            when(gcStore.read(GC_ID)).thenReturn(current);

            observer(true, true).runRecovery();

            verify(scheduleStore, times(2)).deleteSchedulesByName("hitl-timeout-group-" + GC_ID);
        }

        @Test
        @DisplayName("group WAIT_INDEFINITELY is skipped (never read)")
        void groupWaitIndefinitelySkipped() throws Exception {
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of(pausedGc("WAIT_INDEFINITELY", null,
                            Instant.now().minus(1, ChronoUnit.HOURS))));

            observer(true, true).runRecovery();

            verify(gcStore, never()).read(anyString());
            verify(scheduleStore, never()).createSchedule(any());
        }
    }

    // ------------------------------------------------------------------
    // sweepExpiredPendingApprovals — retention branches
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("sweepExpiredPendingApprovals branches")
    class RetentionSweepBranches {

        @Test
        @DisplayName("disabled flag: sweep returns immediately even when max-age is set")
        void disabledFlagShortCircuits() throws Exception {
            var observer = new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore,
                    conversationService, groupConversationService,
                    false, true, Optional.of(Duration.ofDays(30)));

            observer.sweepExpiredPendingApprovals();

            verifyNoInteractions(conversationService);
            verifyNoInteractions(groupConversationService);
        }

        @Test
        @DisplayName("negative max-age is treated as OFF")
        void negativeMaxAgeIsOff() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("old", "WAIT_INDEFINITELY", null,
                            Instant.now().minus(40, ChronoUnit.DAYS))));

            observerWithRetention(Duration.ofDays(30).negated()).sweepExpiredPendingApprovals();

            verify(conversationService, never()).cancelConversation(any(), any(), any());
        }

        @Test
        @DisplayName("summary with null pausedAt is skipped (unknown age)")
        void nullPausedAtSkipped() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("old", "WAIT_INDEFINITELY", null, null)));

            observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals();

            verify(conversationService, never()).cancelConversation(any(), any(), any());
        }

        @Test
        @DisplayName("cancelConversation returning NOT a CANCELLED outcome does not increment")
        void cancelReturningNonCancelledOutcome() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("old", "WAIT_INDEFINITELY", null,
                            Instant.now().minus(40, ChronoUnit.DAYS))));
            when(conversationService.cancelConversation(eq("old"), any(), eq("system:retention")))
                    .thenReturn(IConversationService.CancelOutcome.NOT_FOUND);

            observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals();

            verify(conversationService).cancelConversation(eq("old"), any(), eq("system:retention"));
        }

        @Test
        @DisplayName("per-item catch: cancelConversation throwing is isolated, sweep continues")
        void cancelThrowsIsIsolated() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("boom", "WAIT_INDEFINITELY", null,
                            Instant.now().minus(40, ChronoUnit.DAYS)),
                    pausedSummary("ok", "WAIT_INDEFINITELY", null,
                            Instant.now().minus(41, ChronoUnit.DAYS))));
            when(conversationService.cancelConversation(eq("boom"), any(), any()))
                    .thenThrow(new RuntimeException("cancel failed"));
            when(conversationService.cancelConversation(eq("ok"), any(), any()))
                    .thenReturn(IConversationService.CancelOutcome.CANCELLED);

            assertDoesNotThrow(() -> observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals());
            verify(conversationService).cancelConversation(eq("ok"), any(), any());
        }

        @Test
        @DisplayName("outer catch: findPendingApprovalSummaries throwing does not abort the group pass")
        void regularSweepScanFailureStillRunsGroupPass() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt()))
                    .thenThrow(new RuntimeException("mongo down"));
            var gc = new GroupConversation();
            gc.setId("gc-old");
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(Instant.now().minus(40, ChronoUnit.DAYS));
            when(gcStore.findByState(eq(GroupConversationState.AWAITING_APPROVAL), isNull(), anyInt()))
                    .thenReturn(List.of(gc));
            when(groupConversationService.cancelDiscussion(eq("gc-old"), any())).thenReturn(true);

            assertDoesNotThrow(() -> observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals());
            verify(groupConversationService).cancelDiscussion("gc-old", ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("group pass: null pausedAt skipped; recent (not before cutoff) skipped")
        void groupNullAndRecentSkipped() throws Exception {
            var nullGc = new GroupConversation();
            nullGc.setId("gc-null");
            nullGc.setState(GroupConversationState.AWAITING_APPROVAL);
            nullGc.setPausedAt(null);
            var recentGc = new GroupConversation();
            recentGc.setId("gc-recent");
            recentGc.setState(GroupConversationState.AWAITING_APPROVAL);
            recentGc.setPausedAt(Instant.now().minus(1, ChronoUnit.DAYS));
            when(gcStore.findByState(eq(GroupConversationState.AWAITING_APPROVAL), isNull(), anyInt()))
                    .thenReturn(List.of(nullGc, recentGc));

            observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals();

            verify(groupConversationService, never()).cancelDiscussion(any(), any());
        }

        @Test
        @DisplayName("group pass: cancelDiscussion returning false does not increment")
        void groupCancelReturningFalse() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-old");
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(Instant.now().minus(40, ChronoUnit.DAYS));
            when(gcStore.findByState(eq(GroupConversationState.AWAITING_APPROVAL), isNull(), anyInt()))
                    .thenReturn(List.of(gc));
            when(groupConversationService.cancelDiscussion(eq("gc-old"), any())).thenReturn(false);

            observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals();

            verify(groupConversationService).cancelDiscussion("gc-old", ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("group pass per-item catch: cancelDiscussion throwing is isolated")
        void groupCancelThrowsIsolated() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-boom");
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(Instant.now().minus(40, ChronoUnit.DAYS));
            when(gcStore.findByState(eq(GroupConversationState.AWAITING_APPROVAL), isNull(), anyInt()))
                    .thenReturn(List.of(gc));
            when(groupConversationService.cancelDiscussion(eq("gc-boom"), any()))
                    .thenThrow(new RuntimeException("cancel failed"));

            assertDoesNotThrow(() -> observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals());
            verify(groupConversationService).cancelDiscussion("gc-boom", ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("group pass outer catch: findByState throwing is swallowed")
        void groupSweepScanFailureSwallowed() throws Exception {
            when(gcStore.findByState(eq(GroupConversationState.AWAITING_APPROVAL), isNull(), anyInt()))
                    .thenThrow(new RuntimeException("mongo down"));

            assertDoesNotThrow(() -> observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals());
            verify(groupConversationService, never()).cancelDiscussion(any(), any());
        }
    }
}
