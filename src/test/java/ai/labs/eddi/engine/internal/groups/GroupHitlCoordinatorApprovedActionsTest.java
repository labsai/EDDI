/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedAction;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OnApprovedConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionStatus;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.hitl.HitlPlanHash;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The approval resume and approved-result actions: the action a task owes is
 * recorded inside the approval's pause-bound write, and settled on the resumed
 * leg before the phase loop re-enters.
 */
class GroupHitlCoordinatorApprovedActionsTest {

    private static final String GC_ID = "gc-1";
    private static final String GROUP_ID = "group-1";
    private static final String URI = "eddi://ai.labs.apicalls/apicallstore/apicalls/6740a1b2c3d4e5f6a7b8c9d0?version=2";

    private IGroupConversationStore conversationStore;
    private IAgentGroupStore groupStore;
    private ExecutorService executorService;
    private GroupConversationService groupConversationService;
    private GroupApprovedResultActions actions;
    private GroupHitlCoordinator coordinator;
    private AgentGroupConfiguration config;
    private GroupConversation gc;
    private TaskItem task;

    @BeforeEach
    void setUp() throws Exception {
        conversationStore = mock(IGroupConversationStore.class);
        groupStore = mock(IAgentGroupStore.class);
        executorService = mock(ExecutorService.class);
        groupConversationService = mock(GroupConversationService.class);
        actions = mock(GroupApprovedResultActions.class);
        coordinator = new GroupHitlCoordinator(groupStore, conversationStore, mock(IScheduleStore.class), mock(AuditLedgerService.class),
                new GroupSigningGuard(null, null, "default"), new ConcurrentHashMap<>(), executorService,
                new CallerIdentityContext(null, null), groupConversationService, new SimpleMeterRegistry().counter("p"),
                new SimpleMeterRegistry().counter("r"), new SimpleMeterRegistry().counter("f"));
        coordinator.setApprovedResultActions(actions);

        var phase = new DiscussionPhase("Draft", PhaseType.EXECUTE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, true,
                null, false, null, null, new OnApprovedConfig(new ApprovedAction(null, URI, "postComment"), null));
        config = new AgentGroupConfiguration();
        config.setPhases(List.of(phase));
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setGranularity(HitlGranularity.TASK);
        config.setHitlConfig(hitl);
        var resId = mock(IResourceStore.IResourceId.class);
        when(resId.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resId);
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
        when(groupConversationService.effectivePhases(any(), eq(config))).thenReturn(List.of(phase));

        gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("user-1");
        gc.setOriginalQuestion("Q?");
        gc.setSchemaVersion(GroupConversation.CURRENT_SCHEMA_VERSION);
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setHitlPauseType(HitlPauseType.TASK);
        gc.setPausedAtPhaseIndex(0);
        gc.setPausedPhaseName("Draft");
        gc.setPausedAt(Instant.ofEpochMilli(5_000L));
        var list = new SharedTaskList();
        task = new TaskItem("Reply", "reply to the issue", 0);
        list.addTask(task);
        list.assignTask(task.id(), "writer", "Writer");
        list.startTask(task.id());
        list.submitForApproval(task.id(), "draft");
        gc.setTaskList(list);
        when(conversationStore.read(GC_ID)).thenReturn(gc);
    }

    /** An APPROVED decision by alice; tests add task approvals as they need. */
    private static GroupApprovalRequest approvedByAlice() {
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setDecidedBy("alice");
        var request = new GroupApprovalRequest();
        request.setDecision(decision);
        return request;
    }

    @Test
    void anApproval_recordsTheOwedAction_beforeItsPauseBoundWrite_andRunsItBeforeThePhaseLoop() throws Exception {
        var request = approvedByAlice();
        request.setTaskApprovals(Map.of(task.id(), "APPROVED"));
        var work = ArgumentCaptor.forClass(Runnable.class);

        coordinator.resumeDiscussion(GC_ID, request, null);

        InOrder order = inOrder(actions, conversationStore, groupConversationService);
        order.verify(actions).recordApprovals(same(gc), any(DiscussionPhase.class), eq(0), argThat(ids -> ids.contains(task.id())), eq("alice"));
        order.verify(conversationStore).updateIfStateAndPause(eq(gc), eq(GroupConversationState.AWAITING_APPROVAL), anyString());
        verify(executorService).submit(work.capture());

        work.getValue().run();

        order.verify(actions).runOwed(same(gc), same(conversationStore), any());
        order.verify(groupConversationService).executeDiscussion(eq(gc), eq(config), anyList(), eq("Q?"), isNull(), eq(0));
    }

