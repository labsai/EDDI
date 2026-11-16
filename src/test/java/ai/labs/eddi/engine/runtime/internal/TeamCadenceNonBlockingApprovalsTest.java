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
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.ParkedRun;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Standing teams whose cadence runs pause for a person:
 * {@code hitlConfig.maxPausedCadenceRuns} (a paused run no longer holds the
 * whole team) and {@code hitlConfig.stoppedRunKeepsFinishedTasks} (a run that
 * stops keeps the tasks it finished).
 */
class TeamCadenceNonBlockingApprovalsTest {

    private static final String GROUP_ID = "group-1";
    private static final String CADENCE_ID = "cadence-1";
    private static final String PAUSED_ID = "gc-paused";
    private static final String NEW_ID = "gc-new";

    private IGroupWorkspaceStore workspaceStore;
    private IGroupConversationStore conversationStore;
    private GroupConversationService groupConversationService;
    private IAgentGroupStore groupStore;
    private TeamCadenceService service;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() throws Exception {
        workspaceStore = mock(IGroupWorkspaceStore.class);
        conversationStore = mock(IGroupConversationStore.class);
        groupConversationService = mock(GroupConversationService.class);
        groupStore = mock(IAgentGroupStore.class);
        meters = new SimpleMeterRegistry();
        service = new TeamCadenceService(workspaceStore, conversationStore, groupConversationService,
                mock(ITemplatingEngine.class), meters, Duration.ofHours(24));
        service.initMetrics();
        service.groupStore = groupStore;
        when(workspaceStore.casRunningDiscussion(any())).thenReturn(true);
    }

    // ---------------------------------------------------------------- helpers

