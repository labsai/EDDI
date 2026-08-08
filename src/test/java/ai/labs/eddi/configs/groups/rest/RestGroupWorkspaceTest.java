/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace.BacklogTaskRequest;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace.CadenceRequest;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        rest = new RestGroupWorkspace(workspaceStore, groupStore, scheduleStore, teamCadenceService, identity);

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
        verify(workspaceStore).update(workspace);
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
    }

    @Test
    void addBacklogTask_blankSubject_400() {
        assertEquals(400, rest.addBacklogTask(GROUP_ID, new BacklogTaskRequest("  ", "", 0)).getStatus());
        assertEquals(400, rest.addBacklogTask(GROUP_ID, null).getStatus());
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
    void deleteCadence_removesScheduleAndCadence() throws Exception {
        var workspace = workspace();
        workspace.getCadences().add(new Cadence("c-1", "sched-9", null, 5, null, "pm"));

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
