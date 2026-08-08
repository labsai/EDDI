/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I13 — {@link TeamCadenceService}: the fire protocol (reconcile → pull → claim
 * → run), the writeback matrix, and every deliberate skip.
 */
class TeamCadenceServiceTest {

    private static final String GROUP_ID = "group-1";
    private static final String CADENCE_ID = "cadence-1";
    private static final String GC_ID = "gc-1";

    private IGroupWorkspaceStore workspaceStore;
    private IGroupConversationStore conversationStore;
    private GroupConversationService groupConversationService;
    private ITemplatingEngine templatingEngine;
    private TeamCadenceService service;

    @BeforeEach
    void setUp() {
        workspaceStore = mock(IGroupWorkspaceStore.class);
        conversationStore = mock(IGroupConversationStore.class);
        groupConversationService = mock(GroupConversationService.class);
        templatingEngine = mock(ITemplatingEngine.class);
        service = new TeamCadenceService(workspaceStore, conversationStore, groupConversationService,
                templatingEngine, new SimpleMeterRegistry());
        service.initMetrics();
    }

    private Map<String, Object> metadata() {
        return Map.of(TeamCadenceService.METADATA_TYPE_KEY, TeamCadenceService.METADATA_TYPE_CADENCE,
                TeamCadenceService.METADATA_GROUP_ID_KEY, GROUP_ID,
                TeamCadenceService.METADATA_CADENCE_ID_KEY, CADENCE_ID);
    }

    private GroupWorkspace workspace(Cadence cadence, TaskItem... backlogTasks) throws Exception {
        var workspace = new GroupWorkspace();
        workspace.setId("ws-1");
        workspace.setGroupId(GROUP_ID);
        if (cadence != null) {
            workspace.getCadences().add(cadence);
        }
        for (TaskItem task : backlogTasks) {
            workspace.getBacklog().addTask(task);
        }
        when(workspaceStore.find(GROUP_ID)).thenReturn(workspace);
        lenient().when(workspaceStore.casRunningDiscussion(any(), anyString())).thenReturn(true);
        return workspace;
    }

    private Cadence cadence(int maxTasksPerRun, Double maxCostPerRun) {
        return new Cadence(CADENCE_ID, "sched-1", null, maxTasksPerRun, maxCostPerRun, "pm@example.com");
    }

    private GroupConversation startedGc() throws Exception {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setState(GroupConversationState.IN_PROGRESS);
        when(groupConversationService.startCadenceDiscussionAsync(eq(GROUP_ID), anyString(), anyString(), anyList(),
                any(), any())).thenReturn(gc);
        return gc;
    }

    // =================================================================
    // The fire protocol
    // =================================================================

    @Test
    @DisplayName("a fire pulls the top-priority executable tasks, starts the discussion, and claims the workspace")
    void fire_pullsByPriority_startsAndClaims() throws Exception {
        var workspace = workspace(cadence(2, 3.50),
                new TaskItem("Low", "low prio", 1),
                new TaskItem("Urgent", "do first", 9),
                new TaskItem("Mid", "do second", 5));
        startedGc();

        var result = service.processScheduledFire(metadata());

        assertTrue(result.isSuccess(), String.valueOf(result.error()));
        assertNull(result.skippedReason());
        assertEquals(GC_ID, result.discussionId());
        assertEquals(2, result.tasksPulled());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskDefinition>> tasksCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(groupConversationService).startCadenceDiscussionAsync(eq(GROUP_ID), questionCaptor.capture(),
                eq("pm@example.com"), tasksCaptor.capture(), eq(3.50), any());
        assertEquals(List.of("Urgent", "Mid"), tasksCaptor.getValue().stream().map(TaskDefinition::subject).toList(),
                "top-N by priority, highest first — 'Low' stays on the backlog");
        assertTrue(questionCaptor.getValue().contains("Urgent"), questionCaptor.getValue());

        assertEquals(GC_ID, workspace.getRunningDiscussionId());
        assertEquals(2, workspace.getPulledTaskIds().size());
        long inProgress = workspace.getBacklog().getTasks().stream()
                .filter(t -> t.status() == TaskStatus.IN_PROGRESS).count();
        assertEquals(2, inProgress, "pulled tasks are marked while the discussion owns them");
        verify(workspaceStore).casRunningDiscussion(workspace, GroupWorkspace.NO_RUNNING_DISCUSSION);
    }