    @Test
    void aPhaseLevelRejection_recordsNothing() throws Exception {
        var request = approvedByAlice();
        request.getDecision().setVerdict(HitlDecision.HitlVerdict.REJECTED);

        coordinator.resumeDiscussion(GC_ID, request, null);

        verify(actions, never()).recordApprovals(any(), any(), anyInt(), any(), any());
        verify(actions, never()).runOwed(any(), any(), any());
    }

    @Test
    void aRealRunner_persistsThePendingRecordWithTheApproval() throws Exception {
        var real = new GroupApprovedResultActions(null, null, null, null, new SimpleMeterRegistry());
        coordinator.setApprovedResultActions(real);
        var request = approvedByAlice();
        request.setTaskApprovals(Map.of(task.id(), "APPROVED"));
        doAnswer(inv -> {
            GroupConversation written = inv.getArgument(0);
            var record = written.getTaskList().getApprovedActions().get(task.id());
            assertNotNull(record, "the record travels in the approval's own write");
            assertEquals(ApprovedActionStatus.PENDING, record.status());
            assertEquals(URI + "#postComment", record.target());
            assertEquals("alice", record.approvedBy());
            return null;
        }).when(conversationStore).updateIfStateAndPause(any(), eq(GroupConversationState.AWAITING_APPROVAL), anyString());

        coordinator.resumeDiscussion(GC_ID, request, null);

        verify(conversationStore).updateIfStateAndPause(any(), eq(GroupConversationState.AWAITING_APPROVAL), anyString());
    }

    @Test
    void aTaskRejection_recordsNoAction() throws Exception {
        var real = new GroupApprovedResultActions(null, null, null, null, new SimpleMeterRegistry());
        coordinator.setApprovedResultActions(real);
        var request = approvedByAlice();
        request.setTaskApprovals(Map.of(task.id(), "REJECTED"));

        coordinator.resumeDiscussion(GC_ID, request, null);

        assertTrue(gc.getTaskList().getApprovedActions().isEmpty());
        assertEquals(SharedTaskList.TaskStatus.FAILED, gc.getTaskList().findById(task.id()).status());
    }
    @Test
    void anUnreadableGroupConfig_refusesATaskApproval_andLeavesThePauseAsItWas() throws Exception {
        // The config read fails, so the phase's onApproved is unknown. A decision with
        // the matching plan passes plan binding even under the strict fallback; it must
        // still not complete the task, or the approval would be consumed without the
        // action record it owes.
        when(groupStore.read(GROUP_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("store down"));
        var real = new GroupApprovedResultActions(null, null, null, null, new SimpleMeterRegistry());
        coordinator.setApprovedResultActions(real);
        var request = approvedByAlice();
        request.getDecision().setPlanHash(HitlPlanHash.forGroup(gc));
        request.setTaskApprovals(Map.of(task.id(), "APPROVED"));

        var refused = assertThrows(IResourceStore.ResourceStoreException.class, () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertTrue(refused.getMessage().contains("retry"), refused.getMessage());
        assertEquals(SharedTaskList.TaskStatus.AWAITING_APPROVAL, gc.getTaskList().findById(task.id()).status(),
                "the task still awaits a decision");
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        assertTrue(gc.getTaskList().getApprovedActions().isEmpty());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
        verify(conversationStore, never()).updateIfState(any(), any());
        verify(executorService, never()).submit(any(Runnable.class));
    }

    @Test
    void anUnreadableGroupConfig_refusesTheApproveAllShortcutToo() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("store down"));
        var request = approvedByAlice();
        request.getDecision().setPlanHash(HitlPlanHash.forGroup(gc));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertEquals(SharedTaskList.TaskStatus.AWAITING_APPROVAL, gc.getTaskList().findById(task.id()).status());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
    }

