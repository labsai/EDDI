/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace.BacklogTaskRequest;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace.CadenceRequest;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.internal.TeamCadenceService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I13 — {@link RestGroupWorkspace}: backlog writes with the actionable cap,
 * cadence lifecycle (schedule created with the fire-dispatch metadata, deleted
 * with the cadence), and the group-existence gate.
 */
class RestGroupWorkspaceTest {

    private static final String GROUP_ID = "group-1";

    private IGroupWorkspaceStore workspaceStore;
    private IAgentGroupStore groupStore;
    private IScheduleStore scheduleStore;
    private TeamCadenceService teamCadenceService;
    private RestGroupWorkspace rest;

    @BeforeEach
    void setUp() throws Exception {
        workspaceStore = mock(IGroupWorkspaceStore.class);
        groupStore = mock(IAgentGroupStore.class);
        scheduleStore = mock(IScheduleStore.class);
        teamCadenceService = mock(TeamCadenceService.class);
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn("pm@example.com");
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        rest = new RestGroupWorkspace(workspaceStore, groupStore, scheduleStore, teamCadenceService, identity, mock(ResourceAccessGuard.class));

        var resourceId = mock(IResourceStore.IResourceId.class);
        lenient().when(resourceId.getVersion()).thenReturn(1);
        lenient().when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resourceId);
    }

    private GroupWorkspace workspace() throws Exception {
        var workspace = new GroupWorkspace();
        workspace.setId("ws-1");
        workspace.setGroupId(GROUP_ID);
        lenient().when(workspaceStore.readOrCreate(GROUP_ID)).thenReturn(workspace);
        lenient().when(workspaceStore.find(GROUP_ID)).thenReturn(workspace);
        lenient().when(workspaceStore.casRevision(workspace)).thenReturn(true);
        return workspace;
    }

    @Test
    @DisplayName("reading the workspace runs read-repair reconcile first")
    void readWorkspace_readRepairs() throws Exception {
        var workspace = workspace();

        var response = rest.readWorkspace(GROUP_ID);

        assertEquals(200, response.getStatus());
        verify(teamCadenceService).reconcile(workspace);
    }

    @Test
    void readWorkspace_unknownGroup_404() throws Exception {
        when(groupStore.getCurrentResourceId("missing")).thenReturn(null);

        assertEquals(404, rest.readWorkspace("missing").getStatus());
        verify(workspaceStore, never()).readOrCreate(any());
    }

    @Test
    void addBacklogTask_persistsTheTask() throws Exception {
        var workspace = workspace();

        var response = rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest("Ship it", "the feature", 3));

        assertEquals(201, response.getStatus());
        assertEquals(1, workspace.getBacklog().size());
        TaskItem task = workspace.getBacklog().getTasks().get(0);
        assertEquals("Ship it", task.subject());
        assertEquals(3, task.priority());
        verify(workspaceStore).casRevision(workspace);
        verify(workspaceStore, never()).update(any());
    }

    @Test
    @DisplayName("the backlog cap fails with an ACTIONABLE error, not a silent drop")
    void addBacklogTask_capIsActionable() throws Exception {
        var workspace = workspace();
        for (int i = 0; i < GroupWorkspace.MAX_BACKLOG_SIZE; i++) {
            workspace.getBacklog().addTask(new TaskItem("Task " + i, "", 0));
        }

        var response = rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest("One more", "", 0));

        assertEquals(409, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("complete or delete"),
                "the error says what to do about it: " + response.getEntity());
        verify(workspaceStore, never()).update(any());
        verify(workspaceStore, never()).casRevision(any());
    }

    @Test
    void addBacklogTask_blankSubject_400() {
        assertEquals(400, rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest("  ", "", 0)).getStatus());
        assertEquals(400, rest.addBacklogTask(GROUP_ID, null).getStatus());
    }

    @Test
    @DisplayName("REST enforces the same subject/description bounds as the agent tool surface")
    void addBacklogTask_oversizedFields_400() throws Exception {
        workspace();

        String longSubject = "s".repeat(SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH + 1);
        assertEquals(400, rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest(longSubject, "", 0)).getStatus());

        String longDescription = "d".repeat(SharedTaskList.MAX_AGENT_TASK_DESCRIPTION_LENGTH + 1);
        assertEquals(400, rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest("Ok", longDescription, 0)).getStatus());

        verify(workspaceStore, never()).update(any());
        verify(workspaceStore, never()).casRevision(any());
    }

    @Test
    @DisplayName("duplicate subjects are rejected — writeback matches outcomes by subject")
    void addBacklogTask_duplicateSubject_409() throws Exception {
        var workspace = workspace();
        workspace.getBacklog().addTask(new TaskItem("Ship it", "", 0));

        var response = rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest("ship IT", "", 0));

        assertEquals(409, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("subject"), String.valueOf(response.getEntity()));
        assertEquals(1, workspace.getBacklog().size());
        verify(workspaceStore, never()).update(any());
        verify(workspaceStore, never()).casRevision(any());
    }

    @Test
    @DisplayName("a lost revision race re-reads and retries; exhaustion is an honest 409")
    void addBacklogTask_lostCas_retriesThenGivesUp() throws Exception {
        // Each read returns a FRESH document — in production a re-read reflects
        // the concurrent writer's state, never this caller's failed mutation.
        when(workspaceStore.readOrCreate(GROUP_ID)).thenAnswer(inv -> {
            var w = new GroupWorkspace();
            w.setId("ws-1");
            w.setGroupId(GROUP_ID);
            return w;
        });
        when(workspaceStore.casRevision(any())).thenReturn(false, true);

        assertEquals(201, rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest("Ship it", "", 0)).getStatus());
        verify(workspaceStore, times(2).description("one re-read per attempt")).readOrCreate(GROUP_ID);

        when(workspaceStore.casRevision(any())).thenReturn(false);
        var response = rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest("Another", "", 0));
        assertEquals(409, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("concurrently"),
                "exhausted retries tell the caller to retry: " + response.getEntity());
    }

    @Test
    @DisplayName("a failed workspace write deletes the just-created schedule — no orphan fires forever")
    void addCadence_workspaceWriteFails_deletesSchedule() throws Exception {
        var workspace = workspace();
        when(scheduleStore.createSchedule(any())).thenReturn("sched-9");
        doThrow(new IResourceStore.ResourceStoreException("store down")).when(workspaceStore).update(workspace);

        var response = rest.addCadence(GROUP_ID, new CadenceRequest("0 9 * * 1", null, null, 0, null));

        assertEquals(500, response.getStatus());
        verify(scheduleStore).deleteSchedule("sched-9");
    }

    @Test
    @DisplayName("adding a cadence creates the schedule carrying the fire-dispatch metadata")
    void addCadence_createsScheduleWithMetadata() throws Exception {
        var workspace = workspace();
        when(scheduleStore.createSchedule(any())).thenReturn("sched-9");

        var response = rest.addCadence(GROUP_ID, new CadenceRequest("0 9 * * 1", "UTC", null, 3, 2.50));

        assertEquals(201, response.getStatus());
        ArgumentCaptor<ScheduleConfiguration> captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(captor.capture());
        var schedule = captor.getValue();
        assertEquals(TeamCadenceService.METADATA_TYPE_CADENCE,
                schedule.getMetadata().get(TeamCadenceService.METADATA_TYPE_KEY));
        assertEquals(GROUP_ID, schedule.getMetadata().get(TeamCadenceService.METADATA_GROUP_ID_KEY));
        assertEquals("0 9 * * 1", schedule.getCronExpression());
        assertEquals("pm@example.com", schedule.getCreatedBy());

        assertEquals(1, workspace.getCadences().size());
        Cadence cadence = workspace.getCadences().get(0);
        assertEquals("sched-9", cadence.scheduleRef());
        assertEquals(3, cadence.maxBacklogTasksPerRun());
        assertEquals(2.50, cadence.maxCostPerRun());
        assertEquals("pm@example.com", cadence.createdBy(), "cadence runs are attributable to their creator");
        verify(workspaceStore).update(workspace);
    }

    @Test
    @DisplayName("an unparseable cron fails at creation, not silently at fire time")
    void addCadence_invalidCron_400() throws Exception {
        workspace();

        var response = rest.addCadence(GROUP_ID, new CadenceRequest("not a cron", null, null, 0, null));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    @DisplayName("a workspace is a team's schedule, not a cron farm — the cadence count is capped")
    void addCadence_countCap_409_andTemplateBound_400() throws Exception {
        var workspace = workspace();
        for (int i = 0; i < RestGroupWorkspace.MAX_CADENCES_PER_WORKSPACE; i++) {
            workspace.addCadence(new Cadence("c-" + i, "sched-" + i, null, 5, null, "pm"));
        }
        assertEquals(409, rest.addCadence(GROUP_ID, new CadenceRequest("0 9 * * 1", null, null, 0, null)).getStatus());

        var fresh = workspace();
        String hugeTemplate = "t".repeat(RestGroupWorkspace.MAX_INPUT_TEMPLATE_LENGTH + 1);
        assertEquals(400,
                rest.addCadence(GROUP_ID, new CadenceRequest("0 9 * * 1", null, hugeTemplate, 0, null)).getStatus());
        assertTrue(fresh.getCadences().isEmpty());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    void deleteCadence_removesScheduleAndCadence() throws Exception {
        var workspace = workspace();
        workspace.addCadence(new Cadence("c-1", "sched-9", null, 5, null, "pm"));

        var response = rest.deleteCadence(GROUP_ID, "c-1");

        assertEquals(204, response.getStatus());
        verify(scheduleStore).deleteSchedule("sched-9");
        assertTrue(workspace.getCadences().isEmpty());
        verify(workspaceStore).update(workspace);
    }

    @Test
    void deleteCadence_unknownCadence_404() throws Exception {
        workspace();

        assertEquals(404, rest.deleteCadence(GROUP_ID, "no-such").getStatus());
        verify(scheduleStore, never()).deleteSchedule(anyString());
    }
}
