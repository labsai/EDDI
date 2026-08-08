/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link GroupHitlCoordinator}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 7) refactor.
 * Covers the pure-function and store-facing helper methods directly; the two
 * public entry points ({@code cancelDiscussion}, {@code resumeDiscussion}) and
 * the rest of the pause/resume machinery are already thoroughly exercised via
 * reflection through the facade's delegators by
 * {@code GroupConversationServiceConcurrencyTest},
 * {@code GroupConversationServiceHitlTest},
 * {@code GroupConversationServiceHitlCoverageTest},
 * {@code GroupConversationServiceHitlCoverage2Test}, and
 * {@code RestGroupConversationHitlTest} — re-verified green against this class
 * post-extraction rather than duplicated here.
 *
 * @author tests
 */
class GroupHitlCoordinatorTest {

    private static final String GROUP_ID = "group-1";
    private static final String GC_ID = "gc-1";

    private IGroupConversationStore conversationStore;
    private IAgentGroupStore groupStore;
    private IScheduleStore scheduleStore;

    private GroupHitlCoordinator coordinator() {
        conversationStore = mock(IGroupConversationStore.class);
        groupStore = mock(IAgentGroupStore.class);
        scheduleStore = mock(IScheduleStore.class);
        return new GroupHitlCoordinator(groupStore, conversationStore, scheduleStore,
                mock(AuditLedgerService.class), new GroupSigningGuard(null, null, null, "default"),
                new ConcurrentHashMap<>(), Mockito.mock(ExecutorService.class), new CallerIdentityContext(null, null),
                Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.hitl.pause"),
                new SimpleMeterRegistry().counter("test.hitl.resume"),
                new SimpleMeterRegistry().counter("test.group.failure"));
    }

    private GroupConversation gc(GroupConversationState state) {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("user-1");
        gc.setState(state);
        return gc;
    }

    // =================================================================
    // notifyCancelled
    // =================================================================

    @Test
    void notifyCancelled_nullListener_doesNotThrow() {
        assertDoesNotThrow(() -> coordinator().notifyCancelled(gc(GroupConversationState.CANCELLED), null));
    }

    @Test
    void notifyCancelled_firesOnCancelledWithReasonAndUserId() {
        var listener = mock(GroupDiscussionEventListener.class);

        coordinator().notifyCancelled(gc(GroupConversationState.CANCELLED), listener);

        var captor = org.mockito.ArgumentCaptor.forClass(GroupConversationEventSink.CancelledEvent.class);
        verify(listener).onCancelled(captor.capture());
        assertEquals("Discussion cancelled", captor.getValue().reason());
        assertEquals("user-1", captor.getValue().cancelledBy());
    }

    // =================================================================
    // taskPauseFingerprint
    // =================================================================

    @Test
    void taskPauseFingerprint_nullTaskList_phaseOnlyPrefix() {
        var gc = gc(GroupConversationState.IN_PROGRESS);
        gc.setTaskList(null);

        String fp = coordinator().taskPauseFingerprint(gc, 2);

        assertEquals("phase=2;", fp);
    }

    @Test
    void taskPauseFingerprint_excludesTerminalTasks_includesNonTerminal() {
        var gc = gc(GroupConversationState.IN_PROGRESS);
        var taskList = new SharedTaskList();
        var pending = new TaskItem("Pending", "p", 0);
        var toComplete = new TaskItem("Done", "d", 0);
        taskList.addTask(pending);
        taskList.addTask(toComplete);
        taskList.assignTask(toComplete.id(), "agent-a", "A");
        taskList.startTask(toComplete.id());
        taskList.completeTask(toComplete.id(), "result");
        gc.setTaskList(taskList);

        String fp = coordinator().taskPauseFingerprint(gc, 0);

        assertTrue(fp.contains(pending.id()), "pending task must be included: " + fp);
        assertFalse(fp.contains(toComplete.id()), "completed task must be excluded: " + fp);
    }

    @Test
    void taskPauseFingerprint_stableAcrossIdenticalCalls() {
        var gc = gc(GroupConversationState.IN_PROGRESS);
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("A", "a", 0));
        gc.setTaskList(taskList);
        var coordinator = coordinator();

