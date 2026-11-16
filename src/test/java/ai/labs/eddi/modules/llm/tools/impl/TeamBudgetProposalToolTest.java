/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.groups.budget.InMemoryTeamBudgetStore;
import ai.labs.eddi.engine.groups.budget.TeamBudgetAudit;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamBudgetProposalToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
    private static final CallerIdentity ADMIN = new CallerIdentity("tok", "alice", "origin", Map.of(), true);
    private static final CallerIdentity APPROVER = new CallerIdentity("tok", "bob", "origin", Map.of(), false);

    private final InMemoryTeamBudgetStore store = new InMemoryTeamBudgetStore();
    private final Map<String, AgentGroupConfiguration> configs = new HashMap<>();
    private TeamBudgetService service;
    private CallerIdentity caller;

    @BeforeEach
    void setUp() throws Exception {
        IAgentGroupStore groupStore = mock(IAgentGroupStore.class);
        when(groupStore.getCurrentResourceId(anyString())).thenAnswer(inv -> {
            if (!configs.containsKey(inv.<String>getArgument(0))) {
                throw new IResourceStore.ResourceNotFoundException("no group");
            }
            IResourceStore.IResourceId rid = mock(IResourceStore.IResourceId.class);
            when(rid.getVersion()).thenReturn(1);
            return rid;
        });
        when(groupStore.read(anyString(), anyInt())).thenAnswer(inv -> configs.get(inv.<String>getArgument(0)));
        TenantQuotaService tenant = mock(TenantQuotaService.class);
        when(tenant.getDefaultTenantId()).thenReturn("default");
        when(tenant.remainingCostUsd(anyString())).thenReturn(OptionalDouble.empty());
        service = new TeamBudgetService(store, groupStore, tenant, new SimpleMeterRegistry(), mock(TeamBudgetAudit.class), CLOCK);
        group("studio", 35.0);
        group("radar", 30.0);
        group("stranger", 10.0);
        caller = ADMIN;
    }

    private void group(String id, double cap) {
        AgentGroupConfiguration config = new AgentGroupConfiguration();
        config.setName(id);
        config.setTeamBudget(new TeamBudgetConfig(cap, CostPolicy.SYNTHESIZE_NOW, null));
        configs.put(id, config);
    }

    private TeamBudgetProposalTool tool(AgentOpsConfig.BudgetProposal limits) {
        // "stranger" is a real budgeted team that is not in the agent's scope.
        var scope = new AgentOpsScope(List.of("studio", "radar"), id -> true);
        Supplier<CallerIdentity> who = () -> caller;
        return new TeamBudgetProposalTool(scope, limits, service, who, "agent-1", "conv-1", CLOCK);
    }

    private TeamBudgetProposalTool tool() {
        return tool(new AgentOpsConfig.BudgetProposal());
    }

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void anApprovedRaiseSetsTheCapAndRecordsTheApproverAndTheAgent() throws Exception {
        JsonNode out = parse(tool().proposeRaise("studio", 50.0, "launch week"));

        assertTrue(out.get("applied").asBoolean(), out.toString());
        assertEquals(50.0, service.snapshot("studio").capUsd());
        var adjustment = store.read("studio", "2026-11").orElseThrow().adjustments().get(0);
        assertEquals("alice", adjustment.actor(), "the approving admin is the actor, not the agent");
        assertTrue(adjustment.reason().startsWith("[agent:agent-1] "), adjustment.reason());
        assertTrue(adjustment.reason().contains("launch week"));
    }

    @Test
    void aCallNobodyAdminApprovedChangesNothing() throws Exception {
        for (CallerIdentity who : new CallerIdentity[]{null, APPROVER}) {
            caller = who;
            JsonNode out = parse(tool().proposeRaise("studio", 50.0, "launch week"));
            assertFalse(out.get("applied").asBoolean(), String.valueOf(who));
            assertTrue(out.get("message").asText().contains("eddi-admin"), out.toString());
        }
        assertEquals(35.0, service.snapshot("studio").capUsd());
        assertEquals(0, store.read("studio", "2026-11").map(m -> m.adjustments().size()).orElse(0));
    }

    @Test
    void anAdminOnlyActsAsApproverNotAsTheProposer() throws Exception {
        // an unapproved turn must not even learn the numbers
        caller = APPROVER;
        String answer = tool().proposeRaise("studio", 50.0, "x");
        assertFalse(answer.contains("35"), answer);
    }

    @Test
    void aRaiseMustActuallyRaiseAndStayUnderTheAmountCeiling() throws Exception {
        assertFalse(parse(tool().proposeRaise("studio", 35.0, "same")).get("applied").asBoolean());
        assertFalse(parse(tool().proposeRaise("studio", 20.0, "lower")).get("applied").asBoolean());
        var limits = new AgentOpsConfig.BudgetProposal();
        limits.setMaxAmountUsd(10.0);
        JsonNode tooMuch = parse(tool(limits).proposeRaise("studio", 50.0, "big"));
        assertFalse(tooMuch.get("applied").asBoolean());
        assertTrue(tooMuch.get("message").asText().contains("$10.00"), tooMuch.toString());
        assertEquals(35.0, service.snapshot("studio").capUsd());
        assertTrue(parse(tool(limits).proposeRaise("studio", 45.0, "exactly the ceiling")).get("applied").asBoolean());
    }

    @Test
    void aTeamOutsideTheScopeCannotBeNamedEvenThoughItExists() throws Exception {
        JsonNode raise = parse(tool().proposeRaise("stranger", 20.0, "x"));
        JsonNode transfer = parse(tool().proposeTransfer("studio", "stranger", 5.0, "x"));
        assertFalse(raise.get("applied").asBoolean());
        assertFalse(transfer.get("applied").asBoolean());
        assertEquals(10.0, service.snapshot("stranger").capUsd());
    }

    @Test
    void aTeamTheUserMayNotViewCannotBeNamed() throws Exception {
        var scope = new AgentOpsScope(List.of("studio", "radar"), id -> !id.equals("radar"));
        var tool = new TeamBudgetProposalTool(scope, null, service, () -> caller, "agent-1", "conv-1", CLOCK);
        assertFalse(parse(tool.proposeTransfer("studio", "radar", 5.0, "x")).get("applied").asBoolean());
        assertEquals(30.0, service.snapshot("radar").capUsd());
    }

    @Test
    void aTransferMovesCapBetweenTheTeams() throws Exception {
        JsonNode out = parse(tool().proposeTransfer("radar", "studio", 10.0, "radar is idle"));

        assertTrue(out.get("applied").asBoolean(), out.toString());
        assertEquals(20.0, service.snapshot("radar").capUsd());
        assertEquals(45.0, service.snapshot("studio").capUsd());
    }

    @Test
    void aReplayedTransferMovesNothingTwice() throws Exception {
        // a fresh tool instance per call: the per-turn counter must not be what saves
        // us
        assertTrue(parse(tool().proposeTransfer("radar", "studio", 10.0, "radar is idle")).get("applied").asBoolean());
        assertTrue(parse(tool().proposeTransfer("radar", "studio", 10.0, "radar is idle")).get("applied").asBoolean());

        assertEquals(20.0, service.snapshot("radar").capUsd());
        assertEquals(45.0, service.snapshot("studio").capUsd());
    }

    @Test
    void theTransferKeyDependsOnEveryPartOfTheProposal() {
        var t = tool();
        String base = t.transferKey("radar", "studio", 10.0, "r");
        assertEquals(base, t.transferKey("radar", "studio", 10.0, "r"));
        assertNotEquals(base, t.transferKey("studio", "radar", 10.0, "r"));
        assertNotEquals(base, t.transferKey("radar", "studio", 11.0, "r"));
        assertNotEquals(base, t.transferKey("radar", "studio", 10.0, "other"));
    }

    @Test
    void aTransferIsRefusedWhenTheSourceCannotSpareIt() throws Exception {
        var limits = new AgentOpsConfig.BudgetProposal();
        limits.setMaxAmountUsd(100.0);
        store.addSpend("radar", "2026-11", 25.0, Instant.parse("2027-01-01T00:00:00Z"));

        JsonNode out = parse(tool(limits).proposeTransfer("radar", "studio", 20.0, "x"));

        assertFalse(out.get("applied").asBoolean());
        assertEquals(30.0, service.snapshot("radar").capUsd());
    }

    @Test
    void theAmountCeilingAppliesToTransfersToo() throws Exception {
        JsonNode out = parse(tool().proposeTransfer("radar", "studio", 26.0, "too much"));
        assertFalse(out.get("applied").asBoolean());
        assertTrue(out.get("message").asText().contains("$25.00"), out.toString());
    }

    @Test
    void oneTurnMakesAtMostMaxPerTurnCalls() throws Exception {
        var tool = tool();
        assertTrue(parse(tool.proposeRaise("studio", 40.0, "first")).get("applied").asBoolean());
        JsonNode second = parse(tool.proposeRaise("studio", 45.0, "second"));
        assertFalse(second.get("applied").asBoolean());
        assertTrue(second.get("message").asText().contains("this turn"), second.toString());
        assertEquals(40.0, service.snapshot("studio").capUsd());
    }

    @Test
    void anAgentMayHaveOnlyMaxPerDayChangesAppliedInADay() throws Exception {
        var limits = new AgentOpsConfig.BudgetProposal();
        limits.setMaxPerDay(2);
        assertTrue(parse(tool(limits).proposeRaise("studio", 40.0, "one")).get("applied").asBoolean());
        assertTrue(parse(tool(limits).proposeRaise("studio", 45.0, "two")).get("applied").asBoolean());
        JsonNode third = parse(tool(limits).proposeRaise("studio", 50.0, "three"));

        assertFalse(third.get("applied").asBoolean());
        assertTrue(third.get("message").asText().contains("24 hours"), third.toString());
        assertEquals(45.0, service.snapshot("studio").capUsd());
    }

    @Test
    void theDailyCapIsPerAgentAndAnotherAgentsChangesDoNotCount() throws Exception {
        var limits = new AgentOpsConfig.BudgetProposal();
        limits.setMaxPerDay(1);
        assertTrue(parse(tool(limits).proposeRaise("studio", 40.0, "mine")).get("applied").asBoolean());
        var other = new TeamBudgetProposalTool(new AgentOpsScope(List.of("studio"), id -> true), limits, service, () -> caller, "agent-2",
                "conv-2", CLOCK);

        assertTrue(parse(other.proposeRaise("studio", 45.0, "theirs")).get("applied").asBoolean());
    }

    @Test
    void aBlankReasonIsRefused() throws Exception {
        assertFalse(parse(tool().proposeRaise("studio", 40.0, "  ")).get("applied").asBoolean());
        assertEquals(35.0, service.snapshot("studio").capUsd());
    }

    @Test
    void aBudgetThatCannotBeReadChangesNothing() throws Exception {
        store.down = true;
        JsonNode out = parse(tool().proposeRaise("studio", 40.0, "x"));
        store.down = false;
        assertFalse(out.get("applied").asBoolean());
        assertEquals(35.0, service.snapshot("studio").capUsd());
    }

    @Test
    void theGatePatternCoversBothToolNames() {
        String regex = TeamBudgetProposalTool.GATE_PATTERN.replace("*", ".*");
        assertTrue(TeamBudgetProposalTool.RAISE_TOOL.matches(regex));
        assertTrue(TeamBudgetProposalTool.TRANSFER_TOOL.matches(regex));
    }
}
