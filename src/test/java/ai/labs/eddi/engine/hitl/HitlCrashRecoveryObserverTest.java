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
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HitlCrashRecoveryObserver} — repair semantics: never
 * destroys a legitimately paused conversation, re-arms lost timeout schedules,
 * restores crashed resumes. Tests call {@code runRecovery()} directly — the
 * startup observer only forks it onto a background thread.
 */
@DisplayName("HitlCrashRecoveryObserver")
class HitlCrashRecoveryObserverTest {

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
        when(memStore.findConversationIdsByState(any())).thenReturn(List.of());
        when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of());
        when(gcStore.findByState(any())).thenReturn(List.of());
        when(gcStore.findByState(any(), any(), anyInt())).thenReturn(List.of());
    }

    private HitlCrashRecoveryObserver observer(boolean enabled, boolean recoverInProgress) {
        return new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore, conversationService,
                groupConversationService, enabled, recoverInProgress, Optional.empty());
    }

    private HitlCrashRecoveryObserver observerWithRetention(Duration maxAge) {
        return new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore, conversationService,
                groupConversationService, true, true, Optional.ofNullable(maxAge));
    }

    /** Projected summary of a paused conversation, as the sweep now reads them. */
    private static PendingApprovalSummary pausedSummary(String policy, String timeout, Instant pausedAt) {
        var summary = new PendingApprovalSummary(
                CONV_ID, "agent-1", "user-1", pausedAt, "needs review", policy);
        summary.setApprovalTimeout(timeout);
        return summary;
    }

    private static ConversationMemorySnapshot pausedSnapshot(String policy, String timeout, Instant pausedAt) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        snapshot.setHitlPausedAt(pausedAt);
        snapshot.setHitlTimeoutPolicy(policy == null ? null : HitlTimeoutPolicy.valueOf(policy));
        snapshot.setHitlApprovalTimeout(timeout);
        return snapshot;
    }

    /**
     * Snapshot as the post-rearm re-check re-reads it: AWAITING_HUMAN with the
     * given bookmark timestamp. Used to exercise the KEPT-schedule path — the
     * re-check requires the current pausedAt to still equal the scanned one.
     */
    private static ConversationMemorySnapshot awaitingSnapshot(Instant pausedAt) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPausedAt(pausedAt);
        return snapshot;
    }

    @Nested
    @DisplayName("Disabled via config")
    class DisabledTests {

        @Test
        @DisplayName("does nothing when enabled=false")
        void noOpWhenDisabled() {
            observer(false, true).onStartup(new StartupEvent());

            verifyNoInteractions(memStore);
            verifyNoInteractions(gcStore);
            verifyNoInteractions(scheduleStore);
        }
    }

    @Nested
    @DisplayName("Paused regular conversations")
    class PausedRegularTests {

        @Test
        @DisplayName("WAIT_INDEFINITELY pause is NEVER touched, no matter how old")
        void waitIndefinitelyLeftAlone() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("WAIT_INDEFINITELY", null, Instant.now().minus(90, ChronoUnit.DAYS))));

            observer(true, true).runRecovery();

            verify(memStore, never()).setConversationState(anyString(), any());
            verify(memStore, never()).compareAndSetState(anyString(), any(), any());
            verify(memStore, never()).loadConversationMemorySnapshot(anyString());
            verifyNoInteractions(scheduleStore);
        }

        @Test
        @DisplayName("absent/unknown policy is treated as WAIT_INDEFINITELY (left alone)")
        void unknownPolicyLeftAlone() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("bogus", "PT1H", Instant.now().minus(48, ChronoUnit.HOURS))));

            observer(true, true).runRecovery();

            verify(memStore, never()).setConversationState(anyString(), any());
            verifyNoInteractions(scheduleStore);
        }

        @Test
        @DisplayName("finite-policy pause gets its timeout schedule idempotently re-armed, state untouched")
        void finitePolicyRearmed() throws Exception {
            Instant pausedAt = Instant.now().minus(48, ChronoUnit.HOURS);
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "PT30M", pausedAt)));
            // Post-create re-check re-reads the snapshot: still AWAITING_HUMAN and the
            // SAME pausedAt the sweep scanned → schedule kept.
            when(memStore.loadConversationMemorySnapshot(CONV_ID))
                    .thenReturn(awaitingSnapshot(pausedAt));

            observer(true, true).runRecovery();

            // state is never mutated for a paused conversation
            verify(memStore, never()).setConversationState(anyString(), any());
            verify(memStore, never()).compareAndSetState(anyString(), any(), any());

            // schedule replaced: delete-by-name ONCE (before create), then create
            verify(scheduleStore, times(1)).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            var schedule = captor.getValue();
            assertEquals("hitl-timeout-" + CONV_ID, schedule.getName());
            assertEquals("AUTO_REJECT", schedule.getMetadata().get("policy"));
            assertEquals("regular", schedule.getMetadata().get("surface"));
            assertEquals(CONV_ID, schedule.getMetadata().get("conversationId"));
            // overdue timeout fires after the grace period, not immediately
            assertTrue(schedule.getNextFire().isAfter(Instant.now()),
                    "overdue re-armed timeout must fire in the future (grace period)");
        }

        @Test
        @DisplayName("not-yet-due timeout is re-armed at the original due time")
        void notYetDueRearmedAtOriginalTime() throws Exception {
            Instant pausedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_APPROVE", "PT72H", pausedAt)));
            when(memStore.loadConversationMemorySnapshot(CONV_ID))
                    .thenReturn(awaitingSnapshot(pausedAt));

            observer(true, true).runRecovery();

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            Instant expectedDue = pausedAt.plus(Duration.parse("PT72H"));
            assertEquals(expectedDue, captor.getValue().getNextFire());
        }

        @Test
        @DisplayName("finite policy with missing pausedAt/timeout is skipped without schedule creation")
        void missingBookmarkDataSkipped() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", null, Instant.now().minus(2, ChronoUnit.DAYS))));

            observer(true, true).runRecovery();

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("re-armed schedule is WITHDRAWN when the conversation is no longer paused")
        void rearmWithdrawnWhenNoLongerPaused() throws Exception {
            Instant pausedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "PT30M", pausedAt)));
            // Resume landed between the sweep read and the re-arm — no longer
            // AWAITING_HUMAN
            var resumed = new ConversationMemorySnapshot();
            resumed.setConversationState(ConversationState.READY);
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(resumed);

            observer(true, true).runRecovery();

            verify(scheduleStore).createSchedule(any());
            // deleted twice: once before create (idempotent replace), once to withdraw
            verify(scheduleStore, times(2)).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
        }

        @Test
        @DisplayName("F3: re-armed schedule is WITHDRAWN when a DIFFERENT pause was created after resume+re-pause")
        void rearmWithdrawnWhenPausedAtDiffersFromScanned() throws Exception {
            // The sweep scanned the OLD pause; between scan and re-arm the conversation
            // was resumed and re-paused, producing a NEW pausedAt. Still AWAITING_HUMAN,
            // but the bookmark no longer matches — the stale schedule must be withdrawn
            // so recovery does not keep an OLD-policy timeout on the NEW approval.
            Instant scannedPausedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "PT30M", scannedPausedAt)));
            Instant newerPausedAt = Instant.now().minus(1, ChronoUnit.MINUTES);
            when(memStore.loadConversationMemorySnapshot(CONV_ID))
                    .thenReturn(awaitingSnapshot(newerPausedAt));

            observer(true, true).runRecovery();

            verify(scheduleStore).createSchedule(any());
            // deleted twice: once before create (idempotent replace), once to withdraw
            // the stale schedule (state is AWAITING_HUMAN but the bookmark differs)
            verify(scheduleStore, times(2)).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
        }

        @Test
        @DisplayName("Task 14/1: TOOL_CALL-flavored pause with finite policy is re-armed identically to a RULE pause")
        void toolCallFlavoredPauseRearmedIdentically() throws Exception {
            // A TOOL_CALL pause sets hitlPausedAt exactly like a RULE pause (Task 5) —
            // the summary carries pauseType="TOOL_CALL" but the repair logic never
            // reads pauseType at all, so a finite-policy tool pause must be re-armed
            // the same way a RULE pause is.
            Instant pausedAt = Instant.now().minus(48, ChronoUnit.HOURS);
            var summary = pausedSummary("AUTO_REJECT", "PT30M", pausedAt);
            summary.setPauseType("TOOL_CALL");
            summary.setToolNames(List.of("sendEmail", "chargeCard"));
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(summary));
            when(memStore.loadConversationMemorySnapshot(CONV_ID))
                    .thenReturn(awaitingSnapshot(pausedAt));

            observer(true, true).runRecovery();

            verify(memStore, never()).setConversationState(anyString(), any());
            verify(memStore, never()).compareAndSetState(anyString(), any(), any());
            verify(scheduleStore, times(1)).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            var schedule = captor.getValue();
            assertEquals("AUTO_REJECT", schedule.getMetadata().get("policy"));
            assertEquals("regular", schedule.getMetadata().get("surface"));
        }

        @Test
        @DisplayName("Task 14/1: WAIT_INDEFINITELY TOOL_CALL pause is skipped exactly like a RULE pause")
        void toolCallFlavoredWaitIndefinitelyLeftAlone() throws Exception {
            var summary = pausedSummary("WAIT_INDEFINITELY", null, Instant.now().minus(90, ChronoUnit.DAYS));
            summary.setPauseType("TOOL_CALL");
            summary.setToolNames(List.of("sendEmail"));
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(summary));

            observer(true, true).runRecovery();

            verify(memStore, never()).setConversationState(anyString(), any());
            verify(memStore, never()).compareAndSetState(anyString(), any(), any());
            verify(memStore, never()).loadConversationMemorySnapshot(anyString());
            verifyNoInteractions(scheduleStore);
        }
    }

    @Nested
    @DisplayName("Stuck IN_PROGRESS recovery")
    class InProgressRecoveryTests {

        @Test
        @DisplayName("IN_PROGRESS with intact bookmark → pause restored via CAS")
        void crashedResumeRestored() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("WAIT_INDEFINITELY", null, Instant.now().minus(1, ChronoUnit.HOURS)));
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN))
                    .thenReturn(true);

            observer(true, true).runRecovery();

            verify(memStore).compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            verify(memStore, never()).setConversationState(anyString(), any());
        }

        @Test
        @DisplayName("Task 14/2: IN_PROGRESS-with-bookmark recovery of a TOOL_CALL pause restores AWAITING_HUMAN "
                + "WITHOUT touching hitlPendingToolCalls — the pending batch survives for re-approval")
        void crashedToolCallResumeRestoredWithBatchIntact() throws Exception {
            // A pod died between the resume CAS (AWAITING_HUMAN->IN_PROGRESS) and the
            // resume actually consuming the batch. Recovery must restore the pause
            // WITHOUT ever loading/mutating hitlPendingToolCalls — the batch is only
            // ever read/cleared by the resume path (AgentOrchestrator.resumeToolLoop),
            // never by the crash-recovery observer. This is the structural precondition
            // for the journal (IHitlToolJournalStore.tryClaim) to still prevent
            // double-execution when the human re-approves: the SAME pauseEpoch/callId
            // pairs from the ORIGINAL batch are replayed, not a fresh batch recovery
            // might otherwise have reset. (The tryClaim-returns-false-on-replay
            // contract itself is exercised end-to-end in
            // AgentOrchestratorResumeToolLoopTest#journalExecutedReplays.)
            var snapshot = pausedSnapshot("WAIT_INDEFINITELY", null, Instant.now().minus(1, ChronoUnit.HOURS));
            snapshot.setHitlPauseType("TOOL_CALL");
            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-crash-1");
            var call = new PendingToolCallBatch.PendingToolCall();
            call.setCallId("call-1");
            call.setToolName("chargeCard");
            batch.setCalls(List.of(call));
            snapshot.setHitlPendingToolCalls(batch);

            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snapshot);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN))
                    .thenReturn(true);

            observer(true, true).runRecovery();

            verify(memStore).compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            verify(memStore, never()).setConversationState(anyString(), any());
            // Recovery never clears the bookmark or the batch for a restored pause —
            // only cancel/end (clearHitlBookmark) or LlmTask's post-consumption cleanup
            // ever touch hitlPendingToolCalls.
            verify(memStore, never()).clearHitlBookmark(anyString());
            assertNotNull(snapshot.getHitlPendingToolCalls(), "the pending batch must still be present "
                    + "in the snapshot after recovery — re-approval needs it to apply verdicts");
            assertEquals("epoch-crash-1", snapshot.getHitlPendingToolCalls().getPauseEpoch());
            assertEquals(1, snapshot.getHitlPendingToolCalls().getCalls().size());
        }

        @Test
        @DisplayName("IN_PROGRESS with intact bookmark and finite policy → pause restored AND schedule re-armed")
        void crashedResumeRestoredWithSchedule() throws Exception {
            Instant pausedAt = Instant.now().minus(1, ChronoUnit.HOURS);
            var snapshot = pausedSnapshot("ABORT", "PT15M", pausedAt);
            // Post-CAS the conversation is AWAITING_HUMAN again — the re-arm re-check
            // re-reads this same snapshot and matches the captured pausedAt → kept.
            snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snapshot);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN))
                    .thenReturn(true);

            observer(true, true).runRecovery();

            verify(scheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("IN_PROGRESS without bookmark → EXECUTION_INTERRUPTED via CAS")
        void unknownInProgressInterrupted() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setAgentId("agent-1"); // no hitlPausedAt
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snapshot);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED))
                    .thenReturn(true);

            observer(true, true).runRecovery();

            verify(memStore).compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("null snapshot in the IN_PROGRESS sweep is tolerated")
        void nullSnapshotTolerated() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(null);

            assertDoesNotThrow(() -> observer(true, true).runRecovery());
            verify(memStore, never()).setConversationState(anyString(), any());
            verify(memStore, never()).compareAndSetState(anyString(), any(), any());
        }

        @Test
        @DisplayName("Task 14/5: orphaned hitlPendingToolCalls batch WITHOUT a bookmark is cleared when the "
                + "conversation lands on EXECUTION_INTERRUPTED (neither AWAITING_HUMAN nor IN_PROGRESS)")
        void orphanedToolBatchClearedOnUnknownInProgressRecovery() throws Exception {
            // Defensive-only scenario: pauseConversation() commits hitlPausedAt and
            // hitlPendingToolCalls in the SAME document write, so this should be
            // impossible in practice — but a crash landing between the gate trip and
            // the pause commit could theoretically leave an orphaned batch with no
            // bookmark. The "no bookmark" branch already moves the conversation to
            // EXECUTION_INTERRUPTED; it must also clear the stray batch.
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setAgentId("agent-1"); // no hitlPausedAt — the "unknown interrupted" branch
            var orphanBatch = new PendingToolCallBatch();
            orphanBatch.setPauseEpoch("orphan-epoch");
            snapshot.setHitlPendingToolCalls(orphanBatch);
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snapshot);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED))
                    .thenReturn(true);

            observer(true, true).runRecovery();

            verify(memStore).compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
            verify(memStore).clearHitlBookmark(CONV_ID);
        }

        @Test
        @DisplayName("Task 14/5: a normal (non-orphaned) stuck IN_PROGRESS conversation with no pending batch is NOT touched by the orphan-clear")
        void noBatchNoClearCall() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setAgentId("agent-1"); // no hitlPausedAt, no pending batch either
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(snapshot);
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED))
                    .thenReturn(true);

            observer(true, true).runRecovery();

            verify(memStore, never()).clearHitlBookmark(anyString());
        }

        @Test
        @DisplayName("recover-in-progress=false skips the IN_PROGRESS sweep entirely")
        void inProgressRecoveryCanBeDisabled() throws Exception {
            observer(true, false).runRecovery();

            verify(memStore, never()).findConversationIdsByState(ConversationState.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("Paused group conversations")
    class GroupTests {

        private GroupConversation pausedGc(String policy, String timeout, Instant pausedAt) {
            var gc = new GroupConversation();
            gc.setId(GC_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(pausedAt);
            gc.setHitlTimeoutPolicy(policy == null ? null : HitlTimeoutPolicy.valueOf(policy));
            gc.setHitlApprovalTimeout(timeout);
            return gc;
        }

        @Test
        @DisplayName("WAIT_INDEFINITELY group pause is NEVER touched")
        void groupWaitIndefinitelyLeftAlone() throws Exception {
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of(pausedGc("WAIT_INDEFINITELY", null, Instant.now().minus(90, ChronoUnit.DAYS))));

            observer(true, true).runRecovery();

            verify(gcStore, never()).update(any());
            verifyNoInteractions(scheduleStore);
        }

        @Test
        @DisplayName("finite-policy group pause gets its schedule re-armed, state untouched (never FAILED)")
        void groupFinitePolicyRearmedNotFailed() throws Exception {
            var gc = pausedGc("AUTO_APPROVE", "PT10M", Instant.now().minus(48, ChronoUnit.HOURS));
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL)).thenReturn(List.of(gc));
            when(gcStore.read(GC_ID)).thenReturn(gc);

            observer(true, true).runRecovery();

            verify(gcStore, never()).update(any());
            assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(),
                    "crash recovery must not destroy a paused group conversation");

            verify(scheduleStore, times(1)).deleteSchedulesByName("hitl-timeout-group-" + GC_ID);
            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            assertEquals("group", captor.getValue().getMetadata().get("surface"));
        }

        @Test
        @DisplayName("group pause without pausedAt is skipped")
        void groupNullPausedAtSkipped() throws Exception {
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of(pausedGc("AUTO_REJECT", "PT5M", null)));

            observer(true, true).runRecovery();

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("re-armed group schedule is WITHDRAWN when the discussion is no longer paused")
        void groupRearmWithdrawnWhenNoLongerPaused() throws Exception {
            var gc = pausedGc("AUTO_REJECT", "PT10M", Instant.now().minus(1, ChronoUnit.HOURS));
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL)).thenReturn(List.of(gc));
            // Approve landed between the sweep read and the re-arm
            var resumed = pausedGc("AUTO_REJECT", "PT10M", null);
            resumed.setState(GroupConversationState.IN_PROGRESS);
            when(gcStore.read(GC_ID)).thenReturn(resumed);

            observer(true, true).runRecovery();

            verify(scheduleStore).createSchedule(any());
            verify(scheduleStore, times(2)).deleteSchedulesByName("hitl-timeout-group-" + GC_ID);
        }
    }

    @Nested
    @DisplayName("Group discussions interrupted by a restart")
    class InterruptedGroupTests {

        private final Instant processStart = Instant.now().minus(10, ChronoUnit.MINUTES);

        private GroupConversation runningGc(String id, GroupConversationState state, Instant lastModified) {
            var gc = new GroupConversation();
            gc.setId(id);
            gc.setState(state);
            gc.setLastModified(lastModified);
            return gc;
        }

        private void running(GroupConversationState state, GroupConversation... gcs) throws Exception {
            when(gcStore.findByState(eq(state), isNull(), anyInt())).thenReturn(List.of(gcs));
        }

        @Test
        @DisplayName("IN_PROGRESS discussion last written before the process started is cancelled")
        void inProgressBeforeStartCancelled() throws Exception {
            running(GroupConversationState.IN_PROGRESS,
                    runningGc(GC_ID, GroupConversationState.IN_PROGRESS, processStart.minus(1, ChronoUnit.MINUTES)));
            when(groupConversationService.cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL)).thenReturn(true);

            observer(true, true).runRecovery(processStart);

            verify(groupConversationService).cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("SYNTHESIZING discussion last written before the process started is cancelled")
        void synthesizingBeforeStartCancelled() throws Exception {
            running(GroupConversationState.SYNTHESIZING,
                    runningGc(GC_ID, GroupConversationState.SYNTHESIZING, processStart.minus(1, ChronoUnit.HOURS)));

            observer(true, true).runRecovery(processStart);

            verify(groupConversationService).cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("a discussion written after the process started is a live leg of this process — left alone")
        void writtenAfterStartLeftAlone() throws Exception {
            running(GroupConversationState.IN_PROGRESS,
                    runningGc(GC_ID, GroupConversationState.IN_PROGRESS, processStart.plusSeconds(1)));
            running(GroupConversationState.SYNTHESIZING,
                    runningGc("gc-2", GroupConversationState.SYNTHESIZING, processStart));

            observer(true, true).runRecovery(processStart);

            verify(groupConversationService, never()).cancelDiscussion(any(), any());
        }

        @Test
        @DisplayName("a discussion with no lastModified is of unknown age — left alone")
        void unknownAgeLeftAlone() throws Exception {
            running(GroupConversationState.IN_PROGRESS, runningGc(GC_ID, GroupConversationState.IN_PROGRESS, null));

            observer(true, true).runRecovery(processStart);

            verify(groupConversationService, never()).cancelDiscussion(any(), any());
        }

        @Test
        @DisplayName("recover-in-progress=false leaves interrupted group discussions alone")
        void gatedByRecoverInProgress() throws Exception {
            running(GroupConversationState.IN_PROGRESS,
                    runningGc(GC_ID, GroupConversationState.IN_PROGRESS, processStart.minus(1, ChronoUnit.DAYS)));

            observer(true, false).runRecovery(processStart);

            verify(groupConversationService, never()).cancelDiscussion(any(), any());
        }

        @Test
        @DisplayName("a failing cancel does not stop the next discussion, nor the SYNTHESIZING scan")
        void failingCancelDoesNotStopTheRest() throws Exception {
            Instant before = processStart.minus(5, ChronoUnit.MINUTES);
            running(GroupConversationState.IN_PROGRESS,
                    runningGc("gc-a", GroupConversationState.IN_PROGRESS, before),
                    runningGc("gc-b", GroupConversationState.IN_PROGRESS, before));
            running(GroupConversationState.SYNTHESIZING, runningGc("gc-c", GroupConversationState.SYNTHESIZING, before));
            when(groupConversationService.cancelDiscussion("gc-a", ControlSignal.CANCEL_GRACEFUL))
                    .thenThrow(new RuntimeException("store down"));

            observer(true, true).runRecovery(processStart);

            verify(groupConversationService).cancelDiscussion("gc-b", ControlSignal.CANCEL_GRACEFUL);
            verify(groupConversationService).cancelDiscussion("gc-c", ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("a failing IN_PROGRESS scan does not skip the SYNTHESIZING scan")
        void failingScanDoesNotSkipTheOtherState() throws Exception {
            when(gcStore.findByState(eq(GroupConversationState.IN_PROGRESS), isNull(), anyInt()))
                    .thenThrow(new RuntimeException("query failed"));
            running(GroupConversationState.SYNTHESIZING,
                    runningGc(GC_ID, GroupConversationState.SYNTHESIZING, processStart.minus(1, ChronoUnit.MINUTES)));

            observer(true, true).runRecovery(processStart);

            verify(groupConversationService).cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("the cluster sweep never cancels group discussions — a group holds no lease to prove it dead")
        void clusterSweepLeavesGroupsAlone() throws Exception {
            running(GroupConversationState.IN_PROGRESS,
                    runningGc(GC_ID, GroupConversationState.IN_PROGRESS, Instant.now().minus(1, ChronoUnit.DAYS)));

            observer(true, true).runClusterRecovery(id -> true);

            verify(groupConversationService, never()).cancelDiscussion(any(), any());
        }
    }

    @Nested
    @DisplayName("Pending-approval retention sweep — Finding #32")
    class RetentionSweep {

        private PendingApprovalSummary summary(String conversationId, Instant pausedAt) {
            return new PendingApprovalSummary(conversationId, "agent-1", "user-1", pausedAt,
                    "needs review", "WAIT_INDEFINITELY");
        }

        @Test
        @DisplayName("disabled by default — never cancels")
        void disabledByDefault() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt()))
                    .thenReturn(List.of(summary("old", Instant.now().minus(365, ChronoUnit.DAYS))));

            observer(true, true).sweepExpiredPendingApprovals();

            verify(conversationService, never()).cancelPausedConversation(any(), any(), any());
        }

        @Test
        @DisplayName("G6: cancels pauses older than max-age via the audited path attributed to system:retention")
        void cancelsExpiredPauses() throws Exception {
            var oldOne = summary("old", Instant.now().minus(40, ChronoUnit.DAYS));
            var recentOne = summary("recent", Instant.now().minus(1, ChronoUnit.DAYS));
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(oldOne, recentOne));
            when(conversationService.cancelPausedConversation(eq("old"), eq("system:retention"), any()))
                    .thenReturn(IConversationService.CancelOutcome.CANCELLED);

            observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals();

            // G6: the 3-arg form with the system actor — so the audit records
            // decidedBy=system:retention (automated), not the default "unknown".
            // The paused-only cancel bound to the pause the scan saw: a pause resumed in
            // between is not stopped, and a newer pause is not the expired one.
            verify(conversationService).cancelPausedConversation(eq("old"), eq("system:retention"),
                    eq(HitlDecision.pauseIdOf(oldOne.getPausedAt())));
            verify(conversationService, never()).cancelConversation(any(), any(), any());
            verify(conversationService, never()).cancelPausedConversation(eq("recent"), any(), any());
        }

        @Test
        @DisplayName("zero/negative max-age is treated as OFF")
        void nonPositiveMaxAgeIsOff() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt()))
                    .thenReturn(List.of(summary("old", Instant.now().minus(40, ChronoUnit.DAYS))));

            observerWithRetention(Duration.ZERO).sweepExpiredPendingApprovals();

            verify(conversationService, never()).cancelPausedConversation(any(), any(), any());
        }

        @Test
        @DisplayName("Task 14/4: TOOL_CALL-flavored expired pause is cancelled via the SAME audited "
                + "cancelConversation path as a RULE pause — the sweep never special-cases pauseType")
        void cancelsExpiredToolCallPause() throws Exception {
            // The retention sweep reads only conversationId/pausedAt off the summary —
            // pauseType is irrelevant to the cancellation decision. cancelConversation
            // itself (verified in ConversationServiceHitlTest) calls
            // conversationMemoryStore.clearHitlBookmark(conversationId) on a successful
            // cancel, and clearHitlBookmark unsets hitlPauseType/hitlPendingToolCalls at
            // the store level (ConversationMemoryStore/PostgresConversationMemoryStore) —
            // so a TOOL_CALL pause's pending batch is cleared by the exact same call a
            // RULE pause's bookmark is, with no separate code path required.
            var toolPause = new PendingApprovalSummary("tool-conv", "agent-1", "user-1",
                    Instant.now().minus(40, ChronoUnit.DAYS), "needs review", "WAIT_INDEFINITELY");
            toolPause.setPauseType("TOOL_CALL");
            toolPause.setToolNames(List.of("sendEmail", "chargeCard"));
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(toolPause));
            when(conversationService.cancelPausedConversation(eq("tool-conv"), eq("system:retention"), any()))
                    .thenReturn(IConversationService.CancelOutcome.CANCELLED);

            observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals();

            verify(conversationService).cancelPausedConversation(eq("tool-conv"), eq("system:retention"), any());
        }

        private GroupConversation groupPausedAt(String id, Instant pausedAt) {
            var gc = new GroupConversation();
            gc.setId(id);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(pausedAt);
            gc.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            return gc;
        }

        @Test
        @DisplayName("F2: expired WAIT_INDEFINITELY GROUP pause is cancelled, bound to that pause; recent one spared")
        void cancelsExpiredGroupPauses() throws Exception {
            var oldGc = groupPausedAt("gc-old", Instant.now().minus(40, ChronoUnit.DAYS));
            var recentGc = groupPausedAt("gc-recent", Instant.now().minus(1, ChronoUnit.DAYS));
            when(gcStore.findByState(eq(GroupConversationState.AWAITING_APPROVAL), isNull(), anyInt()))
                    .thenReturn(List.of(oldGc, recentGc));
            when(groupConversationService.cancelPausedDiscussion(eq("gc-old"), any(), any())).thenReturn(true);

            observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals();

            // Paused-only and bound to the expired pause: an approval that resumed the
            // discussion after the scan must not have its running work cancelled.
            verify(groupConversationService).cancelPausedDiscussion("gc-old", ControlSignal.CANCEL_GRACEFUL,
                    HitlDecision.pauseIdOf(oldGc.getPausedAt()));
            verify(groupConversationService, never()).cancelPausedDiscussion(eq("gc-recent"), any(), any());
            verify(groupConversationService, never()).cancelDiscussion(any(), any());
        }

        @Test
        @DisplayName("F2: group retention pass is OFF by default — never cancels group discussions")
        void groupRetentionDisabledByDefault() throws Exception {
            when(gcStore.findByState(eq(GroupConversationState.AWAITING_APPROVAL), isNull(), anyInt()))
                    .thenReturn(List.of(groupPausedAt("gc-old", Instant.now().minus(365, ChronoUnit.DAYS))));

            observer(true, true).sweepExpiredPendingApprovals();

            verify(groupConversationService, never()).cancelPausedDiscussion(any(), any(), any());
            verify(groupConversationService, never()).cancelDiscussion(any(), any());
        }
    }
}