    @Test
    void fire_emptyBacklog_skipsWithoutADiscussion() throws Exception {
        workspace(cadence(5, null));

        var result = service.processScheduledFire(metadata());

        assertTrue(result.isSuccess());
        assertNotNull(result.skippedReason());
        verify(groupConversationService, never()).startCadenceDiscussionAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void fire_missingWorkspaceOrCadence_fails() throws Exception {
        when(workspaceStore.find(GROUP_ID)).thenReturn(null);
        assertFalse(service.processScheduledFire(metadata()).isSuccess());

        workspace(null, new TaskItem("T", "", 0)); // workspace exists, cadence does not
        var result = service.processScheduledFire(metadata());
        assertFalse(result.isSuccess());
        assertTrue(result.error().contains(CADENCE_ID));
    }

    @Test
    void fire_missingMetadata_fails() {
        assertFalse(service.processScheduledFire(null).isSuccess());
        assertFalse(service.processScheduledFire(Map.of()).isSuccess());
    }

    @Test
    @DisplayName("a previous discussion still in flight (or paused for a human) skips the fire")
    void fire_previousStillRunning_skips() throws Exception {
        var workspace = workspace(cadence(5, null), new TaskItem("T", "", 0));
        workspace.setRunningDiscussionId("older-gc");
        var runningGc = new GroupConversation();
        runningGc.setId("older-gc");
        runningGc.setState(GroupConversationState.AWAITING_APPROVAL);
        when(conversationStore.read("older-gc")).thenReturn(runningGc);

        var result = service.processScheduledFire(metadata());

        assertTrue(result.isSuccess());
        assertTrue(result.skippedReason().contains("older-gc"));
        verify(groupConversationService, never()).startCadenceDiscussionAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a finished previous discussion is written back FIRST, then the new run starts")
    void fire_previousTerminal_reconcilesThenRuns() throws Exception {
        var workspace = workspace(cadence(5, null), new TaskItem("Next", "", 0));
        workspace.setRunningDiscussionId("done-gc");
        var doneGc = new GroupConversation();
        doneGc.setId("done-gc");
        doneGc.setState(GroupConversationState.COMPLETED);
        doneGc.setTotalCost(1.25);
        when(conversationStore.read("done-gc")).thenReturn(doneGc);
        startedGc();

        var result = service.processScheduledFire(metadata());

        assertTrue(result.isSuccess());
        assertEquals(GC_ID, result.discussionId());
        assertEquals(1, workspace.getMetrics().getDiscussions(), "the finished run was settled");
        assertEquals(1.25, workspace.getMetrics().getTotalCost(), 1e-9);
    }

    @Test
    @DisplayName("a lost claim cancels the just-started discussion and stands down")
    void fire_lostClaim_cancelsAndSkips() throws Exception {
        workspace(cadence(5, null), new TaskItem("T", "", 0));
        when(workspaceStore.casRunningDiscussion(any(), anyString())).thenReturn(false);
        startedGc();

        var result = service.processScheduledFire(metadata());

        assertTrue(result.isSuccess());
        assertNotNull(result.skippedReason());
        verify(groupConversationService).cancelDiscussion(eq(GC_ID), any());
    }

    @Test
    @DisplayName("a broken input template degrades to the plain backlog summary — a cadence never stops over prose")
    void fire_templateFailure_usesPlainSummary() throws Exception {
        var cadence = new Cadence(CADENCE_ID, "sched-1", "{broken", 5, null, "pm@example.com");
        workspace(cadence, new TaskItem("Fix the flaky test", "", 0));
        when(templatingEngine.processTemplate(anyString(), any())).thenThrow(new RuntimeException("bad template"));
        startedGc();

        var result = service.processScheduledFire(metadata());

        assertTrue(result.isSuccess());
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(groupConversationService).startCadenceDiscussionAsync(eq(GROUP_ID), questionCaptor.capture(),
                anyString(), anyList(), any(), any());
        assertTrue(questionCaptor.getValue().contains("Fix the flaky test"));
    }

    // =================================================================
    // Writeback matrix
    // =================================================================

    private GroupWorkspace pulledWorkspace(TaskItem taskA, TaskItem taskB) throws Exception {
        var workspace = workspace(cadence(5, null), taskA, taskB);
        workspace.setRunningDiscussionId(GC_ID);
        workspace.setPulledTaskIds(List.of(taskA.id(), taskB.id()));
        workspace.getBacklog().updateTask(new TaskItem(taskA.id(), taskA.subject(), taskA.description(),
                TaskStatus.IN_PROGRESS, null, null, taskA.dependsOnIds(), null, null, false, taskA.priority(),
                taskA.createdAt(), null));
        workspace.getBacklog().updateTask(new TaskItem(taskB.id(), taskB.subject(), taskB.description(),
                TaskStatus.IN_PROGRESS, null, null, taskB.dependsOnIds(), null, null, false, taskB.priority(),
                taskB.createdAt(), null));
        return workspace;
    }

    @Test
    @DisplayName("COMPLETED writeback: VERIFIED stays (credited to the assignee), anything else returns to PENDING with feedback")
    void writeback_completed_matrix() throws Exception {
        var taskA = new TaskItem("Ship feature", "", 3);
        var taskB = new TaskItem("Write docs", "original description", 1);
        var workspace = pulledWorkspace(taskA, taskB);

        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setState(GroupConversationState.COMPLETED);
        gc.setTotalCost(2.0);
        var discussionTasks = new SharedTaskList();
        var doneA = discussionTasks.addTask(new TaskItem("Ship feature", "", 0));
        discussionTasks.assignTask(doneA.id(), "agent-a", "Agent A");
        discussionTasks.startTask(doneA.id());
        discussionTasks.completeTask(doneA.id(), "shipped");
        discussionTasks.verifyTask(doneA.id(), true, "looks great");
        var failedB = discussionTasks.addTask(new TaskItem("Write docs", "", 0));
        discussionTasks.assignTask(failedB.id(), "agent-b", "Agent B");
        discussionTasks.startTask(failedB.id());
        discussionTasks.completeTask(failedB.id(), "half done");
        discussionTasks.verifyTask(failedB.id(), false, "missing the API section");
        gc.setTaskList(discussionTasks);

        service.writebackCompleted(workspace, gc);

        TaskItem backlogA = workspace.getBacklog().findById(taskA.id());
        assertEquals(TaskStatus.VERIFIED, backlogA.status());
        assertTrue(backlogA.verified());
        TaskItem backlogB = workspace.getBacklog().findById(taskB.id());
        assertEquals(TaskStatus.PENDING, backlogB.status(), "failed work returns for the next run — the retry loop");
        assertTrue(backlogB.description().contains("missing the API section"),
                "the reviewer's feedback rides the description into the next attempt: " + backlogB.description());
        assertTrue(backlogB.description().contains("original description"), "the original description is preserved");

        var metrics = workspace.getMetrics();
        assertEquals(1, metrics.getDiscussions());
        assertEquals(1, metrics.getTasksVerified());
        assertEquals(2.0, metrics.getTotalCost(), 1e-9);
        assertNotNull(metrics.getLastRunAt());
        assertEquals(1, metrics.getPerMemberStats().get("agent-a").getTasksVerified());
        assertEquals(1, metrics.getPerMemberStats().get("agent-b").getTasksFailed());

        assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
        assertTrue(workspace.getPulledTaskIds().isEmpty());
        verify(workspaceStore).update(workspace);
    }

    @Test
    @DisplayName("FAILED/CANCELLED writeback: every pulled task returns to PENDING untouched")
    void writeback_failure_returnsTasks() throws Exception {
        var taskA = new TaskItem("A", "", 0);
        var taskB = new TaskItem("B", "", 0);
        var workspace = pulledWorkspace(taskA, taskB);
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setState(GroupConversationState.CANCELLED);
        gc.setTotalCost(0.5);

        service.writebackFailure(workspace, gc);

        assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(taskA.id()).status());
        assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(taskB.id()).status());
        assertEquals(1, workspace.getMetrics().getDiscussions());
        assertEquals(0.5, workspace.getMetrics().getTotalCost(), 1e-9);
        assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
    }

