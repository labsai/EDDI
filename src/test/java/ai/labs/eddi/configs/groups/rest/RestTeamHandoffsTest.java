/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestTeamHandoffs.AcceptRequest;
import ai.labs.eddi.configs.groups.IRestTeamHandoffs.DeclineRequest;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake.Status;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RestTeamHandoffsTest {

    static final String GROUP = "team-group";

    TeamHandoffIntake intake;
    IAgentGroupStore groupStore;
    ResourceAccessGuard guard;
    SecurityIdentity identity;
    RestTeamHandoffs rest;

    @BeforeEach
    void setUp() throws Exception {
        intake = mock(TeamHandoffIntake.class);
        groupStore = mock(IAgentGroupStore.class);
        guard = mock(ResourceAccessGuard.class);
        identity = mock(SecurityIdentity.class);
        Principal principal = () -> "reviewer-1";
        when(identity.getPrincipal()).thenReturn(principal);
        when(groupStore.getCurrentResourceId(GROUP)).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        });
        rest = new RestTeamHandoffs(intake, groupStore, identity, guard);
    }

    @Test
    void anUnknownGroupIs404BeforeAnyAccessDecision() throws Exception {
        when(groupStore.getCurrentResourceId("ghost")).thenReturn(null);
        assertEquals(404, rest.readHandoffs("ghost").getStatus());
        assertEquals(404, rest.acceptHandoff("ghost", "h", null).getStatus());
        assertEquals(404, rest.declineHandoff("ghost", "h", null).getStatus());
        assertEquals(404, rest.deleteBacklogTask("ghost", "t").getStatus());
        verifyNoInteractions(guard);
        verifyNoInteractions(intake);
    }

    @Test
    void readingNeedsViewAndReturnsInboxAndOrigins() throws Exception {
        when(intake.view(GROUP)).thenReturn(new TeamHandoffIntake.View(List.of(), Map.of()));
        Response response = rest.readHandoffs(GROUP);
        assertEquals(200, response.getStatus());
        assertTrue(((Map<?, ?>) response.getEntity()).containsKey("inbox"));
        assertTrue(((Map<?, ?>) response.getEntity()).containsKey("origins"));
        verify(guard).requireAccess(GROUP, AccessLevel.VIEW, "group");
    }

    @Test
    void aViewerCanReadButNeverDecide() throws Exception {
        // VIEW passes, EDIT is refused: exactly a read-only collaborator.
        doThrow(new ForbiddenException("no edit")).when(guard).requireAccess(GROUP, AccessLevel.EDIT, "group");
        when(intake.view(GROUP)).thenReturn(new TeamHandoffIntake.View(List.of(), Map.of()));
        assertEquals(200, rest.readHandoffs(GROUP).getStatus());
        assertThrows(ForbiddenException.class, () -> rest.acceptHandoff(GROUP, "h", null));
        assertThrows(ForbiddenException.class, () -> rest.declineHandoff(GROUP, "h", null));
        assertThrows(ForbiddenException.class, () -> rest.deleteBacklogTask(GROUP, "t"));
        verify(intake, never()).accept(anyString(), anyString(), any(), any());
        verify(intake, never()).decline(anyString(), anyString(), any(), any(), any());
        verify(intake, never()).deleteBacklogTask(anyString(), anyString(), any());
    }

    @Test
    void existenceIsCheckedBeforeAccess() throws Exception {
        InOrder order = inOrder(groupStore, guard);
        when(intake.view(GROUP)).thenReturn(new TeamHandoffIntake.View(List.of(), Map.of()));
        rest.readHandoffs(GROUP);
        order.verify(groupStore).getCurrentResourceId(GROUP);
        order.verify(guard).requireAccess(GROUP, AccessLevel.VIEW, "group");
    }

    @Test
    void acceptPassesTheEditsAndTheActingUser() {
        when(intake.accept(anyString(), anyString(), any(), any())).thenReturn(new TeamHandoffIntake.Result(Status.OK, "ok", null, null));
        Response response = rest.acceptHandoff(GROUP, "h-1", new AcceptRequest("S", "D", 4, "n"));
        assertEquals(200, response.getStatus());
        var edits = ArgumentCaptor.forClass(TeamHandoffIntake.Edits.class);
        verify(intake).accept(eq(GROUP), eq("h-1"), edits.capture(), eq("reviewer-1"));
        assertEquals("S", edits.getValue().subject());
        assertEquals(4, edits.getValue().priority());
        assertEquals("n", edits.getValue().note());
    }

    @Test
    void declinePassesReasonNoteAndUser() {
        when(intake.decline(anyString(), anyString(), any(), any(), any())).thenReturn(new TeamHandoffIntake.Result(Status.OK, "ok", null, null));
        assertEquals(200, rest.declineHandoff(GROUP, "h-1", new DeclineRequest("out_of_scope", "n")).getStatus());
        verify(intake).decline(GROUP, "h-1", "out_of_scope", "n", "reviewer-1");
        assertEquals(200, rest.declineHandoff(GROUP, "h-1", null).getStatus());
        verify(intake).decline(GROUP, "h-1", null, null, "reviewer-1");
    }

    @Test
    void statusesMapToHttp() {
        for (var entry : Map.of(Status.NOT_FOUND, 404, Status.INVALID, 400, Status.BACKLOG_FULL, 409, Status.DUPLICATE, 409, Status.CONFLICT, 409,
                Status.UNAVAILABLE, 503).entrySet()) {
            when(intake.accept(anyString(), anyString(), any(), any())).thenReturn(new TeamHandoffIntake.Result(entry.getKey(), "msg", null, null));
            Response response = rest.acceptHandoff(GROUP, "h", null);
            assertEquals(entry.getValue(), response.getStatus(), entry.getKey().name());
            assertEquals(Map.of("error", "msg"), response.getEntity());
        }
    }

    @Test
    void deleteIs204OnSuccessAndTheMappedErrorOtherwise() {
        when(intake.deleteBacklogTask(GROUP, "t1", "reviewer-1")).thenReturn(new TeamHandoffIntake.Result(Status.OK, "Deleted.", null, null));
        when(intake.deleteBacklogTask(GROUP, "t2", "reviewer-1")).thenReturn(new TeamHandoffIntake.Result(Status.CONFLICT, "busy", null, null));
        when(intake.deleteBacklogTask(GROUP, "t3", "reviewer-1")).thenReturn(new TeamHandoffIntake.Result(Status.NOT_FOUND, "none", null, null));
        assertEquals(204, rest.deleteBacklogTask(GROUP, "t1").getStatus());
        assertEquals(409, rest.deleteBacklogTask(GROUP, "t2").getStatus());
        assertEquals(404, rest.deleteBacklogTask(GROUP, "t3").getStatus());
    }

    @Test
    void anUnexpectedFailureIsA500NotALeak() throws Exception {
        when(intake.view(GROUP)).thenThrow(new IllegalStateException("secret internals"));
        Response response = rest.readHandoffs(GROUP);
        assertEquals(500, response.getStatus());
        assertNull(response.getEntity());
    }
}
