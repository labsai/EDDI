/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDepthExceededException;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch-coverage-focused unit tests for HITL error/edge paths in
 * {@link GroupConversationService}. These target the uncovered guard clauses,
 * catch blocks, and boolean conditionals in the cancel / resume /
 * pause-conversion and timeout-scheduling machinery — complementing (not
 * overlapping) {@link GroupConversationServiceHitlTest}.
 */
class GroupConversationServiceHitlCoverageTest {

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IConversationService conversationService;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private NonceCacheService nonceCacheService;
    @Mock
    private IScheduleStore scheduleStore;

    private GroupConversationService service;

    private static final int MAX_DEPTH = 3;
    private static final String DEFAULT_TENANT = "default";
    private static final String GROUP_ID = "group-hitl-cov";
    private static final String USER_ID = "user-hitl-cov";
    private static final String MOD_AGENT = "mod-agent";
    private static final String AGENT_A = "agent-a";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, null, DEFAULT_TENANT, MAX_DEPTH);
    }

    // =================================================================
    // Helpers
    // =================================================================

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases) {
        var config = new AgentGroupConfiguration();
        config.setName("HITL Coverage Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(List.of(new GroupMember(AGENT_A, "Agent A", 1, null)));
        config.setModeratorAgentId(MOD_AGENT);
        return config;
    }

    private IResourceStore.IResourceId mockResourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }
            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    /** Paused (AWAITING_APPROVAL) PHASE-granularity GC with the given id. */
    private GroupConversation pausedPhaseGc(String id) {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setGroupId(GROUP_ID);
        gc.setUserId(USER_ID);
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAtPhaseIndex(0);
        gc.setPausedPhaseName("Gate");
        gc.setPausedAt(Instant.now());
        gc.setHitlPauseType(GroupConversation.HitlPauseType.PHASE);
        gc.setOriginalQuestion("Coverage test");
        return gc;
    }

    @SuppressWarnings("unchecked")
    private java.util.concurrent.ConcurrentHashMap<String, DiscussionControlToken> activeTokens(
                                                                                                GroupConversationService svc)
            throws Exception {
        var field = GroupConversationService.class.getDeclaredField("activeTokens");
        field.setAccessible(true);
        return (java.util.concurrent.ConcurrentHashMap<String, DiscussionControlToken>) field.get(svc);
    }

    private GroupApprovalRequest approvedRequest() {
        var request = new GroupApprovalRequest();
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        request.setDecision(decision);
        return request;
    }

    // =================================================================
    // discuss() early guards
    // =================================================================

    @Test
    @DisplayName("discuss: depth > maxDepth → GroupDepthExceededException")
    void discussDepthExceeded() {
        assertThrows(GroupDepthExceededException.class,
                () -> service.discuss(GROUP_ID, "Q?", USER_ID, MAX_DEPTH + 1));
    }

    @Test
    @DisplayName("discuss: null groupId → IllegalArgumentException")
    void discussNullGroupId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.discuss(null, "Q?", USER_ID, 0));
    }

    @Test
    @DisplayName("discuss: getCurrentResourceId null → ResourceNotFoundException")
    void discussGroupNotFound() throws Exception {
        doReturn(null).when(groupStore).getCurrentResourceId(GROUP_ID);
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> service.discuss(GROUP_ID, "Q?", USER_ID, 0));
    }

    @Test
    @DisplayName("discuss: config read returns null → ResourceNotFoundException")
    void discussConfigNull() throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(null).when(groupStore).read(GROUP_ID, 1);
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> service.discuss(GROUP_ID, "Q?", USER_ID, 0));
    }

    @Test
    @DisplayName("discuss: empty phases → GroupDiscussionException")
    void discussEmptyPhases() throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(buildConfig(List.of())).when(groupStore).read(GROUP_ID, 1);
        assertThrows(GroupDiscussionException.class,
                () -> service.discuss(GROUP_ID, "Q?", USER_ID, 0));
    }

    // =================================================================
    // resumeDiscussion() validation guards (before CAS)
    // =================================================================

    @Test
    @DisplayName("resume: state not AWAITING_APPROVAL → GroupDiscussionException")
    void resumeNotAwaiting() throws Exception {
        var gc = pausedPhaseGc("gc-not-awaiting");
        gc.setState(GroupConversationState.IN_PROGRESS);
        doReturn(gc).when(conversationStore).read("gc-not-awaiting");

        assertThrows(GroupDiscussionException.class,
                () -> service.resumeDiscussion("gc-not-awaiting", approvedRequest(), null));
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    @DisplayName("resume: taskApprovals against a PHASE pause → IllegalArgumentException (#30)")
    void resumeTaskApprovalsAgainstPhasePause() throws Exception {
        var gc = pausedPhaseGc("gc-phase-with-taskapprovals");
        // PHASE pause + a task list present → still rejected because pauseType is PHASE
        var taskList = new SharedTaskList();
        taskList.addTask(new SharedTaskList.TaskItem("T", "Do", 0));
        gc.setTaskList(taskList);
        doReturn(gc).when(conversationStore).read("gc-phase-with-taskapprovals");

        var request = approvedRequest();
        request.setTaskApprovals(java.util.Map.of("whatever", "APPROVED"));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.resumeDiscussion("gc-phase-with-taskapprovals", request, null));
        assertTrue(ex.getMessage().contains("PHASE"), ex.getMessage());
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    @DisplayName("resume: taskApprovals against a TASK pause with NO task list → IllegalArgumentException")
    void resumeTaskApprovalsNoTaskList() throws Exception {
        var gc = pausedPhaseGc("gc-no-tasklist");
        gc.setHitlPauseType(GroupConversation.HitlPauseType.TASK);
        gc.setTaskList(null); // no task list
        doReturn(gc).when(conversationStore).read("gc-no-tasklist");

        var request = approvedRequest();
        request.setTaskApprovals(java.util.Map.of("whatever", "APPROVED"));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.resumeDiscussion("gc-no-tasklist", request, null));
        assertTrue(ex.getMessage().contains("task list"), ex.getMessage());
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    // =================================================================
    // cancelDiscussion() branches
    // =================================================================

    @Test
    @DisplayName("cancel: terminal state (COMPLETED) → returns false, no DB write")
    void cancelTerminalStateSkipped() throws Exception {
        var gc = pausedPhaseGc("gc-terminal");
        gc.setState(GroupConversationState.COMPLETED);
        doReturn(gc).when(conversationStore).read("gc-terminal");

        boolean result = service.cancelDiscussion("gc-terminal", ControlSignal.CANCEL_GRACEFUL);

        assertFalse(result, "Cancel of a COMPLETED GC must return false");
        verify(conversationStore, never()).updateIfState(any(), any());
        verify(scheduleStore, never()).deleteSchedulesByName(anyString());
    }

    @Test
    @DisplayName("cancel: token present + GRACEFUL → returns true via signal path (no DB write)")
    void cancelGracefulTokenPath() throws Exception {
        activeTokens(service).put("gc-token-graceful", new DiscussionControlToken());

        boolean result = service.cancelDiscussion("gc-token-graceful", ControlSignal.CANCEL_GRACEFUL);

        assertTrue(result, "Cancel with an in-flight token must return true");
        assertEquals(ControlSignal.CANCEL_GRACEFUL,
                activeTokens(service).get("gc-token-graceful").getSignal());
        // token path takes the early return — never reads the DB or deletes schedules
        verify(conversationStore, never()).read("gc-token-graceful");
        verify(scheduleStore, never()).deleteSchedulesByName(anyString());
    }

    @Test
    @DisplayName("cancel: token present + IMMEDIATE → sets IMMEDIATE signal, returns true")
    void cancelImmediateTokenPath() throws Exception {
        activeTokens(service).put("gc-token-immediate", new DiscussionControlToken());

        boolean result = service.cancelDiscussion("gc-token-immediate", ControlSignal.CANCEL_IMMEDIATE);

        assertTrue(result);
        assertEquals(ControlSignal.CANCEL_IMMEDIATE,
                activeTokens(service).get("gc-token-immediate").getSignal());
        verify(conversationStore, never()).read("gc-token-immediate");
    }

    @Test
    @DisplayName("cancel: no token, paused → DB-CAS write, audit + schedule cleanup, returns true")
    void cancelPausedViaDbWrite() throws Exception {
        var gc = pausedPhaseGc("gc-db-cancel");
        doReturn(gc).when(conversationStore).read("gc-db-cancel");

        boolean result = service.cancelDiscussion("gc-db-cancel", ControlSignal.CANCEL_GRACEFUL);

        assertTrue(result);
        assertEquals(GroupConversationState.CANCELLED, gc.getState());
        assertNull(gc.getPausedAt());
        verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        // wasPaused → schedule deleted
        verify(scheduleStore).deleteSchedulesByName(anyString());
    }

    @Test
    @DisplayName("cancel: no token, IN_PROGRESS (not paused) → cancelled without schedule cleanup")
    void cancelInProgressNotPaused() throws Exception {
        var gc = pausedPhaseGc("gc-inprogress-cancel");
        gc.setState(GroupConversationState.IN_PROGRESS);
        doReturn(gc).when(conversationStore).read("gc-inprogress-cancel");

        boolean result = service.cancelDiscussion("gc-inprogress-cancel", ControlSignal.CANCEL_GRACEFUL);

        assertTrue(result);
        assertEquals(GroupConversationState.CANCELLED, gc.getState());
    }

    @Test
    @DisplayName("cancel: DB-CAS lost (ResourceModifiedException) → returns false, schedule untouched")
    void cancelCasLost() throws Exception {
        var gc = pausedPhaseGc("gc-cas-lost");
        doReturn(gc).when(conversationStore).read("gc-cas-lost");
        doThrow(new IResourceStore.ResourceModifiedException("state changed"))
                .when(conversationStore).updateIfState(any(), eq(GroupConversationState.AWAITING_APPROVAL));

        boolean result = service.cancelDiscussion("gc-cas-lost", ControlSignal.CANCEL_GRACEFUL);

        assertFalse(result, "A lost CAS must report false");
        verify(scheduleStore, never()).deleteSchedulesByName(anyString());
    }

    // =================================================================
    // scheduleGroupHitlTimeout() branches — driven via a real pause (discuss)
    // =================================================================

    private AgentGroupConfiguration approvalConfigWithTimeout(HitlTimeoutPolicy policy, String timeout) {
        var phases = List.of(
                new DiscussionPhase("Gate", PhaseType.OPINION,
                        "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                        false, null, 1, true)); // requiresApproval=true → pause
        var config = buildConfig(phases);
        var hitlConfig = new AgentGroupConfiguration.HitlConfig();
        hitlConfig.setGranularity(HitlGranularity.PHASE);
        if (policy != null) {
            hitlConfig.setTimeoutPolicy(policy);
        }
        hitlConfig.setApprovalTimeout(timeout);
        config.setHitlConfig(hitlConfig);
        return config;
    }

    private void stubAgentSay() throws Exception {
        var snapshot = new ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot();
        var output = new ai.labs.eddi.engine.memory.model.ConversationOutput();
        output.put("output", List.of("Test response"));
        snapshot.setConversationOutputs(new java.util.ArrayList<>(List.of(output)));
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            if (handler != null) {
                handler.onComplete(snapshot);
            }
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("scheduleGroupHitlTimeout: WAIT_INDEFINITELY → no schedule created")
    void timeoutWaitIndefinitelySkipped() throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(approvalConfigWithTimeout(HitlTimeoutPolicy.WAIT_INDEFINITELY, "PT5M"))
                .when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-wait").when(conversationStore).create(any());
        stubAgentSay();

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        // WAIT_INDEFINITELY short-circuits before creating a schedule
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    @DisplayName("scheduleGroupHitlTimeout: null approvalTimeout → no schedule created")
    void timeoutNullTimeoutSkipped() throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        // finite policy but no timeout string → blank/null guard skips scheduling
        doReturn(approvalConfigWithTimeout(HitlTimeoutPolicy.AUTO_REJECT, null))
                .when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-null-timeout").when(conversationStore).create(any());
        stubAgentSay();

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    @DisplayName("scheduleGroupHitlTimeout: fresh finite pause → schedule created")
    void timeoutFreshScheduleCreated() throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(approvalConfigWithTimeout(HitlTimeoutPolicy.AUTO_REJECT, "PT30M"))
                .when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-fresh-timeout").when(conversationStore).create(any());
        stubAgentSay();

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        // fresh finite pause → a one-shot schedule is created
        verify(scheduleStore).createSchedule(any());
    }

    @Test
    @DisplayName("scheduleGroupHitlTimeout: store throws → swallowed, pause still committed")
    void timeoutScheduleStoreThrows() throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(approvalConfigWithTimeout(HitlTimeoutPolicy.AUTO_REJECT, "PT30M"))
                .when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-sched-throws").when(conversationStore).create(any());
        doThrow(new RuntimeException("schedule store down"))
                .when(scheduleStore).createSchedule(any());
        stubAgentSay();

        // The scheduling failure is caught inside scheduleGroupHitlTimeout — the
        // discussion still parks in AWAITING_APPROVAL rather than propagating.
        GroupConversation gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0);

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
    }

    @Test
    @DisplayName("scheduleGroupHitlTimeout: past-due pausedAt clamps to grace window")
    void timeoutPastDueClamped() throws Exception {
        // Directly exercise the private past-due clamp branch: a bookmark whose
        // (pausedAt + timeout) is already in the past must be clamped to now+grace
        // rather than skipped.
        var gc = pausedPhaseGc("gc-past-due");
        gc.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        gc.setHitlApprovalTimeout("PT1M");
        gc.setPausedAt(Instant.now().minus(java.time.Duration.ofHours(2))); // long past due

        var m = GroupConversationService.class.getDeclaredMethod(
                "scheduleGroupHitlTimeout", GroupConversation.class);
        m.setAccessible(true);
        m.invoke(service, gc);

        // Even though the deadline is in the past, a schedule is still created
        // (clamped to now + grace), not skipped.
        verify(scheduleStore).createSchedule(any());
    }

    // =================================================================
    // convertPauseToCancelIfSignalled() branches (private, via reflection)
    // =================================================================

    private void invokeConvert(GroupConversation gc, DiscussionControlToken token) throws Exception {
        var m = GroupConversationService.class.getDeclaredMethod(
                "convertPauseToCancelIfSignalled", GroupConversation.class,
                IGroupConversationServiceListenerType(), DiscussionControlToken.class);
        m.setAccessible(true);
        m.invoke(service, gc, null, token);
    }

    /** The listener parameter type used by the private convert overload. */
    private Class<?> IGroupConversationServiceListenerType() {
        return ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener.class;
    }

    @Test
    @DisplayName("convertPause: null token → no-op (state unchanged)")
    void convertPauseNullToken() throws Exception {
        var gc = pausedPhaseGc("gc-convert-null");
        invokeConvert(gc, null);
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(),
                "A null token must leave the pause intact");
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    @DisplayName("convertPause: token not cancelled → no-op")
    void convertPauseNotCancelled() throws Exception {
        var gc = pausedPhaseGc("gc-convert-not-cancelled");
        invokeConvert(gc, new DiscussionControlToken()); // fresh token, not signalled
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    @DisplayName("convertPause: cancelled token → CANCELLED, schedule deleted")
    void convertPauseCancelledSuccess() throws Exception {
        var gc = pausedPhaseGc("gc-convert-success");
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);

        invokeConvert(gc, token);

        assertEquals(GroupConversationState.CANCELLED, gc.getState());
        assertNull(gc.getPausedAt());
        verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        verify(scheduleStore).deleteSchedulesByName(anyString());
    }

    @Test
    @DisplayName("convertPause: ResourceModifiedException → in-memory state restored to AWAITING_APPROVAL")
    void convertPauseResourceModifiedRestores() throws Exception {
        var gc = pausedPhaseGc("gc-convert-modified");
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        doThrow(new IResourceStore.ResourceModifiedException("raced"))
                .when(conversationStore).updateIfState(any(), eq(GroupConversationState.AWAITING_APPROVAL));

        invokeConvert(gc, token);

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(),
                "A lost CAS restores the in-memory paused state so finally does not release resources");
        verify(scheduleStore, never()).deleteSchedulesByName(anyString());
    }

    @Test
    @DisplayName("convertPause: GroupConversationGoneException → skipped, no restore")
    void convertPauseGoneSkipped() throws Exception {
        var gc = pausedPhaseGc("gc-convert-gone");
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        doThrow(new IGroupConversationStore.GroupConversationGoneException("deleted", null))
                .when(conversationStore).updateIfState(any(), eq(GroupConversationState.AWAITING_APPROVAL));

        // The CANCELLED state was set before the store call; the Gone branch simply
        // logs and does not restore. No exception must escape.
        assertDoesNotThrow(() -> invokeConvert(gc, token));
        verify(scheduleStore, never()).deleteSchedulesByName(anyString());
    }

    @Test
    @DisplayName("convertPause: generic Exception → in-memory state restored to AWAITING_APPROVAL")
    void convertPauseGenericExceptionRestores() throws Exception {
        var gc = pausedPhaseGc("gc-convert-generic");
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        doThrow(new RuntimeException("boom"))
                .when(conversationStore).updateIfState(any(), eq(GroupConversationState.AWAITING_APPROVAL));

        invokeConvert(gc, token);

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(),
                "A generic failure restores the in-memory paused state");
    }

    // =================================================================
    // removeTokenAndConvertIfSignalled() branches (private, via reflection)
    // =================================================================

    private void invokeRemoveAndConvert(GroupConversation gc) throws Exception {
        var m = GroupConversationService.class.getDeclaredMethod(
                "removeTokenAndConvertIfSignalled", GroupConversation.class,
                IGroupConversationServiceListenerType());
        m.setAccessible(true);
        m.invoke(service, gc, null);
    }

    @Test
    @DisplayName("removeToken: no token registered → no-op")
    void removeTokenNoneRegistered() throws Exception {
        var gc = pausedPhaseGc("gc-remove-none");
        // no token in activeTokens for this id
        invokeRemoveAndConvert(gc);
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    @DisplayName("removeToken: token present but not cancelled → removed, no conversion")
    void removeTokenNotCancelled() throws Exception {
        var gc = pausedPhaseGc("gc-remove-not-cancelled");
        activeTokens(service).put("gc-remove-not-cancelled", new DiscussionControlToken());

        invokeRemoveAndConvert(gc);

        assertFalse(activeTokens(service).containsKey("gc-remove-not-cancelled"),
                "token must be removed");
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    @DisplayName("removeToken: cancelled token + AWAITING_APPROVAL → converts to CANCELLED")
    void removeTokenCancelledConverts() throws Exception {
        var gc = pausedPhaseGc("gc-remove-cancelled");
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        activeTokens(service).put("gc-remove-cancelled", token);

        invokeRemoveAndConvert(gc);

        assertFalse(activeTokens(service).containsKey("gc-remove-cancelled"));
        assertEquals(GroupConversationState.CANCELLED, gc.getState(),
                "a cancelled token on an AWAITING_APPROVAL GC converts to CANCELLED");
        verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
    }

    @Test
    @DisplayName("removeToken: cancelled token but NOT awaiting (COMPLETED) → no conversion")
    void removeTokenCancelledButNotAwaiting() throws Exception {
        var gc = pausedPhaseGc("gc-remove-completed");
        gc.setState(GroupConversationState.COMPLETED); // not AWAITING_APPROVAL
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        activeTokens(service).put("gc-remove-completed", token);

        invokeRemoveAndConvert(gc);

        assertFalse(activeTokens(service).containsKey("gc-remove-completed"));
        assertEquals(GroupConversationState.COMPLETED, gc.getState(),
                "a completed GC is not converted even with a cancelled token");
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    // =================================================================
    // auditHitlCancellation() branches (private, via reflection)
    // =================================================================

    @Test
    @DisplayName("auditHitlCancellation: null ledger → no-op (no throw)")
    void auditCancellationNullLedger() throws Exception {
        // 'service' was constructed with a null auditLedgerService
        var gc = pausedPhaseGc("gc-audit-null");
        var m = GroupConversationService.class.getDeclaredMethod(
                "auditHitlCancellation", GroupConversation.class, ControlSignal.class);
        m.setAccessible(true);
        assertDoesNotThrow(() -> m.invoke(service, gc, ControlSignal.CANCEL_GRACEFUL));
    }

    @Test
    @DisplayName("auditHitlCancellation: ledger disabled → submit never called")
    void auditCancellationDisabledLedger() throws Exception {
        var ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(false);
        var audited = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, ledger, DEFAULT_TENANT, MAX_DEPTH);

        var gc = pausedPhaseGc("gc-audit-disabled");
        var m = GroupConversationService.class.getDeclaredMethod(
                "auditHitlCancellation", GroupConversation.class, ControlSignal.class);
        m.setAccessible(true);
        m.invoke(audited, gc, ControlSignal.CANCEL_GRACEFUL);

        verify(ledger, never()).submit(any());
    }

    @Test
    @DisplayName("auditHitlCancellation: ledger enabled → submits a CANCELLED entry")
    void auditCancellationEnabledSubmits() throws Exception {
        var ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        var audited = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, ledger, DEFAULT_TENANT, MAX_DEPTH);

        var gc = pausedPhaseGc("gc-audit-enabled");
        var m = GroupConversationService.class.getDeclaredMethod(
                "auditHitlCancellation", GroupConversation.class, ControlSignal.class);
        m.setAccessible(true);
        m.invoke(audited, gc, ControlSignal.CANCEL_GRACEFUL);

        verify(ledger).submit(any());
    }

    @Test
    @DisplayName("auditHitlCancellation: null mode → defaults to CANCEL_GRACEFUL, still submits")
    void auditCancellationNullMode() throws Exception {
        var ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        var audited = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, ledger, DEFAULT_TENANT, MAX_DEPTH);

        var gc = pausedPhaseGc("gc-audit-null-mode");
        var m = GroupConversationService.class.getDeclaredMethod(
                "auditHitlCancellation", GroupConversation.class, ControlSignal.class);
        m.setAccessible(true);
        m.invoke(audited, gc, (ControlSignal) null);

        verify(ledger).submit(any());
    }

    // =================================================================
    // deleteGroupHitlTimeoutSchedule() branches (private, via reflection)
    // =================================================================

    @Test
    @DisplayName("deleteGroupHitlTimeoutSchedule: store throws → swallowed (no propagation)")
    void deleteScheduleStoreThrows() throws Exception {
        doThrow(new RuntimeException("store down"))
                .when(scheduleStore).deleteSchedulesByName(anyString());

        var m = GroupConversationService.class.getDeclaredMethod(
                "deleteGroupHitlTimeoutSchedule", String.class);
        m.setAccessible(true);
        assertDoesNotThrow(() -> m.invoke(service, "gc-del-throws"));
    }

    @Test
    @DisplayName("deleteGroupHitlTimeoutSchedule: >0 deleted → logs, no throw")
    void deleteScheduleDeletedSome() throws Exception {
        doReturn(2).when(scheduleStore).deleteSchedulesByName(anyString());

        var m = GroupConversationService.class.getDeclaredMethod(
                "deleteGroupHitlTimeoutSchedule", String.class);
        m.setAccessible(true);
        assertDoesNotThrow(() -> m.invoke(service, "gc-del-some"));
        verify(scheduleStore).deleteSchedulesByName(anyString());
    }
}