    @Test
    @DisplayName("a vanished discussion releases the claim instead of stalling every future fire")
    void reconcile_goneDiscussion_releasesClaim() throws Exception {
        var taskA = new TaskItem("A", "", 0);
        var workspace = workspace(cadence(5, null), taskA);
        workspace.setRunningDiscussionId("gone-gc");
        workspace.setPulledTaskIds(List.of(taskA.id()));
        when(conversationStore.read("gone-gc")).thenThrow(
                new ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException("gone"));

        assertTrue(service.reconcile(workspace));

        assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
        assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(taskA.id()).status());
    }

    @Test
    void reconcile_idleWorkspace_isANoOp() throws Exception {
        var workspace = workspace(cadence(5, null));

        assertTrue(service.reconcile(workspace));

        verify(workspaceStore, never()).update(any());
    }

    // =================================================================
    // Model + contract pins
    // =================================================================

    @Test
    void cadence_compactConstructor_defaultsTasksPerRun() {
        assertEquals(GroupWorkspace.DEFAULT_MAX_BACKLOG_TASKS_PER_RUN,
                new Cadence("c", "s", null, 0, null, "pm").maxBacklogTasksPerRun());
        assertEquals(3, new Cadence("c", "s", null, 3, null, "pm").maxBacklogTasksPerRun());
    }

    @Test
    void isTeamCadenceSchedule_matchesOnlyItsOwnMetadata() {
        assertTrue(TeamCadenceService.isTeamCadenceSchedule(metadata()));
        assertFalse(TeamCadenceService.isTeamCadenceSchedule(Map.of("dreamType", "dream_consolidation")));
        assertFalse(TeamCadenceService.isTeamCadenceSchedule(null));
    }
}
