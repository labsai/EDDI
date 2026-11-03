/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.groups.budget.TeamBudgetSnapshot;
import ai.labs.eddi.engine.groups.budget.rest.RestGroupBudget;
import ai.labs.eddi.engine.groups.budget.rest.RestTeamBudgets;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The team-budget tools run the REST resources against the MCP caller — the
 * real {@link RestGroupBudget} and {@link RestTeamBudgets} with a mocked
 * service and guard — and gate the two writes to administrators themselves,
 * because an in-process call never passes the HTTP layer's
 * {@code @RolesAllowed}.
 */
@DisplayName("McpTeamBudgetTools")
class McpTeamBudgetToolsTest {

    private static final String GROUP = "6aba70011a25f8be7920e0b1";
    private static final String OTHER = "7bcb81122b36f9cf8a31f1c2";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private TeamBudgetService service;
    private ResourceAccessGuard guard;

    @BeforeEach
    void setUp() {
        service = mock(TeamBudgetService.class);
        guard = mock(ResourceAccessGuard.class);
        when(service.config(GROUP)).thenReturn(new AgentGroupConfiguration());
    }

    private McpTeamBudgetTools tools(Set<String> roles) {
        SecurityIdentity identity = identity(roles);
        return new McpTeamBudgetTools(new RestGroupBudget(service, guard, identity), new RestTeamBudgets(service, guard, identity),
                new JsonSerialization(mapper), identity, true);
    }

    private static SecurityIdentity identity(Set<String> roles) {
        SecurityIdentity id = mock(SecurityIdentity.class);
        when(id.isAnonymous()).thenReturn(false);
        when(id.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        when(id.getRoles()).thenReturn(roles);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("root");
        when(id.getPrincipal()).thenReturn(principal);
        return id;
    }

    private static TeamBudgetSnapshot snapshot(double cap, double spent) {
        return new TeamBudgetSnapshot(GROUP, "Content Studio", "2026-11", cap, 0.0, cap, spent, Math.max(0.0, cap - spent), spent >= cap,
                Instant.parse("2026-12-01T00:00:00Z"), null, TeamBudgetSnapshot.LIMIT_TEAM);
    }

    @Test
    @DisplayName("read: an eddi-viewer may read (REST admits it), VIEW on the group is checked, the snapshot comes back")
    void viewerReadsWithViewAccess() throws Exception {
        when(service.snapshot(GROUP)).thenReturn(snapshot(35.0, 12.5));

        JsonNode result = mapper.readTree(tools(Set.of("eddi-viewer")).readTeamBudget(GROUP, null));

        verify(guard).requireAccess(GROUP, AccessLevel.VIEW, "group");
        assertEquals(35.0, result.get("capUsd").asDouble(), 1e-9);
        assertEquals(22.5, result.get("remainingUsd").asDouble(), 1e-9);
        verify(service, never()).history(anyString(), anyInt());
    }

    @Test
    @DisplayName("read with historyMonths adds the past months; out-of-range values are refused before any read")
    void historyIsOptionalAndBounded() throws Exception {
        when(service.snapshot(GROUP)).thenReturn(snapshot(35.0, 1.0));
        when(service.history(GROUP, 3)).thenReturn(List.of());
        var tools = tools(Set.of("eddi-editor"));

        JsonNode result = mapper.readTree(tools.readTeamBudget(GROUP, 3));
        assertEquals(35.0, result.get("current").get("capUsd").asDouble(), 1e-9);
        assertEquals(GROUP, result.get("history").get("groupId").asText());

        assertEquals("BAD_REQUEST", mapper.readTree(tools.readTeamBudget(GROUP, 14)).get("errorCode").asText());
        verify(service, never()).history(GROUP, 14);
    }

    @Test
    @DisplayName("read without VIEW on the group is FORBIDDEN")
    void readWithoutAccessIsForbidden() throws Exception {
        when(guard.requireAccess(GROUP, AccessLevel.VIEW, "group")).thenThrow(new ForbiddenException("No VIEW access to this group."));

        JsonNode result = mapper.readTree(tools(Set.of("eddi-editor")).readTeamBudget(GROUP, null));

        assertEquals("FORBIDDEN", result.get("errorCode").asText());
        verify(service, never()).snapshot(GROUP);
    }

    @Test
    @DisplayName("an eddi-user has no budget role at all, as over REST")
    void userIsRefused() {
        assertThrows(ForbiddenException.class, () -> tools(Set.of("eddi-user")).readTeamBudget(GROUP, null));
    }

    @Test
    @DisplayName("adjust: an editor — even with EDIT on the group — is refused; the cap is an administrator's lever")
    void adjustIsAdminOnly() {
        assertThrows(ForbiddenException.class, () -> tools(Set.of("eddi-editor")).adjustTeamBudget(GROUP, 100.0, "launch week"));
        assertThrows(ForbiddenException.class, () -> tools(Set.of("eddi-viewer")).transferTeamBudget(GROUP, OTHER, 5.0, "x", null));
        verify(service, never()).setCurrentMonthCap(anyString(), anyDouble(), anyString(), anyString());
    }

    @Test
    @DisplayName("adjust as admin records the caller as the actor; a cap below the spend is BAD_REQUEST with the service's reason")
    void adjustAsAdmin() throws Exception {
        when(service.setCurrentMonthCap(GROUP, 50.0, "launch week", "root")).thenReturn(snapshot(50.0, 12.0));
        when(service.setCurrentMonthCap(GROUP, 5.0, "too low", "root"))
                .thenThrow(new IllegalArgumentException("The cap cannot be below what is already spent ($12.00)."));
        var tools = tools(Set.of("eddi-admin"));

        assertEquals(50.0, mapper.readTree(tools.adjustTeamBudget(GROUP, 50.0, "launch week")).get("capUsd").asDouble(), 1e-9);

        JsonNode refused = mapper.readTree(tools.adjustTeamBudget(GROUP, 5.0, "too low"));
        assertEquals("BAD_REQUEST", refused.get("errorCode").asText());
        assertTrue(refused.get("error").asText().contains("already spent"));
    }

    @Test
    @DisplayName("transfer as admin passes the idempotency key and the caller; missing arguments never reach the service")
    void transferAsAdmin() throws Exception {
        var tools = tools(Set.of("eddi-admin"));
        when(service.transfer(GROUP, OTHER, 5.0, "rebalance", "root", "key-1"))
                .thenReturn(new TeamBudgetService.TransferResult(snapshot(30.0, 1.0), snapshot(40.0, 0.0), "t-1"));

        JsonNode result = mapper.readTree(tools.transferTeamBudget(GROUP, OTHER, 5.0, "rebalance", " key-1 "));
        assertEquals("t-1", result.get("transferId").asText());

        assertEquals("BAD_REQUEST", mapper.readTree(tools.transferTeamBudget(GROUP, OTHER, null, "x", null)).get("errorCode").asText());
        assertEquals("BAD_REQUEST", mapper.readTree(tools.adjustTeamBudget(GROUP, 5.0, " ")).get("errorCode").asText());
    }
}
