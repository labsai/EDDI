/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ReviewerEdit;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cadence writeback under {@code hitlConfig.approvalCompletesBacklogTask}: a
 * task a person approved at a TASK gate stays done, instead of returning to
 * PENDING and running again on the next fire.
 */
class TeamCadenceApprovalWritebackTest {

    private static final String GROUP_ID = "group-1";
    private static final String GC_ID = "gc-1";

    private IGroupWorkspaceStore workspaceStore;
    private IAgentGroupStore groupStore;
    private TeamCadenceService service;

    @BeforeEach
    void setUp() throws Exception {
        workspaceStore = mock(IGroupWorkspaceStore.class);
        when(workspaceStore.casRunningDiscussion(any())).thenReturn(true);
        groupStore = mock(IAgentGroupStore.class);
        service = new TeamCadenceService(workspaceStore, mock(IGroupConversationStore.class),
                mock(GroupConversationService.class), mock(ITemplatingEngine.class), new SimpleMeterRegistry(),
                TeamCadenceService.DEFAULT_CLAIM_TTL);
        service.initMetrics();
        service.groupStore = groupStore;
    }

    private void configure(Boolean approvalCompletesBacklogTask) throws Exception {
        var config = new AgentGroupConfiguration();
        if (approvalCompletesBacklogTask != null) {
            var hitl = new AgentGroupConfiguration.HitlConfig();
            hitl.setGranularity(HitlGranularity.TASK);
            hitl.setApprovalCompletesBacklogTask(approvalCompletesBacklogTask);
            config.setHitlConfig(hitl);
        }
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resourceId(3));
        when(groupStore.read(GROUP_ID, 3)).thenReturn(config);
    }

    private static IResourceStore.IResourceId resourceId(int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    /** A workspace whose running cadence discussion pulled {@code tasks}. */
    private GroupWorkspace pulled(TaskItem... tasks) {
        var workspace = new GroupWorkspace();
        workspace.setId("ws-1");
        workspace.setGroupId(GROUP_ID);
        workspace.addCadence(new Cadence("cadence-1", "sched-1", null, 5, null, "pm@example.com"));
        for (TaskItem task : tasks) {
            workspace.getBacklog().addTask(task);
            workspace.getBacklog().updateTask(new TaskItem(task.id(), task.subject(), task.description(),
                    TaskStatus.IN_PROGRESS, null, null, task.dependsOnIds(), null, null, false, task.priority(),
                    task.createdAt(), null));
        }
        workspace.setRunningDiscussionId(GC_ID);
        workspace.setPulledTaskIds(List.of(tasks).stream().map(TaskItem::id).toList());
        return workspace;
    }

    private static GroupConversation completed(SharedTaskList discussionTasks) {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setState(GroupConversationState.COMPLETED);
        gc.setTaskList(discussionTasks);
        return gc;
    }

    /** Adds {@code subject} to {@code list} and walks it to AWAITING_APPROVAL. */
    private static String awaitingApproval(SharedTaskList list, String subject, String agentId, String result) {
        String id = list.addTask(new TaskItem(subject, "", 0)).id();
        list.assignTask(id, agentId, agentId);
        list.startTask(id);
        list.submitForApproval(id, result);
        return id;
    }

    @Test
    @DisplayName("default (switch absent): a human-approved task returns to PENDING, as before")
    void defaultIsUnchanged() throws Exception {
        configure(null);
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        discussion.approveTask(awaitingApproval(discussion, "Publish the post", "writer", "the post"), "alice");

        assertTrue(service.writebackCompleted(workspace, completed(discussion)));

        TaskItem after = workspace.getBacklog().findById(post.id());
        assertEquals(TaskStatus.PENDING, after.status());
        assertEquals(0, workspace.getMetrics().getTasksApproved());
        assertEquals(1, workspace.getMetrics().getPerMemberStats().get("writer").getTasksFailed());
    }

    @Test
    @DisplayName("switch explicitly false: unchanged as well")
    void explicitFalseIsUnchanged() throws Exception {
        configure(false);
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        discussion.approveTask(awaitingApproval(discussion, "Publish the post", "writer", "the post"), "alice");

        service.writebackCompleted(workspace, completed(discussion));

        assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(post.id()).status());
    }

    @Test
    @DisplayName("switch on: a human-approved task stays done, COMPLETED with its result, counted as approved")
    void approvedTaskStaysDone() throws Exception {
        configure(true);
        var post = new TaskItem("Publish the post", "original description", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        discussion.approveTask(awaitingApproval(discussion, "Publish the post", "writer", "the post"), "alice");

        assertTrue(service.writebackCompleted(workspace, completed(discussion)));

        TaskItem after = workspace.getBacklog().findById(post.id());
        assertEquals(TaskStatus.COMPLETED, after.status());
        assertFalse(after.verified(), "approved is not verified");
        assertEquals("the post", after.result());
        assertEquals("original description", after.description(), "no retry feedback appended");
        assertEquals("writer", after.assignedAgentId());
        assertEquals(1, workspace.getMetrics().getTasksApproved());
        assertEquals(0, workspace.getMetrics().getTasksVerified(), "tasksVerified keeps meaning verified");
        assertEquals(1, workspace.getMetrics().getPerMemberStats().get("writer").getTasksApproved());
        assertEquals(0, workspace.getMetrics().getPerMemberStats().get("writer").getTasksFailed());
        assertTrue(workspace.getBacklog().findExecutableTasks().isEmpty(), "the next fire has nothing to pull");
    }

    @Test
    @DisplayName("switch on: an edited approval stores the approver's edited result on the backlog")
    void editedApprovalKeepsTheEdit() throws Exception {
        configure(true);
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        String id = awaitingApproval(discussion, "Publish the post", "writer", "draft with a typo");
        discussion.approveTaskWithEditedResult(id, "draft without the typo",
                new ReviewerEdit("alice", "2026-11-09T09:00:00Z", "abc", 17));

        service.writebackCompleted(workspace, completed(discussion));

        TaskItem after = workspace.getBacklog().findById(post.id());
        assertEquals(TaskStatus.COMPLETED, after.status());
        assertEquals("draft without the typo", after.result());
    }

    @Test
    @DisplayName("switch on: a task COMPLETED in an ungated phase (no approval on record) still returns to PENDING")
    void ungatedCompletionStillRetries() throws Exception {
        configure(true);
        var task = new TaskItem("Research", "", 1);
        var workspace = pulled(task);
        var discussion = new SharedTaskList();
        String id = discussion.addTask(new TaskItem("Research", "", 0)).id();
        discussion.assignTask(id, "researcher", "Researcher");
        discussion.startTask(id);
        discussion.completeTask(id, "notes");

        service.writebackCompleted(workspace, completed(discussion));

        TaskItem after = workspace.getBacklog().findById(task.id());
        assertEquals(TaskStatus.PENDING, after.status());
        assertTrue(after.description().contains("notes"), "the usual retry feedback");
        assertEquals(0, workspace.getMetrics().getTasksApproved());
    }

    @Test
    @DisplayName("switch on: a timeout auto-approval (system:timeout) does not count — it is a decision nobody made")
    void timeoutApprovalDoesNotCount() throws Exception {
        configure(true);
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        discussion.approveTask(awaitingApproval(discussion, "Publish the post", "writer", "the post"), "system:timeout");

        service.writebackCompleted(workspace, completed(discussion));

        assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(post.id()).status());
        assertEquals(0, workspace.getMetrics().getTasksApproved());
    }

    @Test
    @DisplayName("switch on: an approval with no authenticated decider still counts (auth disabled is still a person)")
    void unnamedApproverCounts() throws Exception {
        configure(true);
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        discussion.approveTask(awaitingApproval(discussion, "Publish the post", "writer", "the post"));

        service.writebackCompleted(workspace, completed(discussion));

        assertEquals(TaskStatus.COMPLETED, workspace.getBacklog().findById(post.id()).status());
    }

    @Test
    @DisplayName("switch on: approved and then failed by a later VERIFY returns to PENDING")
    void approvedThenFailedVerifyRetries() throws Exception {
        configure(true);
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        String id = awaitingApproval(discussion, "Publish the post", "writer", "the post");
        discussion.approveTask(id, "alice");
        discussion.verifyTask(id, false, "wrong channel");

        service.writebackCompleted(workspace, completed(discussion));

        TaskItem after = workspace.getBacklog().findById(post.id());
        assertEquals(TaskStatus.PENDING, after.status());
        assertTrue(after.description().contains("wrong channel"));
    }

    @Test
    @DisplayName("switch on: VERIFIED outcomes are still VERIFIED and counted as verified")
    void verifiedIsUntouched() throws Exception {
        configure(true);
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        String id = awaitingApproval(discussion, "Publish the post", "writer", "the post");
        discussion.approveTask(id, "alice");
        discussion.verifyTask(id, true, "good");

        service.writebackCompleted(workspace, completed(discussion));

        assertEquals(TaskStatus.VERIFIED, workspace.getBacklog().findById(post.id()).status());
        assertEquals(1, workspace.getMetrics().getTasksVerified());
        assertEquals(0, workspace.getMetrics().getTasksApproved());
    }

    @Test
    @DisplayName("an unreadable group config falls back to the default rule instead of failing the writeback")
    void unreadableConfigFallsBackToDefault() throws Exception {
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        discussion.approveTask(awaitingApproval(discussion, "Publish the post", "writer", "the post"), "alice");

        assertTrue(service.writebackCompleted(workspace, completed(discussion)));

        assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(post.id()).status());
    }

    @Test
    @DisplayName("without a group store (direct construction) the default rule applies")
    void noGroupStoreIsDefault() {
        service.groupStore = null;
        var post = new TaskItem("Publish the post", "", 1);
        var workspace = pulled(post);
        var discussion = new SharedTaskList();
        discussion.approveTask(awaitingApproval(discussion, "Publish the post", "writer", "the post"), "alice");

        service.writebackCompleted(workspace, completed(discussion));

        assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(post.id()).status());
        assertNull(workspace.getBacklog().findById(post.id()).result());
    }
}