    /**
     * The paused phase renamed while the discussion waited: the bookmark no longer
     * matches.
     */
    private void renamePausedPhase() {
        var renamed = new DiscussionPhase("Redraft", PhaseType.EXECUTE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1,
                true, null, false, null, null, new OnApprovedConfig(new ApprovedAction(null, URI, "postComment"), null));
        when(groupConversationService.effectivePhases(any(), eq(config))).thenReturn(List.of(renamed));
    }

    @Test
    void aRenamedPausedPhase_refusesATaskApproval_beforeCompletingTheTask() throws Exception {
        // Recording would be skipped for the drifted phase, while the completion would
        // persist with the resume CAS; the resumed leg restores the pause but not the
        // task, so a retry after the fix would find nothing awaiting and owe nothing.
        renamePausedPhase();
        var real = new GroupApprovedResultActions(null, null, null, null, new SimpleMeterRegistry());
        coordinator.setApprovedResultActions(real);
        var request = approvedByAlice();
        request.setTaskApprovals(Map.of(task.id(), "APPROVED"));

        var refused = assertThrows(IResourceStore.ResourceStoreException.class, () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertTrue(refused.getMessage().contains("changed while paused"), refused.getMessage());
        assertEquals(SharedTaskList.TaskStatus.AWAITING_APPROVAL, gc.getTaskList().findById(task.id()).status(),
                "the task still awaits a decision");
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        assertEquals("Draft", gc.getPausedPhaseName(), "the bookmark is untouched");
        assertTrue(gc.getTaskList().getApprovedActions().isEmpty());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
        verify(conversationStore, never()).updateIfState(any(), any());
        verify(executorService, never()).submit(any(Runnable.class));
    }

    @Test
    void aRemovedPausedPhase_refusesTheApproveAllShortcut() throws Exception {
        when(groupConversationService.effectivePhases(any(), eq(config))).thenReturn(List.of());
        var request = approvedByAlice();

        var refused = assertThrows(IResourceStore.ResourceStoreException.class, () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertTrue(refused.getMessage().contains("no phase at index 0"), refused.getMessage());
        assertEquals(SharedTaskList.TaskStatus.AWAITING_APPROVAL, gc.getTaskList().findById(task.id()).status());
        verify(actions, never()).recordApprovals(any(), any(), anyInt(), any(), any());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
    }

    @Test
    void aRenamedPausedPhase_stillAppliesATaskRejection_whichOwesNoAction() throws Exception {
        renamePausedPhase();
        var request = approvedByAlice();
        request.setTaskApprovals(Map.of(task.id(), "REJECTED"));

        coordinator.resumeDiscussion(GC_ID, request, null);

        assertEquals(SharedTaskList.TaskStatus.FAILED, gc.getTaskList().findById(task.id()).status());
        verify(actions, never()).recordApprovals(any(), any(), anyInt(), any(), any());
    }

    @Test
    void bookmarkedPhaseDrift_checksIndexAndName() {
        var draft = config.getPhases().getFirst();
        assertNull(GroupHitlCoordinator.bookmarkedPhaseDrift(gc, List.of(draft)));
        gc.setPausedPhaseName(null);
        assertNull(GroupHitlCoordinator.bookmarkedPhaseDrift(gc, List.of(draft)), "a nameless pause is checked by index only");
        assertNotNull(GroupHitlCoordinator.bookmarkedPhaseDrift(gc, List.of()));
        gc.setPausedPhaseName("Other");
        assertNotNull(GroupHitlCoordinator.bookmarkedPhaseDrift(gc, List.of(draft)));
    }

    @Test
    void anUnreadableGroupConfig_stillAppliesATaskRejection_whichOwesNoAction() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("store down"));
        var request = approvedByAlice();
        request.getDecision().setPlanHash(HitlPlanHash.forGroup(gc));
        request.setTaskApprovals(Map.of(task.id(), "REJECTED"));

        coordinator.resumeDiscussion(GC_ID, request, null);

        assertEquals(SharedTaskList.TaskStatus.FAILED, gc.getTaskList().findById(task.id()).status());
        verify(conversationStore).updateIfStateAndPause(eq(gc), eq(GroupConversationState.AWAITING_APPROVAL), anyString());
    }
}
