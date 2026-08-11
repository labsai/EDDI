/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OnHumanTimeout;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupConversation.PendingHumanInput;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

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
    private ExecutorService executorService;
    private GroupConversationService groupConversationService;

    private GroupHitlCoordinator coordinator() {
        conversationStore = mock(IGroupConversationStore.class);
        groupStore = mock(IAgentGroupStore.class);
        scheduleStore = mock(IScheduleStore.class);
        executorService = Mockito.mock(ExecutorService.class);
        groupConversationService = Mockito.mock(GroupConversationService.class);
        return new GroupHitlCoordinator(groupStore, conversationStore, scheduleStore,
                mock(AuditLedgerService.class), new GroupSigningGuard(null, null, null, "default"),
                new ConcurrentHashMap<>(), executorService, new CallerIdentityContext(null, null),
                groupConversationService,
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

    // =================================================================
    // I6 — human member turns
    // =================================================================

    private static final GroupMember HUMAN = new GroupMember("h-1", "Hannah", 1, null, MemberType.HUMAN);

    private DiscussionPhase opinionPhase() {
        return new DiscussionPhase("Discuss", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, 1, false);
    }

    private AgentGroupConfiguration humanGroupConfig(String turnTimeout) {
        var config = new AgentGroupConfiguration();
        config.setName("G");
        config.setMembers(List.of(HUMAN));
        config.setPhases(List.of(opinionPhase()));
        if (turnTimeout != null) {
            config.setHumanMemberConfig(new AgentGroupConfiguration.HumanMemberConfig(turnTimeout, OnHumanTimeout.SKIP_TURN));
        }
        return config;
    }

    private GroupConversation humanPausedGc() {
        var gc = gc(GroupConversationState.AWAITING_HUMAN_INPUT);
        gc.setOriginalQuestion("Q?");
        gc.setPausedAt(Instant.now());
        gc.setPausedAtPhaseIndex(0);
        gc.setPausedPhaseName("Discuss");
        gc.setHitlPauseType(HitlPauseType.HUMAN_TURN);
        gc.setPendingHumanInput(new PendingHumanInput("h-1", "Hannah", 0, 0, 1, "OPINION", "the prompt",
                "SKIP_TURN", Instant.now()));
        gc.setResumePoint(new GroupConversation.ResumePoint(0, 0, 1, GroupConversation.RESUME_KIND_HUMAN_TURN));
        return gc;
    }

    @Test
    void commitHumanTurnPause_persistsPauseBookmarkPendingAndSchedule() throws Exception {
        var coordinator = coordinator();
        var gc = gc(GroupConversationState.IN_PROGRESS);
        var turn = new PhaseExecutionEngine.HumanTurnRequired(HUMAN, 2, "the prompt", false);
        var listener = mock(GroupDiscussionEventListener.class);

        coordinator.commitHumanTurnPause(gc, 1, opinionPhase(), 0, turn, 5, "OPINION", listener, humanGroupConfig("PT4H"));

        assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
        assertEquals(HitlPauseType.HUMAN_TURN, gc.getHitlPauseType());
        assertEquals(5, gc.getPausedTurnCount(), "the human's turn is spent when it resolves — counted at the pause");
        assertEquals("PT4H", gc.getHitlApprovalTimeout());
        var pending = gc.getPendingHumanInput();
        assertEquals("h-1", pending.memberId());
        assertEquals("the prompt", pending.renderedPrompt());
        assertEquals("OPINION", pending.entryType());
        assertEquals("SKIP_TURN", pending.onTimeout());
        var bookmark = gc.getResumePoint();
        assertEquals(1, bookmark.phaseIdx());
        assertEquals(2, bookmark.speakerIdx());
        assertEquals(GroupConversation.RESUME_KIND_HUMAN_TURN, bookmark.pauseKind());
        verify(conversationStore).update(gc);
        var scheduleCaptor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(scheduleCaptor.capture());
        assertEquals(HitlSchedules.SURFACE_GROUP_HUMAN,
                scheduleCaptor.getValue().getMetadata().get(HitlSchedules.METADATA_SURFACE_KEY));
        assertEquals("SKIP_TURN", scheduleCaptor.getValue().getMetadata().get(HitlSchedules.METADATA_POLICY_KEY));
        verify(listener).onHumanInputRequested(any(GroupConversationEventSink.HumanInputRequestedEvent.class));
    }

    @Test
    void commitHumanTurnPause_parallelKind_andNoTimeout_waitsIndefinitely() throws Exception {
        var coordinator = coordinator();
        var gc = gc(GroupConversationState.IN_PROGRESS);
        var turn = new PhaseExecutionEngine.HumanTurnRequired(HUMAN, 0, "p", true);

        coordinator.commitHumanTurnPause(gc, 0, opinionPhase(), 0, turn, 1, "OPINION", null, humanGroupConfig(null));

        assertEquals(GroupConversation.RESUME_KIND_HUMAN_TURN_PARALLEL, gc.getResumePoint().pauseKind());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    void submitHumanInput_recordsEntryAdvancesBookmarkAndResumes() throws Exception {
        var coordinator = coordinator();
        var gc = humanPausedGc();
        when(conversationStore.read(GC_ID)).thenReturn(gc);
        var resId = mock(IResourceStore.IResourceId.class);
        when(resId.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resId);
        var config = humanGroupConfig("PT4H");
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
        when(groupConversationService.effectivePhases(any(), eq(config))).thenReturn(List.of(opinionPhase()));
        var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        var result = coordinator.submitHumanInput(GC_ID, "h-1", "My considered answer.", "h-1", null);

        assertEquals(GroupConversationState.IN_PROGRESS, result.getState());
        assertNull(result.getPendingHumanInput());
        assertEquals(1, result.getTranscript().size());
        var entry = result.getTranscript().get(0);
        assertEquals("h-1", entry.speakerAgentId());
        assertEquals("My considered answer.", entry.content());
        assertEquals(TranscriptEntryType.OPINION, entry.type(), "the phase's NATURAL type, captured at pause time");
        assertEquals(2, result.getResumePoint().speakerIdx(), "advanced past the answered speaker");
        verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_HUMAN_INPUT);
        verify(scheduleStore).deleteSchedulesByName(anyString());
        // The resume runs on the executor — capture and run it, then verify the
        // discussion re-entered at the paused phase.
        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();
        verify(groupConversationService).executeDiscussion(eq(gc), eq(config), anyList(), eq("Q?"), isNull(), eq(0));
    }

    @Test
    void submitHumanInput_wrongMember_rejectsBeforeAnyMutation() throws Exception {
        var coordinator = coordinator();
        var gc = humanPausedGc();
        when(conversationStore.read(GC_ID)).thenReturn(gc);

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.submitHumanInput(GC_ID, "someone-else", "text", "someone-else", null));

        assertTrue(gc.getTranscript().isEmpty());
        assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    void submitHumanInput_wrongState_conflicts() throws Exception {
        var coordinator = coordinator();
        when(conversationStore.read(GC_ID)).thenReturn(gc(GroupConversationState.COMPLETED));

        assertThrows(IGroupConversationService.GroupDiscussionException.class,
                () -> coordinator.submitHumanInput(GC_ID, "h-1", "text", "h-1", null));
    }

    @Test
    void submitHumanInput_blankOrOversizeContent_rejected() throws Exception {
        var coordinator = coordinator();
        when(conversationStore.read(GC_ID)).thenReturn(humanPausedGc());

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.submitHumanInput(GC_ID, "h-1", "   ", "h-1", null));
        when(conversationStore.read(GC_ID)).thenReturn(humanPausedGc());
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.submitHumanInput(GC_ID, "h-1",
                        "x".repeat(GroupHitlCoordinator.MAX_HUMAN_INPUT_LENGTH + 1), "h-1", null));
    }

    @Test
    void submitHumanInput_configDrift_refusesBeforeMutation() throws Exception {
        var coordinator = coordinator();
        var gc = humanPausedGc();
        gc.setPausedPhaseName("Old Phase Name");
        when(conversationStore.read(GC_ID)).thenReturn(gc);
        var resId = mock(IResourceStore.IResourceId.class);
        when(resId.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resId);
        var config = humanGroupConfig(null);
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
        when(groupConversationService.effectivePhases(any(), eq(config))).thenReturn(List.of(opinionPhase()));

        assertThrows(IGroupConversationService.GroupDiscussionException.class,
                () -> coordinator.submitHumanInput(GC_ID, "h-1", "text", "h-1", null));

        assertTrue(gc.getTranscript().isEmpty(), "drift refuses BEFORE any mutation — no rollback needed");
        assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
    }

    // =================================================================
    // I12 — facilitator escalation pause
    // =================================================================

    @Test
    void commitFacilitatorEscalationPause_pausesOnThePrincipal_withResumeShapedBookmark() throws Exception {
        var coordinator = coordinator();
        var gc = gc(GroupConversationState.IN_PROGRESS);
        var listener = mock(GroupDiscussionEventListener.class);
        // The RUNTIME list, deliberately different from anything a config would
        // resolve — the resume phase's name must come from IT.
        var runtimePhases = List.of(opinionPhase(),
                new DiscussionPhase("Facilitator Vote", PhaseType.VOTE, "ALL", TurnOrder.PARALLEL,
                        ContextScope.NONE, false, null, 1, false));
        var escalation = new FacilitatorEngine.FacilitatorAction.Escalation("boss@example.com", "Budget approved?");

        coordinator.commitFacilitatorEscalationPause(gc, 1, 0, runtimePhases, escalation, 7, listener,
                humanGroupConfig("PT4H"));

        assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
        assertEquals(HitlPauseType.HUMAN_TURN, gc.getHitlPauseType());
        assertEquals("Facilitator Vote", gc.getPausedPhaseName(), "read from the runtime list, not the config");
        assertEquals(7, gc.getPausedTurnCount());
        assertEquals("PT4H", gc.getHitlApprovalTimeout(), "escalations wait under the group's human-turn rules");
        var pending = gc.getPendingHumanInput();
        assertEquals("boss@example.com", pending.memberId());
        assertEquals("Budget approved?", pending.renderedPrompt());
        assertEquals("FOLLOW_UP", pending.entryType());
        assertEquals(-1, pending.speakerIdx());
        var bookmark = gc.getResumePoint();
        assertEquals(1, bookmark.phaseIdx());
        assertEquals(0, bookmark.repeatIdx());
        assertEquals(-1, bookmark.speakerIdx(), "the shared +1 advance lands the resume at speaker 0");
        assertEquals(GroupConversation.RESUME_KIND_HUMAN_TURN, bookmark.pauseKind());
        verify(conversationStore).update(gc);
        verify(scheduleStore).createSchedule(any());
        verify(listener).onHumanInputRequested(any(GroupConversationEventSink.HumanInputRequestedEvent.class));
    }

    @Test
    void escalationSubmission_resumesAtTheBookmarkedPhase_speakerZero() throws Exception {
        var coordinator = coordinator();
        var gc = gc(GroupConversationState.AWAITING_HUMAN_INPUT);
        gc.setOriginalQuestion("Q?");
        gc.setPausedAt(Instant.now());
        gc.setPausedAtPhaseIndex(1);
        gc.setPausedPhaseName("Facilitator Vote");
        gc.setHitlPauseType(HitlPauseType.HUMAN_TURN);
        gc.setPendingHumanInput(new PendingHumanInput("boss@example.com", "boss@example.com", 1, 0, -1,
                "FOLLOW_UP", "Budget approved?", "SKIP_TURN", Instant.now()));
        gc.setResumePoint(new GroupConversation.ResumePoint(1, 0, -1, GroupConversation.RESUME_KIND_HUMAN_TURN));
        var votePhase = new DiscussionPhase("Facilitator Vote", PhaseType.VOTE, "ALL", TurnOrder.PARALLEL,
                ContextScope.NONE, false, null, 1, false);
        gc.setRuntimePhases(List.of(opinionPhase(), votePhase));
        when(conversationStore.read(GC_ID)).thenReturn(gc);
        var resId = mock(IResourceStore.IResourceId.class);
        when(resId.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resId);
        var config = humanGroupConfig(null);
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
        // The coordinator asks for the EFFECTIVE phases; the drift check only
        // passes because the runtime list (with the inserted vote) is what comes
        // back — the config alone has no phase named "Facilitator Vote" at index 1.
        when(groupConversationService.effectivePhases(any(), eq(config)))
                .thenReturn(List.of(opinionPhase(), votePhase));
        var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        var result = coordinator.submitHumanInput(GC_ID, "boss@example.com", "Yes — approved.", "boss@example.com", null);

        assertEquals(GroupConversationState.IN_PROGRESS, result.getState());
        var entry = result.getTranscript().get(0);
        assertEquals(TranscriptEntryType.FOLLOW_UP, entry.type(), "escalation answers are peer-visible guidance");
        assertEquals("Yes — approved.", entry.content());
        assertEquals(1, entry.phaseIndex());
        assertEquals(0, result.getResumePoint().speakerIdx(), "-1 advanced to 0 — the phase starts fresh");
        verify(executorService).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();
        verify(groupConversationService).executeDiscussion(eq(gc), eq(config), anyList(), eq("Q?"), isNull(), eq(1));
    }

    @Test
    void skipHumanTurnOnTimeout_writesSkippedEntryAndResumes() throws Exception {
        var coordinator = coordinator();
        var gc = humanPausedGc();
        gc.setHitlApprovalTimeout("PT1H");
        when(conversationStore.read(GC_ID)).thenReturn(gc);
        var resId = mock(IResourceStore.IResourceId.class);
        when(resId.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resId);
        var config = humanGroupConfig("PT1H");
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
        when(groupConversationService.effectivePhases(any(), eq(config))).thenReturn(List.of(opinionPhase()));

        coordinator.skipHumanTurnOnTimeout(GC_ID);

        assertEquals(GroupConversationState.IN_PROGRESS, gc.getState());
        assertEquals(1, gc.getTranscript().size());
        var entry = gc.getTranscript().get(0);
        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertTrue(entry.errorReason().contains("Hannah"), "the skip names WHO did not respond: " + entry.errorReason());
        assertTrue(entry.errorReason().contains("PT1H"), "and within WHAT window: " + entry.errorReason());
        assertEquals(2, gc.getResumePoint().speakerIdx(), "a skipped turn still advances past the speaker");
    }

    @Test
    void skipHumanTurnOnTimeout_alreadyResolved_noOp() throws Exception {
        var coordinator = coordinator();
        when(conversationStore.read(GC_ID)).thenReturn(gc(GroupConversationState.COMPLETED));

        assertDoesNotThrow(() -> coordinator.skipHumanTurnOnTimeout(GC_ID));

        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    void cancelDiscussion_awaitingHumanInput_cancelsAndClearsPending() throws Exception {
        var coordinator = coordinator();
        var gc = humanPausedGc();
        when(conversationStore.read(GC_ID)).thenReturn(gc);

        assertTrue(coordinator.cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL));

        assertEquals(GroupConversationState.CANCELLED, gc.getState());
        assertNull(gc.getPendingHumanInput(), "a cancelled turn is no longer owed");
        verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_HUMAN_INPUT);
        verify(scheduleStore).deleteSchedulesByName(anyString());
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
            throw new RejectedExecutionException("saturated");
        });
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        var request = new GroupApprovalRequest();
        request.setDecision(decision);

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertTrue(activeTokens.isEmpty(), "the rollback must not leak the control token");
        assertEquals(GroupConversationState.CANCELLED, gc.getState(),
                "a cancel signalled during the rollback window converts the restored pause instead of vanishing");
    }
}
