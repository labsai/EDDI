/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
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

    @BeforeEach
    void setUp() throws Exception {
        memStore = mock(IConversationMemoryStore.class);
        gcStore = mock(IGroupConversationStore.class);
        scheduleStore = mock(IScheduleStore.class);
        conversationService = mock(IConversationService.class);
        when(memStore.findConversationIdsByState(any())).thenReturn(List.of());
        when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of());
        when(gcStore.findByState(any())).thenReturn(List.of());
    }

    private HitlCrashRecoveryObserver observer(boolean enabled, boolean recoverInProgress) {
        return new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore, conversationService,
                enabled, recoverInProgress, Optional.empty());
    }

    private HitlCrashRecoveryObserver observerWithRetention(Duration maxAge) {
        return new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore, conversationService,
                true, true, Optional.ofNullable(maxAge));
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
        snapshot.setHitlTimeoutPolicy(policy);
        snapshot.setHitlApprovalTimeout(timeout);
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
            when(memStore.getConversationState(CONV_ID)).thenReturn(ConversationState.AWAITING_HUMAN);

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
            when(memStore.getConversationState(CONV_ID)).thenReturn(ConversationState.AWAITING_HUMAN);

            observer(true, true).runRecovery();

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            Instant expectedDue = pausedAt.plus(java.time.Duration.parse("PT72H"));
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
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(
                    pausedSummary("AUTO_REJECT", "PT30M", Instant.now().minus(1, ChronoUnit.HOURS))));
            // Resume landed between the sweep read and the re-arm
            when(memStore.getConversationState(CONV_ID)).thenReturn(ConversationState.READY);

            observer(true, true).runRecovery();

            verify(scheduleStore).createSchedule(any());
            // deleted twice: once before create (idempotent replace), once to withdraw
            verify(scheduleStore, times(2)).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
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
        @DisplayName("IN_PROGRESS with intact bookmark and finite policy → pause restored AND schedule re-armed")
        void crashedResumeRestoredWithSchedule() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.IN_PROGRESS))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("ABORT", "PT15M", Instant.now().minus(1, ChronoUnit.HOURS)));
            when(memStore.compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN))
                    .thenReturn(true);
            when(memStore.getConversationState(CONV_ID)).thenReturn(ConversationState.AWAITING_HUMAN);

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
            gc.setHitlTimeoutPolicy(policy);
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

            verify(conversationService, never()).cancelConversation(any(), any(), any());
        }

        @Test
        @DisplayName("G6: cancels pauses older than max-age via the audited path attributed to system:retention")
        void cancelsExpiredPauses() throws Exception {
            var oldOne = summary("old", Instant.now().minus(40, ChronoUnit.DAYS));
            var recentOne = summary("recent", Instant.now().minus(1, ChronoUnit.DAYS));
            when(memStore.findPendingApprovalSummaries(anyInt())).thenReturn(List.of(oldOne, recentOne));
            when(conversationService.cancelConversation(eq("old"), any(), eq("system:retention")))
                    .thenReturn(IConversationService.CancelOutcome.CANCELLED);

            observerWithRetention(Duration.ofDays(30)).sweepExpiredPendingApprovals();

            // G6: the 3-arg form with the system actor — so the audit records
            // decidedBy=system:retention (automated), not the default "unknown".
            verify(conversationService).cancelConversation(eq("old"), eq(ControlSignal.CANCEL_GRACEFUL), eq("system:retention"));
            verify(conversationService, never()).cancelConversation(eq("recent"), any(), any());
        }

        @Test
        @DisplayName("zero/negative max-age is treated as OFF")
        void nonPositiveMaxAgeIsOff() throws Exception {
            when(memStore.findPendingApprovalSummaries(anyInt()))
                    .thenReturn(List.of(summary("old", Instant.now().minus(40, ChronoUnit.DAYS))));

            observerWithRetention(Duration.ZERO).sweepExpiredPendingApprovals();

            verify(conversationService, never()).cancelConversation(any(), any(), any());
        }
    }
}
