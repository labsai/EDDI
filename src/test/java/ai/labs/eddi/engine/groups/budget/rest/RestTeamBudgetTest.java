/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.engine.groups.budget.InMemoryTeamBudgetStore;
import ai.labs.eddi.engine.groups.budget.TeamBudgetAccountingUnavailableException;
import ai.labs.eddi.engine.groups.budget.TeamBudgetAudit;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.groups.budget.TeamBudgetSnapshot;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestTeamBudgetTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);

    private InMemoryTeamBudgetStore store;
    private ResourceAccessGuard guard;
    private RestGroupBudget groupRest;
    private RestTeamBudgets teamsRest;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryTeamBudgetStore();
        IAgentGroupStore groups = mock(IAgentGroupStore.class);
        for (String id : List.of("studio", "radar", "secret")) {
            AgentGroupConfiguration config = new AgentGroupConfiguration();
            config.setName(id);
            config.setTeamBudget(new TeamBudgetConfig(30.0, CostPolicy.SYNTHESIZE_NOW, null));
            IResourceStore.IResourceId rid = mock(IResourceStore.IResourceId.class);
            when(rid.getVersion()).thenReturn(1);
            when(groups.getCurrentResourceId(id)).thenReturn(rid);
            when(groups.read(eq(id), anyInt())).thenReturn(config);
        }
        when(groups.getCurrentResourceId("ghost")).thenThrow(new IResourceStore.ResourceNotFoundException("no"));
        TenantQuotaService tenant = mock(TenantQuotaService.class);
        when(tenant.getDefaultTenantId()).thenReturn("default");
        when(tenant.remainingCostUsd(anyString())).thenReturn(OptionalDouble.empty());
        TeamBudgetService service = new TeamBudgetService(store, groups, tenant, new SimpleMeterRegistry(), mock(TeamBudgetAudit.class), CLOCK);
        guard = mock(ResourceAccessGuard.class);
        when(guard.hasAccess(anyString(), eq(AccessLevel.VIEW))).thenAnswer(inv -> !"secret".equals(inv.getArgument(0)));
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = () -> "alice";
        when(identity.getPrincipal()).thenReturn(principal);
        groupRest = new RestGroupBudget(service, guard, identity);
        teamsRest = new RestTeamBudgets(service, guard, identity);
    }

    @Test
    void readReturnsTheSnapshotAndNeedsViewOnTheGroup() {
        store.addSpend("studio", "2026-11", 12.0, Instant.parse("2027-12-01T00:00:00Z"));
        Response r = groupRest.readBudget("studio");
        assertEquals(200, r.getStatus());
        TeamBudgetSnapshot s = (TeamBudgetSnapshot) r.getEntity();
        assertEquals(12.0, s.spentUsd());
        assertEquals(18.0, s.remainingUsd());

        doThrow(new ForbiddenException("no")).when(guard).requireAccess("secret", AccessLevel.VIEW, "group");
        assertThrows(ForbiddenException.class, () -> groupRest.readBudget("secret"));
    }

    @Test
    void anUnknownGroupIs404() {
        assertEquals(404, groupRest.readBudget("ghost").getStatus());
        assertEquals(404, groupRest.readHistory("ghost", 6).getStatus());
    }

    @Test
    void anOutageIs503NotAnEmptyBudget() {
        store.down = true;
        assertEquals(503, groupRest.readBudget("studio").getStatus());
        assertEquals(503, teamsRest.listBudgets("studio").getStatus());
        assertEquals(503, groupRest.setCurrentMonthCap("studio", new IRestGroupBudget.CapRequest(40.0, "x")).getStatus());
    }

    @Test
    void settingTheCapMapsValidationTo400() {
        assertEquals(200, groupRest.setCurrentMonthCap("studio", new IRestGroupBudget.CapRequest(40.0, "launch")).getStatus());
        assertEquals(400, groupRest.setCurrentMonthCap("studio", new IRestGroupBudget.CapRequest(40.0, " ")).getStatus());
        assertEquals(400, groupRest.setCurrentMonthCap("studio", new IRestGroupBudget.CapRequest(null, "x")).getStatus());
        assertEquals(400, groupRest.setCurrentMonthCap("studio", null).getStatus());
        store.addSpend("studio", "2026-11", 39.0, Instant.parse("2027-12-01T00:00:00Z"));
        Response r = groupRest.setCurrentMonthCap("studio", new IRestGroupBudget.CapRequest(10.0, "cut"));
        assertEquals(400, r.getStatus());
        assertTrue(((Map<?, ?>) r.getEntity()).get("error").toString().contains("already been spent"));
    }

    @Test
    void theListingLeavesOutTeamsTheCallerCannotView() {
        Response r = teamsRest.listBudgets("studio, radar,secret,ghost");
        assertEquals(200, r.getStatus());
        Map<?, ?> body = (Map<?, ?>) r.getEntity();
        List<?> teams = (List<?>) body.get("teams");
        assertEquals(List.of("studio", "radar"), teams.stream().map(t -> ((TeamBudgetSnapshot) t).groupId()).toList());
        assertEquals("2026-11", body.get("month"));
    }

    @Test
    void theListingIsBounded() {
        String many = String.join(",", java.util.stream.IntStream.range(0, 51).mapToObj(i -> "g" + i).toList());
        assertEquals(400, teamsRest.listBudgets(many).getStatus());
    }

    @Test
    void aTransferMovesCapAndRetriesAreIdempotent() {
        var request = new IRestTeamBudgets.TransferRequest("radar", "studio", 5.0, "studio needs it");
        assertEquals(200, teamsRest.transfer("key-1", request).getStatus());
        assertEquals(200, teamsRest.transfer("key-1", request).getStatus());
        assertEquals(25.0, ((TeamBudgetSnapshot) groupRest.readBudget("radar").getEntity()).capUsd());
        assertEquals(35.0, ((TeamBudgetSnapshot) groupRest.readBudget("studio").getEntity()).capUsd());
        assertEquals(400, teamsRest.transfer(null, new IRestTeamBudgets.TransferRequest("radar", "studio", 99.0, "too much")).getStatus());
        assertEquals(400, teamsRest.transfer(null, null).getStatus());
    }

    @Test
    void roleTiersAreTheDocumentedOnes() throws Exception {
        assertEquals(Set.of("eddi-admin", "eddi-editor", "eddi-viewer"), roles(IRestGroupBudget.class.getMethod("readBudget", String.class)));
        assertEquals(Set.of("eddi-admin", "eddi-editor", "eddi-viewer"),
                roles(IRestGroupBudget.class.getMethod("readHistory", String.class, int.class)));
        assertEquals(Set.of("eddi-admin"),
                roles(IRestGroupBudget.class.getMethod("setCurrentMonthCap", String.class, IRestGroupBudget.CapRequest.class)));
        assertEquals(Set.of("eddi-admin", "eddi-editor", "eddi-viewer"), roles(IRestTeamBudgets.class.getMethod("listBudgets", String.class)));
        assertEquals(Set.of("eddi-admin"),
                roles(IRestTeamBudgets.class.getMethod("transfer", String.class, IRestTeamBudgets.TransferRequest.class)));
    }

    private static Set<String> roles(java.lang.reflect.Method method) {
        return Set.of(method.getAnnotation(RolesAllowed.class).value());
    }

    @Test
    void anOutageClassIsTheExpectedType() {
        assertTrue(RuntimeException.class.isAssignableFrom(TeamBudgetAccountingUnavailableException.class));
    }
}
