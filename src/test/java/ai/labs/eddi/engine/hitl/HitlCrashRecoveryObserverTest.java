/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HitlCrashRecoveryObserver} — repair semantics: never
 * destroys a legitimately paused conversation, re-arms lost timeout schedules,
 * restores crashed resumes.
 */
@DisplayName("HitlCrashRecoveryObserver")
class HitlCrashRecoveryObserverTest {

    private static final String CONV_ID = "conv-1";
    private static final String GC_ID = "gc-1";

    private IConversationMemoryStore memStore;
    private IGroupConversationStore gcStore;
    private IScheduleStore scheduleStore;

    @BeforeEach
    void setUp() throws Exception {
        memStore = mock(IConversationMemoryStore.class);
        gcStore = mock(IGroupConversationStore.class);
        scheduleStore = mock(IScheduleStore.class);
        when(memStore.findConversationIdsByState(any())).thenReturn(List.of());
        when(gcStore.findByState(any())).thenReturn(List.of());
    }

    private HitlCrashRecoveryObserver observer(boolean enabled, boolean recoverInProgress) {
        return new HitlCrashRecoveryObserver(memStore, gcStore, scheduleStore, enabled, recoverInProgress);
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
            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("WAIT_INDEFINITELY", null, Instant.now().minus(90, ChronoUnit.DAYS)));

            observer(true, true).onStartup(new StartupEvent());

            verify(memStore, never()).setConversationState(anyString(), any());
            verify(memStore, never()).compareAndSetState(anyString(), any(), any());
            verifyNoInteractions(scheduleStore);
        }

        @Test
        @DisplayName("absent/unknown policy is treated as WAIT_INDEFINITELY (left alone)")
        void unknownPolicyLeftAlone() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("bogus", "PT1H", Instant.now().minus(48, ChronoUnit.HOURS)));

            observer(true, true).onStartup(new StartupEvent());

            verify(memStore, never()).setConversationState(anyString(), any());
            verifyNoInteractions(scheduleStore);
        }

        @Test
        @DisplayName("finite-policy pause gets its timeout schedule idempotently re-armed, state untouched")
        void finitePolicyRearmed() throws Exception {
            Instant pausedAt = Instant.now().minus(48, ChronoUnit.HOURS);
            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("AUTO_REJECT", "PT30M", pausedAt));

            observer(true, true).onStartup(new StartupEvent());

            // state is never mutated for a paused conversation
            verify(memStore, never()).setConversationState(anyString(), any());
            verify(memStore, never()).compareAndSetState(anyString(), any(), any());

            // schedule replaced: delete-by-name then create with correct metadata
            verify(scheduleStore).deleteSchedulesByName("hitl-timeout-" + CONV_ID);
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
            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("AUTO_APPROVE", "PT72H", pausedAt));

            observer(true, true).onStartup(new StartupEvent());

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            Instant expectedDue = pausedAt.plus(java.time.Duration.parse("PT72H"));
            assertEquals(expectedDue, captor.getValue().getNextFire());
        }

        @Test
        @DisplayName("finite policy with missing pausedAt/timeout is skipped without schedule creation")
        void missingBookmarkDataSkipped() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(
                    pausedSnapshot("AUTO_REJECT", null, Instant.now().minus(2, ChronoUnit.DAYS)));

            observer(true, true).onStartup(new StartupEvent());

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("null snapshot is tolerated")
        void nullSnapshotTolerated() throws Exception {
            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID));
            when(memStore.loadConversationMemorySnapshot(CONV_ID)).thenReturn(null);

            assertDoesNotThrow(() -> observer(true, true).onStartup(new StartupEvent()));
            verify(memStore, never()).setConversationState(anyString(), any());
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

            observer(true, true).onStartup(new StartupEvent());

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

            observer(true, true).onStartup(new StartupEvent());

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

            observer(true, true).onStartup(new StartupEvent());

            verify(memStore).compareAndSetState(CONV_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("recover-in-progress=false skips the IN_PROGRESS sweep entirely")
        void inProgressRecoveryCanBeDisabled() throws Exception {
            observer(true, false).onStartup(new StartupEvent());

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

            observer(true, true).onStartup(new StartupEvent());

            verify(gcStore, never()).update(any());
            verifyNoInteractions(scheduleStore);
        }

        @Test
        @DisplayName("finite-policy group pause gets its schedule re-armed, state untouched (never FAILED)")
        void groupFinitePolicyRearmedNotFailed() throws Exception {
            var gc = pausedGc("AUTO_APPROVE", "PT10M", Instant.now().minus(48, ChronoUnit.HOURS));
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL)).thenReturn(List.of(gc));

            observer(true, true).onStartup(new StartupEvent());

            verify(gcStore, never()).update(any());
            assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(),
                    "crash recovery must not destroy a paused group conversation");

            verify(scheduleStore).deleteSchedulesByName("hitl-timeout-group-" + GC_ID);
            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            assertEquals("group", captor.getValue().getMetadata().get("surface"));
        }

        @Test
        @DisplayName("group pause without pausedAt is skipped")
        void groupNullPausedAtSkipped() throws Exception {
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of(pausedGc("AUTO_REJECT", "PT5M", null)));

            observer(true, true).onStartup(new StartupEvent());

            verify(scheduleStore, never()).createSchedule(any());
        }
    }
}