        assertEquals(coordinator.taskPauseFingerprint(gc, 1), coordinator.taskPauseFingerprint(gc, 1));
    }

    // =================================================================
    // persistedTerminalOverride
    // =================================================================

    @Test
    void persistedTerminalOverride_nonTerminalPersistedState_returnsFalse() throws Exception {
        var coordinator = coordinator();
        doReturn(gc(GroupConversationState.IN_PROGRESS)).when(conversationStore).read(GC_ID);

        assertFalse(coordinator.persistedTerminalOverride(gc(GroupConversationState.IN_PROGRESS), null));
    }

    @Test
    void persistedTerminalOverride_terminalPersistedState_alignsStateAndReturnsTrue() throws Exception {
        var coordinator = coordinator();
        doReturn(gc(GroupConversationState.COMPLETED)).when(conversationStore).read(GC_ID);
        var localGc = gc(GroupConversationState.IN_PROGRESS);

        boolean stopped = coordinator.persistedTerminalOverride(localGc, null);

        assertTrue(stopped);
        assertEquals(GroupConversationState.COMPLETED, localGc.getState());
    }

    @Test
    void persistedTerminalOverride_cancelledPersistedState_notifiesListener() throws Exception {
        var coordinator = coordinator();
        doReturn(gc(GroupConversationState.CANCELLED)).when(conversationStore).read(GC_ID);
        var listener = mock(GroupDiscussionEventListener.class);

        assertTrue(coordinator.persistedTerminalOverride(gc(GroupConversationState.IN_PROGRESS), listener));
        verify(listener).onCancelled(any());
    }

    @Test
    void persistedTerminalOverride_storeReadThrows_bestEffortReturnsFalse() throws Exception {
        var coordinator = coordinator();
        doThrow(new IResourceStore.ResourceStoreException("boom")).when(conversationStore).read(GC_ID);

        assertFalse(coordinator.persistedTerminalOverride(gc(GroupConversationState.IN_PROGRESS), null));
    }

    // =================================================================
    // scheduleGroupHitlTimeout
    // =================================================================

    @Test
    void scheduleGroupHitlTimeout_waitIndefinitely_noScheduleCreated() throws Exception {
        var gc = gc(GroupConversationState.AWAITING_APPROVAL);
        gc.setHitlApprovalTimeout("PT10M");
        gc.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);

        coordinator().scheduleGroupHitlTimeout(gc);

        verifyNoInteractions(scheduleStore);
    }

    @Test
    void scheduleGroupHitlTimeout_finiteTimeout_createsSchedule() throws Exception {
        var gc = gc(GroupConversationState.AWAITING_APPROVAL);
        gc.setHitlApprovalTimeout("PT10M");
        gc.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        gc.setPausedAt(java.time.Instant.now());

        coordinator().scheduleGroupHitlTimeout(gc);

        verify(scheduleStore).createSchedule(any());
    }

    @Test
    void scheduleGroupHitlTimeout_storeThrows_swallowed() throws Exception {
        var gc = gc(GroupConversationState.AWAITING_APPROVAL);
        gc.setHitlApprovalTimeout("PT10M");
        gc.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        gc.setPausedAt(java.time.Instant.now());
        var coordinator = coordinator();
        doThrow(new RuntimeException("store down")).when(scheduleStore).createSchedule(any());

        assertDoesNotThrow(() -> coordinator.scheduleGroupHitlTimeout(gc));
    }

    // =================================================================
    // convertPauseToCancelIfSignalled — post-commit failures must not revert
    // =================================================================

    /**
     * Regression: {@code notifyCancelled} used to run inside the same {@code try}
     * as the store commit, under a blanket {@code catch (Exception)} that reset the
     * in-memory state to {@code AWAITING_APPROVAL}. A listener throwing — an SSE
     * sink on a closed stream is the realistic case — therefore left the store
     * holding {@code CANCELLED} while memory said {@code AWAITING_APPROVAL}, and
     * {@code executeDiscussion}'s {@code finally} reads that in-memory state: it
     * skips {@code forgetConversation} for {@code AWAITING_APPROVAL} and only runs
     * {@code cleanupEphemeralAgents} for {@code FAILED}/{@code CANCELLED}, so
     * dynamically created agents stayed deployed.
     */
    @Test
    void convertPauseToCancelIfSignalled_listenerThrowsAfterCommit_stateStaysCancelled() throws Exception {
        var coordinator = coordinator();
        var gc = gc(GroupConversationState.AWAITING_APPROVAL);
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        var listener = mock(GroupDiscussionEventListener.class);
        doThrow(new RuntimeException("SSE stream closed")).when(listener).onCancelled(any());

        assertDoesNotThrow(() -> coordinator.convertPauseToCancelIfSignalled(gc, listener, token));

        verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        assertEquals(GroupConversationState.CANCELLED, gc.getState(),
                "the commit already landed — a throwing listener must not roll the in-memory state back");
    }

    /**
     * The pre-commit revert is the one that must stay: a lost state race means the
     * store still holds {@code AWAITING_APPROVAL}, so memory has to agree.
     */
    @Test
    void convertPauseToCancelIfSignalled_commitLosesRace_revertsToAwaitingApproval() throws Exception {
        var coordinator = coordinator();
        var gc = gc(GroupConversationState.AWAITING_APPROVAL);
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        doThrow(new IResourceStore.ResourceModifiedException("raced"))
                .when(conversationStore).updateIfState(any(), any());
        var listener = mock(GroupDiscussionEventListener.class);

        coordinator.convertPauseToCancelIfSignalled(gc, listener, token);

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        verifyNoInteractions(listener);
        verifyNoInteractions(scheduleStore);
    }

    @Test
    void constructedWithMockedCollaborators_doesNotThrow() {
        assertDoesNotThrow(this::coordinator);
    }

    /**
     * Final-review minor: the executor-saturated rollback used a PLAIN token remove
     * while every sibling rollback path uses remove-and-recheck. A cancel signalled
     * between the token registration (right after the resume CAS) and the rollback
     * was silently dropped with the discarded token — the operator was told
     * "cancelled", yet the discussion sat restored to AWAITING_APPROVAL forever.
     */
    @Test
    void resumeRollback_onSaturatedExecutor_convertsASignalledCancel() throws Exception {
        var activeTokens = new ConcurrentHashMap<String, DiscussionControlToken>();
        var executorService = mock(ExecutorService.class);
        conversationStore = mock(IGroupConversationStore.class);
        groupStore = mock(IAgentGroupStore.class);
        scheduleStore = mock(IScheduleStore.class);
        var coordinator = new GroupHitlCoordinator(groupStore, conversationStore, scheduleStore,
                mock(AuditLedgerService.class), new GroupSigningGuard(null, null, null, "default"),
                activeTokens, executorService, new CallerIdentityContext(null, null),
                Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.hitl.pause"),
                new SimpleMeterRegistry().counter("test.hitl.resume"),
                new SimpleMeterRegistry().counter("test.group.failure"));

        var gc = gc(GroupConversationState.AWAITING_APPROVAL);
        gc.setSchemaVersion(GroupConversation.CURRENT_SCHEMA_VERSION);
        gc.setPausedAtPhaseIndex(0);
        gc.setPausedPhaseName("Phase 1");
        gc.setHitlPauseType(GroupConversation.HitlPauseType.PHASE);
        gc.setPausedAt(java.time.Instant.now());
        when(conversationStore.read(GC_ID)).thenReturn(gc);
        when(executorService.submit(any(Runnable.class))).thenAnswer(invocation -> {
            // The racing cancel: lands after the fresh token was registered but
            // before the rollback runs — exactly the dropped window.
            activeTokens.get(GC_ID).setSignal(ControlSignal.CANCEL_GRACEFUL);
            throw new java.util.concurrent.RejectedExecutionException("saturated");
        });
        var decision = new ai.labs.eddi.engine.lifecycle.model.HitlDecision();
        decision.setVerdict(ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict.APPROVED);
        var request = new ai.labs.eddi.engine.internal.GroupApprovalRequest();
        request.setDecision(decision);

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertTrue(activeTokens.isEmpty(), "the rollback must not leak the control token");
        assertEquals(GroupConversationState.CANCELLED, gc.getState(),
                "a cancel signalled during the rollback window converts the restored pause instead of vanishing");
    }
}