    private AgentGroupConfiguration.HitlConfig configure(Integer maxPausedRuns, boolean keepFinished,
                                                         boolean approvalCompletes)
            throws Exception {
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setGranularity(HitlGranularity.TASK);
        hitl.setMaxPausedCadenceRuns(maxPausedRuns);
        hitl.setStoppedRunKeepsFinishedTasks(keepFinished);
        hitl.setApprovalCompletesBacklogTask(approvalCompletes);
        var config = new AgentGroupConfiguration();
        config.setHitlConfig(hitl);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resourceId());
        when(groupStore.read(GROUP_ID, 2)).thenReturn(config);
        return hitl;
    }

    private static IResourceStore.IResourceId resourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return 2;
            }
        };
    }

    private static Map<String, Object> metadata() {
        return Map.of(TeamCadenceService.METADATA_TYPE_KEY, TeamCadenceService.METADATA_TYPE_CADENCE,
                TeamCadenceService.METADATA_GROUP_ID_KEY, GROUP_ID,
                TeamCadenceService.METADATA_CADENCE_ID_KEY, CADENCE_ID);
    }

    /** A workspace with the given backlog; nothing claimed. */
    private static GroupWorkspace workspace(TaskItem... backlog) {
        var workspace = new GroupWorkspace();
        workspace.setId("ws-1");
        workspace.setGroupId(GROUP_ID);
        workspace.addCadence(new Cadence(CADENCE_ID, "sched-1", null, 5, null, "pm@example.com"));
        for (TaskItem task : backlog) {
            workspace.getBacklog().addTask(task);
        }
        return workspace;
    }

    /** Marks {@code tasks} IN_PROGRESS and returns their ids. */
    private static List<String> hold(GroupWorkspace workspace, TaskItem... tasks) {
        List<String> ids = new ArrayList<>();
        for (TaskItem task : tasks) {
            TaskItem current = workspace.getBacklog().findById(task.id());
            workspace.getBacklog().updateTask(new TaskItem(current.id(), current.subject(), current.description(),
                    TaskStatus.IN_PROGRESS, null, null, current.dependsOnIds(), null, null, false, current.priority(),
                    current.createdAt(), null));
            ids.add(task.id());
        }
        return ids;
    }

    /**
     * Gives {@code workspace} a claim-holding run {@code discussionId} over
     * {@code tasks}.
     */
    private static void claimed(GroupWorkspace workspace, String discussionId, Instant claimedAt, TaskItem... tasks) {
        workspace.setPulledTaskIds(hold(workspace, tasks));
        workspace.setRunningDiscussionId(discussionId);
        workspace.setClaimedAt(claimedAt);
    }

    private GroupConversation discussion(String id, GroupConversationState state) throws Exception {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setGroupId(GROUP_ID);
        gc.setState(state);
        when(conversationStore.read(id)).thenReturn(gc);
        // The real cancel of a discussion no leg is running: CAS to CANCELLED.
        when(groupConversationService.cancelDiscussion(eq(id), any())).thenAnswer(inv -> {
            gc.setState(GroupConversationState.CANCELLED);
            return true;
        });
        return gc;
    }

    private void newRunPrepared() throws Exception {
        var gc = new GroupConversation();
        gc.setId(NEW_ID);
        gc.setGroupId(GROUP_ID);
        gc.setState(GroupConversationState.IN_PROGRESS);
        var prepared = mock(GroupConversationService.CadenceDiscussion.class);
        when(prepared.conversation()).thenReturn(gc);
        when(groupConversationService.prepareCadenceDiscussion(eq(GROUP_ID), anyString(), anyString(), anyList(),
                any(), any())).thenReturn(prepared);
    }

    private GroupWorkspace persisted() throws Exception {
        ArgumentCaptor<GroupWorkspace> captor = ArgumentCaptor.forClass(GroupWorkspace.class);
        verify(workspaceStore, atLeastOnce()).casRunningDiscussion(captor.capture());
        return captor.getValue();
    }

    private static String awaitingApproval(SharedTaskList list, String subject) {
        String id = list.addTask(new TaskItem(subject, "", 0)).id();
        list.assignTask(id, "writer", "Writer");
        list.startTask(id);
        list.submitForApproval(id, "draft of " + subject);
        return id;
    }

    private static void verified(SharedTaskList list, String subject) {
        String id = list.addTask(new TaskItem(subject, "", 0)).id();
        list.assignTask(id, "writer", "Writer");
        list.startTask(id);
        list.completeTask(id, "done: " + subject);
        list.verifyTask(id, true, "checked");
    }

    // ------------------------------------------------------------ parking

    @Nested
    @DisplayName("hitlConfig.maxPausedCadenceRuns")
    class Parking {

        private TaskItem held;
        private TaskItem free;
        private GroupWorkspace workspace;
        private final Instant claimedAt = Instant.now().minus(Duration.ofHours(2));

        @BeforeEach
        void paused() throws Exception {
            held = new TaskItem("Draft the post", "", 5);
            free = new TaskItem("Answer the inbox", "", 3);
            workspace = workspace(held, free);
            claimed(workspace, PAUSED_ID, claimedAt, held);
            when(workspaceStore.find(GROUP_ID)).thenReturn(workspace);
            discussion(PAUSED_ID, GroupConversationState.AWAITING_APPROVAL);
        }

        @Test
        @DisplayName("absent (default): a paused run holds the team and the fire is skipped, as before")
        void defaultSkips() throws Exception {
            configure(null, false, false);

            var result = service.processScheduledFire("sched-1", metadata());

            assertTrue(result.isSuccess());
            assertTrue(result.skippedReason().contains(PAUSED_ID), result.skippedReason());
            verify(groupConversationService, never()).prepareCadenceDiscussion(any(), any(), any(), any(), any(), any());
            verify(workspaceStore, never()).casRunningDiscussion(any());
        }

        @Test
        @DisplayName("0 behaves exactly like absent")
        void zeroSkips() throws Exception {
            configure(0, false, false);

            assertTrue(service.processScheduledFire("sched-1", metadata()).skippedReason() != null);
            verify(groupConversationService, never()).prepareCadenceDiscussion(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("on: the paused run is parked with its tasks and claim time, and a new run takes only the free tasks")
        void parksAndStartsANewRun() throws Exception {
            configure(1, false, false);
            newRunPrepared();

            var result = service.processScheduledFire("sched-1", metadata());

            assertTrue(result.isSuccess(), String.valueOf(result.error()));
            assertNull(result.skippedReason());
            assertEquals(NEW_ID, result.discussionId());
            assertEquals(1, result.tasksPulled());

            GroupWorkspace saved = persisted();
            assertEquals(NEW_ID, saved.getRunningDiscussionId());
            assertEquals(List.of(free.id()), saved.getPulledTaskIds());
            assertEquals(List.of(new ParkedRun(PAUSED_ID, claimedAt, List.of(held.id()))), saved.getParkedRuns());
            assertEquals(TaskStatus.IN_PROGRESS, saved.getBacklog().findById(held.id()).status(),
                    "the parked run still holds its task");
            assertEquals(TaskStatus.IN_PROGRESS, saved.getBacklog().findById(free.id()).status());
            assertEquals(1.0, meters.counter("eddi_team_cadence_runs_parked_total").count());
            verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
        }

        @Test
        @DisplayName("on: once the limit is reached, fires are skipped again and say why")
        void limitReachedSkips() throws Exception {
            configure(1, false, false);
            workspace.setParkedRuns(List.of(new ParkedRun("gc-older", Instant.now(), List.of())));
            discussion("gc-older", GroupConversationState.AWAITING_APPROVAL);

            var result = service.processScheduledFire("sched-1", metadata());

            assertTrue(result.skippedReason().contains("maxPausedCadenceRuns"), result.skippedReason());
            verify(groupConversationService, never()).prepareCadenceDiscussion(any(), any(), any(), any(), any(), any());
            verify(workspaceStore, never()).casRunningDiscussion(any());
        }

        @Test
        @DisplayName("on: a run that is still working is never parked")
        void workingRunIsNotParked() throws Exception {
            configure(3, false, false);
            discussion(PAUSED_ID, GroupConversationState.IN_PROGRESS);

            var result = service.processScheduledFire("sched-1", metadata());

            assertTrue(result.skippedReason() != null);
            verify(groupConversationService, never()).prepareCadenceDiscussion(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("on: a run waiting on a human member's turn is parked too")
        void humanInputPauseIsParked() throws Exception {
            configure(1, false, false);
            discussion(PAUSED_ID, GroupConversationState.AWAITING_HUMAN_INPUT);
            newRunPrepared();

            assertEquals(NEW_ID, service.processScheduledFire("sched-1", metadata()).discussionId());
            assertEquals(PAUSED_ID, persisted().getParkedRuns().getFirst().discussionId());
        }

        @Test
        @DisplayName("on: with every free task taken there is nothing to start, and the park is not persisted")
        void nothingFreeNothingWritten() throws Exception {
            configure(1, false, false);
            hold(workspace, free);

            var result = service.processScheduledFire("sched-1", metadata());

            assertEquals("No executable backlog tasks", result.skippedReason());
            verify(workspaceStore, never()).casRunningDiscussion(any());
        }

        @Test
        @DisplayName("on: a claim lost to an unrelated write re-parks on the fresh copy and lands")
        void claimRetryReparksOnFreshCopy() throws Exception {
            configure(1, false, false);
            newRunPrepared();
            var fresh = workspace(held, free);
            fresh.getBacklog().setTasks(new ArrayList<>(workspace.getBacklog().getTasks()));
            fresh.setRunningDiscussionId(PAUSED_ID);
            fresh.setClaimedAt(claimedAt);
            fresh.setPulledTaskIds(List.of(held.id()));
            when(workspaceStore.find(GROUP_ID)).thenReturn(workspace, fresh);
            when(workspaceStore.casRunningDiscussion(any())).thenReturn(false, true);

            var result = service.processScheduledFire("sched-1", metadata());

            assertEquals(NEW_ID, result.discussionId(), String.valueOf(result.skippedReason()));
            assertEquals(NEW_ID, fresh.getRunningDiscussionId());
            assertEquals(List.of(PAUSED_ID), fresh.getParkedRuns().stream().map(ParkedRun::discussionId).toList());
        }

        @Test
        @DisplayName("on: a claim lost to another fire that took the workspace stands down")
        void claimLostToAnotherRun() throws Exception {
            configure(1, false, false);
            newRunPrepared();
            var fresh = workspace(held, free);
            fresh.setRunningDiscussionId("gc-other-pod");
            when(workspaceStore.find(GROUP_ID)).thenReturn(workspace, fresh);
            when(workspaceStore.casRunningDiscussion(any())).thenReturn(false);

            var result = service.processScheduledFire("sched-1", metadata());

            assertEquals("Lost the run claim to a concurrent fire", result.skippedReason());
            assertTrue(fresh.getParkedRuns().isEmpty(), "another pod's run is not ours to park");
        }
    }

    // ------------------------------------------------- reconciling parked runs

    @Nested
    @DisplayName("a stale-claim reclaim racing an approval")
    class ReclaimRace {

        private TaskItem task;
        private GroupWorkspace workspace;

        @BeforeEach
        void stale() {
            task = new TaskItem("Draft the post", "", 5);
            workspace = workspace(task);
            claimed(workspace, PAUSED_ID, Instant.now().minus(Duration.ofHours(30)), task);
        }

        /** One stored version of the discussion; each read returns its own copy. */
        private GroupConversation snapshot(GroupConversationState state, HitlPauseType pauseType,
                                           boolean taskVerified) {
            var gc = new GroupConversation();
            gc.setId(PAUSED_ID);
            gc.setGroupId(GROUP_ID);
            gc.setState(state);
            gc.setHitlPauseType(pauseType);
            var tasks = new SharedTaskList();
            if (taskVerified) {
                verified(tasks, "Draft the post");
            }
            gc.setTaskList(tasks);
            return gc;
        }

        private GroupConversation paused() {
            return snapshot(GroupConversationState.AWAITING_APPROVAL, HitlPauseType.TASK, false);
        }

        private TaskStatus status() {
            return workspace.getBacklog().findById(task.id()).status();
        }

        @Test
        @DisplayName("a run an approval resumed keeps its claim and its tasks — nothing is written from the pre-cancel snapshot")
        void resumedRunKeepsTheClaim() throws Exception {
            // The approval landed between the reclaim's read and its cancel: the run is
            // IN_PROGRESS again, so the cancel only signalled the live leg.
            when(conversationStore.read(PAUSED_ID)).thenReturn(paused(),
                    snapshot(GroupConversationState.IN_PROGRESS, null, false));

            assertFalse(service.reconcile(workspace));

            verify(groupConversationService).cancelDiscussion(PAUSED_ID, ControlSignal.CANCEL_GRACEFUL);
            assertEquals(PAUSED_ID, workspace.getRunningDiscussionId());
            assertEquals(TaskStatus.IN_PROGRESS, status());
            verify(workspaceStore, never()).casRunningDiscussion(any());
            assertEquals(0, meters.counter("eddi_team_cadence_claims_reclaimed_total").count(),
                    "no claim was released");
        }

        @Test
        @DisplayName("once the resumed leg ends, the run is settled from its final task list")
        void resumedRunIsSettledFromItsFinalState() throws Exception {
            when(conversationStore.read(PAUSED_ID)).thenReturn(paused(),
                    snapshot(GroupConversationState.IN_PROGRESS, null, false),
                    snapshot(GroupConversationState.COMPLETED, null, true));

            assertFalse(service.reconcile(workspace));
            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.VERIFIED, status(), "the task the resumed leg finished is not run again");
            assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
        }

        @Test
        @DisplayName("on: a task finished before the cancel ended the run is kept — read from the post-cancel state")
        void cancelledRunKeepsWhatItFinished() throws Exception {
            configure(null, true, false);
            when(conversationStore.read(PAUSED_ID)).thenReturn(paused(),
                    snapshot(GroupConversationState.CANCELLED, HitlPauseType.TASK, true));

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.VERIFIED, status());
            assertEquals(1, meters.counter("eddi_team_cadence_claims_reclaimed_total").count());
        }

        @Test
        @DisplayName("on: a PHASE rejection that lands before the post-cancel read keeps its rule — everything returns")
        void phaseRejectionKeepsItsRule() throws Exception {
            configure(null, true, false);
            when(conversationStore.read(PAUSED_ID)).thenReturn(paused(),
                    snapshot(GroupConversationState.REJECTED, HitlPauseType.PHASE, true));

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.PENDING, status(), "the person declined the phase's whole output");
        }

        @Test
        @DisplayName("a reclaim whose writeback loses the settle race is not counted — another reconciler released the claim")
        void lostReclaimIsNotCounted() throws Exception {
            when(conversationStore.read(PAUSED_ID)).thenReturn(paused(),
                    snapshot(GroupConversationState.CANCELLED, HitlPauseType.TASK, false));
            when(workspaceStore.casRunningDiscussion(any())).thenReturn(false);
            when(workspaceStore.find(GROUP_ID)).thenReturn(workspace(task));

            assertFalse(service.reconcile(workspace));

            assertEquals(0, meters.counter("eddi_team_cadence_claims_reclaimed_total").count());
        }
    }

    @Nested
    @DisplayName("a discussion that cannot be read")
    class UnreadableDiscussion {

        private TaskItem task;
        private GroupWorkspace workspace;

        @BeforeEach
        void unreadable() throws Exception {
            task = new TaskItem("Draft the post", "", 5);
            workspace = workspace(task);
            when(conversationStore.read(PAUSED_ID)).thenThrow(new IResourceStore.ResourceStoreException("timeout"));
        }

        @Test
        @DisplayName("is not treated as deleted: the run keeps its claim and its tasks")
        void keepsTheRun() throws Exception {
            claimed(workspace, PAUSED_ID, Instant.now().minus(Duration.ofHours(1)), task);

            assertFalse(service.reconcile(workspace));

            assertEquals(PAUSED_ID, workspace.getRunningDiscussionId());
            assertEquals(TaskStatus.IN_PROGRESS, workspace.getBacklog().findById(task.id()).status());
            verify(workspaceStore, never()).casRunningDiscussion(any());
        }

        @Test
        @DisplayName("a parked run keeps its place too")
        void keepsTheParkedRun() throws Exception {
            workspace.setParkedRuns(List.of(new ParkedRun(PAUSED_ID, Instant.now().minus(Duration.ofHours(1)),
                    hold(workspace, task))));

            assertTrue(service.reconcile(workspace), "nothing was lost; the claim slot is free");

            assertEquals(1, workspace.getParkedRuns().size());
            assertEquals(TaskStatus.IN_PROGRESS, workspace.getBacklog().findById(task.id()).status());
        }

        @Test
        @DisplayName("past the claim TTL it is cancelled but keeps its claim — releasing needs a read that shows it ended")
        void cancelledPastTheTtlButKept() throws Exception {
            claimed(workspace, PAUSED_ID, Instant.now().minus(Duration.ofHours(30)), task);

            assertFalse(service.reconcile(workspace));

            verify(groupConversationService).cancelDiscussion(PAUSED_ID, ControlSignal.CANCEL_GRACEFUL);
            assertEquals(PAUSED_ID, workspace.getRunningDiscussionId());
            assertEquals(TaskStatus.IN_PROGRESS, workspace.getBacklog().findById(task.id()).status());
            verify(workspaceStore, never()).casRunningDiscussion(any());
        }

        @Test
        @DisplayName("within the TTL it is not cancelled")
        void notCancelledWithinTheTtl() throws Exception {
            claimed(workspace, PAUSED_ID, Instant.now().minus(Duration.ofHours(1)), task);

            service.reconcile(workspace);

            verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
        }
    }

    @Nested
    @DisplayName("a cancel that reached the database but not the leg")
    class RemoteLegStillRunning {

        private TaskItem task;
        private GroupWorkspace workspace;

        @BeforeEach
        void remote() {
            task = new TaskItem("Draft the post", "", 5);
            workspace = workspace(task);
            // The node running the leg did not answer: the cancel's database fallback
            // stored CANCELLED, but the leg's group lease is still held.
            when(groupConversationService.isDiscussionLegRunning(PAUSED_ID)).thenReturn(true);
        }

        private GroupConversation stored(GroupConversationState state, boolean taskVerified) {
            var gc = new GroupConversation();
            gc.setId(PAUSED_ID);
            gc.setGroupId(GROUP_ID);
            gc.setState(state);
            var tasks = new SharedTaskList();
            if (taskVerified) {
                verified(tasks, "Draft the post");
            }
            gc.setTaskList(tasks);
            return gc;
        }

        private TaskStatus status() {
            return workspace.getBacklog().findById(task.id()).status();
        }

        @Test
        @DisplayName("reclaim: a post-cancel CANCELLED read keeps the claim while the leg's lease is held")
        void reclaimKeepsTheClaim() throws Exception {
            claimed(workspace, PAUSED_ID, Instant.now().minus(Duration.ofHours(30)), task);
            when(conversationStore.read(PAUSED_ID)).thenReturn(stored(GroupConversationState.IN_PROGRESS, false),
                    stored(GroupConversationState.CANCELLED, false));

            assertFalse(service.reconcile(workspace));

            verify(groupConversationService).cancelDiscussion(PAUSED_ID, ControlSignal.CANCEL_GRACEFUL);
            assertEquals(PAUSED_ID, workspace.getRunningDiscussionId());
            assertEquals(TaskStatus.IN_PROGRESS, status());
            verify(workspaceStore, never()).casRunningDiscussion(any());
            assertEquals(0, meters.counter("eddi_team_cadence_claims_reclaimed_total").count());
        }

        @Test
        @DisplayName("active run: an ended discussion is settled only once its leg has stopped")
        void activeRunWaitsForTheLeg() throws Exception {
            claimed(workspace, PAUSED_ID, Instant.now().minus(Duration.ofHours(1)), task);
            when(conversationStore.read(PAUSED_ID)).thenReturn(stored(GroupConversationState.CANCELLED, false));

            assertFalse(service.reconcile(workspace));
            assertEquals(TaskStatus.IN_PROGRESS, status(), "the leg may still be working on it");
            verify(workspaceStore, never()).casRunningDiscussion(any());

            when(groupConversationService.isDiscussionLegRunning(PAUSED_ID)).thenReturn(false);
            assertTrue(service.reconcile(workspace));
            assertEquals(TaskStatus.PENDING, status());
            assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
        }

        @Test
        @DisplayName("parked run: an ended discussion keeps its place and its tasks while its leg runs")
        void parkedRunWaitsForTheLeg() throws Exception {
            workspace.setParkedRuns(List.of(new ParkedRun(PAUSED_ID, Instant.now().minus(Duration.ofHours(30)),
                    hold(workspace, task))));
            when(conversationStore.read(PAUSED_ID)).thenReturn(stored(GroupConversationState.CANCELLED, true));

            assertTrue(service.reconcile(workspace), "the claim slot itself is free");

            assertEquals(1, workspace.getParkedRuns().size());
            assertEquals(TaskStatus.IN_PROGRESS, status());
            verify(workspaceStore, never()).casRunningDiscussion(any());
        }

        @Test
        @DisplayName("a completed discussion whose leg has not released its lease yet is settled at the next fire")
        void completedWaitsForTheLeg() throws Exception {
            claimed(workspace, PAUSED_ID, Instant.now().minus(Duration.ofHours(1)), task);
            when(conversationStore.read(PAUSED_ID)).thenReturn(stored(GroupConversationState.COMPLETED, true));

            assertFalse(service.reconcile(workspace));
            when(groupConversationService.isDiscussionLegRunning(PAUSED_ID)).thenReturn(false);
            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.VERIFIED, status());
        }
    }

    @Nested
    @DisplayName("reconciling parked runs")
    class ReconcileParked {

        private TaskItem parkedTask;
        private GroupWorkspace workspace;

        @BeforeEach
        void parked() {
            parkedTask = new TaskItem("Draft the post", "", 5);
            workspace = workspace(parkedTask);
            workspace.setParkedRuns(List.of(new ParkedRun(PAUSED_ID, Instant.now().minus(Duration.ofHours(1)),
                    hold(workspace, parkedTask))));
        }

        @Test
        @DisplayName("a parked run that completed is written back on its own and leaves parkedRuns")
        void completedParkedRunIsWrittenBack() throws Exception {
            var gc = discussion(PAUSED_ID, GroupConversationState.COMPLETED);
            var tasks = new SharedTaskList();
            verified(tasks, "Draft the post");
            gc.setTaskList(tasks);

            assertTrue(service.reconcile(workspace), "no claim-holding run: the workspace is idle");

            assertTrue(workspace.getParkedRuns().isEmpty());
            assertEquals(TaskStatus.VERIFIED, workspace.getBacklog().findById(parkedTask.id()).status());
            assertEquals(1, workspace.getMetrics().getDiscussions());
        }

        @Test
        @DisplayName("a parked run still paused stays parked and does not make the workspace busy")
        void pausedParkedRunStays() throws Exception {
            discussion(PAUSED_ID, GroupConversationState.AWAITING_APPROVAL);

            assertTrue(service.reconcile(workspace));

            assertEquals(1, workspace.getParkedRuns().size());
            verify(workspaceStore, never()).casRunningDiscussion(any());
        }

        @Test
        @DisplayName("the claim-holding run is still settled after the parked ones")
        void claimHolderStillSettled() throws Exception {
            discussion(PAUSED_ID, GroupConversationState.AWAITING_APPROVAL);
            var other = new TaskItem("Other", "", 1);
            workspace.getBacklog().addTask(other);
            claimed(workspace, NEW_ID, Instant.now(), other);
            discussion(NEW_ID, GroupConversationState.FAILED);

            assertTrue(service.reconcile(workspace));

            assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
            assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(other.id()).status());
            assertEquals(1, workspace.getParkedRuns().size(), "the paused parked run is untouched");
        }

        @Test
        @DisplayName("a parked run past the claim TTL is cancelled and reclaimed, measured from its original claim")
        void staleParkedRunIsReclaimed() throws Exception {
            workspace.setParkedRuns(List.of(new ParkedRun(PAUSED_ID, Instant.now().minus(Duration.ofHours(30)),
                    List.of(parkedTask.id()))));
            discussion(PAUSED_ID, GroupConversationState.AWAITING_APPROVAL);

            assertTrue(service.reconcile(workspace));

            verify(groupConversationService).cancelDiscussion(PAUSED_ID, ControlSignal.CANCEL_GRACEFUL);
            assertTrue(workspace.getParkedRuns().isEmpty());
            assertEquals(TaskStatus.PENDING, workspace.getBacklog().findById(parkedTask.id()).status());
        }

        @Test
        @DisplayName("losing a parked run's settle race makes this caller's copy stale: reconcile reports busy")
        void lostParkedSettleIsBusy() throws Exception {
            discussion(PAUSED_ID, GroupConversationState.FAILED);
            when(workspaceStore.casRunningDiscussion(any())).thenReturn(false);
            var fresh = workspace(parkedTask);
            when(workspaceStore.find(GROUP_ID)).thenReturn(fresh);

            assertFalse(service.reconcile(workspace));
        }

        @Test
        @DisplayName("a lost settle whose fresh copy still parks the run redoes the writeback on that copy")
        void lostParkedSettleRedoesOnFresh() throws Exception {
            discussion(PAUSED_ID, GroupConversationState.FAILED);
            when(workspaceStore.casRunningDiscussion(any())).thenReturn(false, true);
            var fresh = workspace(parkedTask);
            fresh.setParkedRuns(List.of(new ParkedRun(PAUSED_ID, Instant.now(), hold(fresh, parkedTask))));
            when(workspaceStore.find(GROUP_ID)).thenReturn(fresh);

            assertTrue(service.reconcile(workspace));

            assertTrue(fresh.getParkedRuns().isEmpty());
            assertEquals(TaskStatus.PENDING, fresh.getBacklog().findById(parkedTask.id()).status());
        }

        @Test
        @DisplayName("a parked run's tasks count as held: they cannot be deleted from the backlog")
        void parkedTasksAreHeld() {
            assertTrue(workspace.isHeldByRun(parkedTask.id()));
            assertFalse(workspace.isHeldByRun("unknown"));
        }
    }

    // --------------------------------------------- keeping finished tasks

    @Nested
    @DisplayName("hitlConfig.stoppedRunKeepsFinishedTasks")
    class KeepFinished {

        private TaskItem verifiedTask;
        private TaskItem approvedTask;
        private TaskItem undecidedTask;
        private GroupWorkspace workspace;

        @BeforeEach
        void stopped() {
            verifiedTask = new TaskItem("Fix the typo", "", 3);
            approvedTask = new TaskItem("Publish the post", "", 2);
            undecidedTask = new TaskItem("Send the newsletter", "", 1);
            workspace = workspace(verifiedTask, approvedTask, undecidedTask);
            claimed(workspace, PAUSED_ID, Instant.now(), verifiedTask, approvedTask, undecidedTask);
        }

        /**
         * The discussion: one verified, one person-approved, one still awaiting
         * approval.
         */
        private GroupConversation ended(GroupConversationState state, HitlPauseType pauseType) throws Exception {
            var gc = discussion(PAUSED_ID, state);
            gc.setHitlPauseType(pauseType);
            var tasks = new SharedTaskList();
            verified(tasks, "Fix the typo");
            tasks.approveTask(awaitingApproval(tasks, "Publish the post"), "alice");
            awaitingApproval(tasks, "Send the newsletter");
            gc.setTaskList(tasks);
            return gc;
        }

        private TaskStatus statusOf(TaskItem task) {
            return workspace.getBacklog().findById(task.id()).status();
        }

        @Test
        @DisplayName("off (default): a timed-out TASK pause returns every pulled task to PENDING, as before")
        void defaultReturnsEverything() throws Exception {
            configure(null, false, true);
            ended(GroupConversationState.REJECTED, HitlPauseType.TASK);

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.PENDING, statusOf(verifiedTask));
            assertEquals(TaskStatus.PENDING, statusOf(approvedTask));
            assertEquals(TaskStatus.PENDING, statusOf(undecidedTask));
            assertEquals(0, workspace.getMetrics().getTasksVerified());
        }

        @Test
        @DisplayName("on: a rejected TASK pause keeps the verified and the person-approved task; only the undecided one returns")
        void taskPauseKeepsFinished() throws Exception {
            configure(null, true, true);
            ended(GroupConversationState.REJECTED, HitlPauseType.TASK);

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.VERIFIED, statusOf(verifiedTask));
            assertEquals(TaskStatus.COMPLETED, statusOf(approvedTask));
            assertEquals(TaskStatus.PENDING, statusOf(undecidedTask));
            assertEquals(1, workspace.getMetrics().getTasksVerified());
            assertEquals(1, workspace.getMetrics().getTasksApproved());
            assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
            assertEquals(TaskStatus.PENDING, statusOf(undecidedTask));
            assertEquals("", workspace.getBacklog().findById(undecidedTask.id()).description(),
                    "an undecided task returns untouched — no retry feedback is appended");
        }

        @Test
        @DisplayName("on, without approvalCompletesBacklogTask: an approved task is not done, a verified one is")
        void approvalNeedsItsOwnSwitch() throws Exception {
            configure(null, true, false);
            ended(GroupConversationState.REJECTED, HitlPauseType.TASK);

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.VERIFIED, statusOf(verifiedTask));
            assertEquals(TaskStatus.PENDING, statusOf(approvedTask));
        }

        @Test
        @DisplayName("on: a rejection at a PHASE pause still returns everything — the person declined the phase")
        void phasePauseRejectionReturnsEverything() throws Exception {
            configure(null, true, true);
            ended(GroupConversationState.REJECTED, HitlPauseType.PHASE);

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.PENDING, statusOf(verifiedTask));
            assertEquals(TaskStatus.PENDING, statusOf(approvedTask));
        }

        @Test
        @DisplayName("on: a cancelled run (an ABORT timeout) keeps its finished tasks")
        void cancelledKeepsFinished() throws Exception {
            configure(null, true, true);
            ended(GroupConversationState.CANCELLED, HitlPauseType.TASK);

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.VERIFIED, statusOf(verifiedTask));
            assertEquals(TaskStatus.COMPLETED, statusOf(approvedTask));
            assertEquals(TaskStatus.PENDING, statusOf(undecidedTask));
        }

        @Test
        @DisplayName("on: a failed run keeps its finished tasks")
        void failedKeepsFinished() throws Exception {
            configure(null, true, false);
            ended(GroupConversationState.FAILED, null);

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.VERIFIED, statusOf(verifiedTask));
        }

        @Test
        @DisplayName("on: a run reclaimed past the claim TTL keeps its finished tasks")
        void reclaimedKeepsFinished() throws Exception {
            configure(null, true, true);
            workspace.setClaimedAt(Instant.now().minus(Duration.ofHours(30)));
            ended(GroupConversationState.AWAITING_APPROVAL, HitlPauseType.TASK);

            assertTrue(service.reconcile(workspace));

            verify(groupConversationService).cancelDiscussion(PAUSED_ID, ControlSignal.CANCEL_GRACEFUL);
            assertEquals(TaskStatus.VERIFIED, statusOf(verifiedTask));
            assertEquals(TaskStatus.COMPLETED, statusOf(approvedTask));
            assertEquals(TaskStatus.PENDING, statusOf(undecidedTask));
        }

        @Test
        @DisplayName("on: a vanished discussion has nothing to keep — everything returns")
        void vanishedReturnsEverything() throws Exception {
            configure(null, true, true);
            when(conversationStore.read(PAUSED_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.PENDING, statusOf(verifiedTask));
        }

        @Test
        @DisplayName("on, with an unreadable group config: the default rule applies")
        void unreadableConfigFallsBack() throws Exception {
            when(groupStore.getCurrentResourceId(GROUP_ID)).thenThrow(new IllegalStateException("store down"));
            ended(GroupConversationState.REJECTED, HitlPauseType.TASK);

            assertTrue(service.reconcile(workspace));

            assertEquals(TaskStatus.PENDING, statusOf(verifiedTask));
        }

        @Test
        @DisplayName("on: a parked run that stops keeps its finished tasks too")
        void parkedRunKeepsFinished() throws Exception {
            configure(1, true, true);
            workspace.setParkedRuns(List.of(new ParkedRun(PAUSED_ID, Instant.now(), workspace.getPulledTaskIds())));
            workspace.setRunningDiscussionId(GroupWorkspace.NO_RUNNING_DISCUSSION);
            workspace.setPulledTaskIds(List.of());
            ended(GroupConversationState.REJECTED, HitlPauseType.TASK);

            assertTrue(service.reconcile(workspace));

            assertTrue(workspace.getParkedRuns().isEmpty());
            assertEquals(TaskStatus.VERIFIED, statusOf(verifiedTask));
            assertEquals(TaskStatus.COMPLETED, statusOf(approvedTask));
            assertEquals(TaskStatus.PENDING, statusOf(undecidedTask));
        }
    }

    // ---------------------------------------------------- rule and config

    @Test
    @DisplayName("which stopped runs keep their finished tasks")
    void keepsFinishedTasksMatrix() {
        var gc = new GroupConversation();
        gc.setState(GroupConversationState.FAILED);
        assertTrue(TeamCadenceService.keepsFinishedTasks(gc));
        gc.setState(GroupConversationState.CANCELLED);
        assertTrue(TeamCadenceService.keepsFinishedTasks(gc));
        gc.setState(GroupConversationState.REJECTED);
        gc.setHitlPauseType(HitlPauseType.TASK);
        assertTrue(TeamCadenceService.keepsFinishedTasks(gc));
        gc.setHitlPauseType(HitlPauseType.PHASE);
        assertFalse(TeamCadenceService.keepsFinishedTasks(gc));
        gc.setHitlPauseType(null);
        assertFalse(TeamCadenceService.keepsFinishedTasks(gc));
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        assertFalse(TeamCadenceService.keepsFinishedTasks(gc));
    }

    @Test
    @DisplayName("maxPausedCadenceRuns is validated: 0..10")
    void validation() {
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setMaxPausedCadenceRuns(AgentGroupConfiguration.HitlConfig.MAX_PAUSED_CADENCE_RUNS);
        assertDoesNotThrow(() -> HitlConfigValidation.validate(hitl));
        hitl.setMaxPausedCadenceRuns(0);
        assertDoesNotThrow(() -> HitlConfigValidation.validate(hitl));
        hitl.setMaxPausedCadenceRuns(AgentGroupConfiguration.HitlConfig.MAX_PAUSED_CADENCE_RUNS + 1);
        var tooMany = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl));
        assertTrue(tooMany.getMessage().contains("maxPausedCadenceRuns"));
        hitl.setMaxPausedCadenceRuns(-1);
        assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl));
    }
}
