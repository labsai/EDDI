/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.rest.RestTeamHandoffs;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake.Edits;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake.Result;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake.Status;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The handoff intake tools run the REST resource's checks against the MCP
 * caller: the real {@link RestTeamHandoffs} with a mocked intake, group store
 * and access guard.
 */
@DisplayName("McpTeamHandoffTools")
class McpTeamHandoffToolsTest {

    private static final String GROUP = "6aba70011a25f8be7920e0a1";
    private static final String HANDOFF = "7bcb81122b36f9cf8a31f1b2";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private TeamHandoffIntake intake;
    private IAgentGroupStore groupStore;
    private ResourceAccessGuard guard;
    private McpTeamHandoffTools tools;

    @BeforeEach
    void setUp() throws Exception {
        intake = mock(TeamHandoffIntake.class);
        groupStore = mock(IAgentGroupStore.class);
        guard = mock(ResourceAccessGuard.class);
        doReturn(mock(IResourceStore.IResourceId.class)).when(groupStore).getCurrentResourceId(GROUP);
        tools = tools(Set.of("eddi-editor"));
    }

    private McpTeamHandoffTools tools(Set<String> roles) {
        SecurityIdentity identity = identity(roles);
        return new McpTeamHandoffTools(new RestTeamHandoffs(intake, groupStore, identity, guard), new JsonSerialization(mapper), identity, true);
    }

    private static SecurityIdentity identity(Set<String> roles) {
        SecurityIdentity id = mock(SecurityIdentity.class);
        when(id.isAnonymous()).thenReturn(false);
        when(id.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        when(id.getRoles()).thenReturn(roles);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("alice");
        when(id.getPrincipal()).thenReturn(principal);
        return id;
    }

    private JsonNode json(String result) throws Exception {
        return mapper.readTree(result);
    }

    @Test
    @DisplayName("list: needs VIEW on the group and returns the inbox and origins")
    void listChecksViewAndReturnsTheInbox() throws Exception {
        when(intake.view(GROUP)).thenReturn(new TeamHandoffIntake.View(List.of(), Map.of()));

        JsonNode result = json(tools.listTeamHandoffs(GROUP));

        verify(guard).requireAccess(GROUP, AccessLevel.VIEW, "group");
        assertEquals(0, result.get("inbox").size());
        assertEquals(0, result.get("origins").size());
    }

    @Test
    @DisplayName("an unknown group is NOT_FOUND before any access check — 'not yours' and 'missing' look the same")
    void unknownGroupIsNotFound() throws Exception {
        String unknown = "8cdc92233c47a0d09b42a2c3";

        JsonNode result = json(tools.listTeamHandoffs(unknown));

        assertEquals("NOT_FOUND", result.get("errorCode").asText());
        verify(guard, never()).requireAccess(anyString(), any(), anyString());
        verify(intake, never()).view(anyString());
    }

    @Test
    @DisplayName("a caller without access gets FORBIDDEN and the intake is never read")
    void noAccessIsForbidden() throws Exception {
        when(guard.requireAccess(GROUP, AccessLevel.VIEW, "group")).thenThrow(new ForbiddenException("No VIEW access to this group."));

        JsonNode result = json(tools.listTeamHandoffs(GROUP));

        assertEquals("FORBIDDEN", result.get("errorCode").asText());
        verify(intake, never()).view(anyString());
    }

    @Test
    @DisplayName("accept: needs EDIT, records the MCP caller as the decider and passes the edits through")
    void acceptRecordsTheCallerAndEdits() throws Exception {
        when(intake.accept(eq(GROUP), eq(HANDOFF), any(), anyString())).thenReturn(new Result(Status.OK, "Accepted.", null, null));

        JsonNode result = json(tools.acceptTeamHandoff(GROUP, HANDOFF, "New subject", null, 7, "checked"));

        verify(guard).requireAccess(GROUP, AccessLevel.EDIT, "group");
        ArgumentCaptor<Edits> edits = ArgumentCaptor.forClass(Edits.class);
        verify(intake).accept(eq(GROUP), eq(HANDOFF), edits.capture(), eq("alice"));
        assertEquals(new Edits("New subject", null, 7, "checked"), edits.getValue());
        assertEquals("Accepted.", result.get("message").asText());
    }

    @Test
    @DisplayName("accept onto a full backlog is CONFLICT with the intake's own sentence")
    void fullBacklogIsConflict() throws Exception {
        when(intake.accept(eq(GROUP), eq(HANDOFF), any(), anyString()))
                .thenReturn(new Result(Status.BACKLOG_FULL, "The backlog is full (200 tasks).", null, null));

        JsonNode result = json(tools.acceptTeamHandoff(GROUP, HANDOFF, null, null, null, null));

        assertEquals("CONFLICT", result.get("errorCode").asText());
        assertEquals("The backlog is full (200 tasks).", result.get("error").asText());
    }

    @Test
    @DisplayName("decline: needs EDIT and passes the reason code, note and caller")
    void declinePassesReasonAndCaller() throws Exception {
        when(intake.decline(GROUP, HANDOFF, "out_of_scope", "not ours", "alice")).thenReturn(new Result(Status.OK, "Declined.", null, null));

        JsonNode result = json(tools.declineTeamHandoff(GROUP, HANDOFF, "out_of_scope", "not ours"));

        verify(guard).requireAccess(GROUP, AccessLevel.EDIT, "group");
        assertEquals("Declined.", result.get("message").asText());
        assertNull(result.get("errorCode"));
    }

    @Test
    @DisplayName("missing ids are BAD_REQUEST without touching the resource")
    void missingIdsAreBadRequest() throws Exception {
        assertEquals("BAD_REQUEST", json(tools.listTeamHandoffs(" ")).get("errorCode").asText());
        assertEquals("BAD_REQUEST", json(tools.acceptTeamHandoff(GROUP, null, null, null, null, null)).get("errorCode").asText());
        assertEquals("BAD_REQUEST", json(tools.declineTeamHandoff(null, HANDOFF, null, null)).get("errorCode").asText());
        verifyNoInteractions(intake, guard);
    }

    @Test
    @DisplayName("eddi-viewer is refused at the door, as REST refuses it")
    void viewerIsRefused() {
        var viewerTools = tools(Set.of("eddi-viewer"));
        assertThrows(ForbiddenException.class, () -> viewerTools.listTeamHandoffs(GROUP));
        assertThrows(ForbiddenException.class, () -> viewerTools.acceptTeamHandoff(GROUP, HANDOFF, null, null, null, null));
        verifyNoInteractions(intake);
    }
}
