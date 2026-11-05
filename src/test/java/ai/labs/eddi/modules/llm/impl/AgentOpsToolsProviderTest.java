/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalAnalyticsService;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.impl.AgentOpsScope;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOpsToolsProviderTest {

    private static final String GC_ID = "gc-1";
    private static final String MEMBER_CONV = "member-conv";

    private TeamBudgetService budget;
    private ResourceAccessGuard guard;
    private LiveDiscussionRegistry registry;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() {
        budget = mock(TeamBudgetService.class);
        guard = mock(ResourceAccessGuard.class);
        registry = new LiveDiscussionRegistry();
        memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn(MEMBER_CONV);
        when(memory.getUserId()).thenReturn("alice");
        when(memory.getAgentToolApprovalsConfig()).thenReturn(null);
    }

    private AgentOpsToolsProvider provider(boolean toolHitlEnabled) {
        return new AgentOpsToolsProvider(budget, mock(IGroupWorkspaceStore.class), mock(ApprovalAnalyticsService.class),
                mock(IUserMemoryStore.class), mock(IScheduleStore.class), mock(IAgentGroupStore.class), registry, guard,
                mock(CallerIdentityContext.class), toolHitlEnabled, Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC));
    }

    private ToolAssemblyContext ctx(LlmConfiguration.Task task, List<String> whitelist, String groupConversationId) {
        return new ToolAssemblyContext(memory, task, whitelist, null, "alice", "agent-1", groupConversationId);
    }

    private static LlmConfiguration.Task task(boolean builtIns) {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(builtIns);
        return task;
    }

    private static Set<String> names(ToolContribution contribution) {
        return contribution.specs().stream().map(ToolSpecification::name).collect(Collectors.toSet());
    }

    private static ToolApprovalsConfig gating(String... patterns) {
        var config = new ToolApprovalsConfig();
        config.setRequireApproval(List.of(patterns));
        return config;
    }

    @Test
    void everyToolIsOffWithoutAWhitelistAndWhenTheWhitelistDoesNotNameIt() {
        var provider = provider(true);
        assertTrue(provider.contribute(ctx(task(true), null, null)).specs().isEmpty(), "an omitted whitelist means 'all built-ins', not these");
        assertTrue(provider.contribute(ctx(task(true), List.of(), null)).specs().isEmpty());
        assertTrue(provider.contribute(ctx(task(true), List.of("calculator", "datetime"), null)).specs().isEmpty());
    }

    @Test
    void builtInsSwitchedOffMeansNoTools() {
        assertTrue(provider(true)
                .contribute(ctx(task(false), List.of("team_scorecard", "schedule_followup"), null)).specs().isEmpty());
    }

    @Test
    void theScorecardSlugAdmitsOnlyTheScorecard() {
        assertEquals(Set.of("read_team_scorecard"), names(provider(true).contribute(ctx(task(true), List.of("team_scorecard"), null))));
    }

    @Test
    void theFollowUpSlugAdmitsTheThreeFollowUpTools() {
        assertEquals(Set.of("schedule_followup", "list_followups", "cancel_followup"),
                names(provider(true).contribute(ctx(task(true), List.of("schedule_followup"), null))));
    }

    @Test
    void theBudgetToolsAreWithheldUnlessApprovalIsRequiredForBoth() {
        var whitelist = List.of("propose_team_budget");
        // no approval config at all
        assertTrue(provider(true).contribute(ctx(task(true), whitelist, null)).specs().isEmpty());
        // gating something else
        when(memory.getAgentToolApprovalsConfig()).thenReturn(gating("delete_*"));
        assertTrue(provider(true).contribute(ctx(task(true), whitelist, null)).specs().isEmpty());
        // gating only one of the two
        when(memory.getAgentToolApprovalsConfig()).thenReturn(gating("propose_team_budget_raise"));
        assertTrue(provider(true).contribute(ctx(task(true), whitelist, null)).specs().isEmpty());
        // exempting a gated tool beats the gate
        var exempt = gating("propose_team_budget_*");
        exempt.setExempt(List.of("propose_team_budget_transfer"));
        when(memory.getAgentToolApprovalsConfig()).thenReturn(exempt);
        assertTrue(provider(true).contribute(ctx(task(true), whitelist, null)).specs().isEmpty());
    }

    @Test
    void theBudgetToolsAppearWhenBothAreGatedAtAgentOrTaskLevel() {
        var whitelist = List.of("propose_team_budget");
        when(memory.getAgentToolApprovalsConfig()).thenReturn(gating("propose_team_budget_*"));
        assertEquals(Set.of("propose_team_budget_raise", "propose_team_budget_transfer"),
                names(provider(true).contribute(ctx(task(true), whitelist, null))));

        when(memory.getAgentToolApprovalsConfig()).thenReturn(null);
        var task = task(true);
        task.setToolApprovals(gating("builtin:propose_team_budget_raise", "propose_team_budget_transfer"));
        assertEquals(2, provider(true).contribute(ctx(task, whitelist, null)).specs().size());
    }

    @Test
    void theBudgetToolsAreWithheldWhenToolApprovalsAreSwitchedOffClusterWide() {
        when(memory.getAgentToolApprovalsConfig()).thenReturn(gating("propose_team_budget_*"));
        assertTrue(provider(false).contribute(ctx(task(true), List.of("propose_team_budget"), null)).specs().isEmpty());
    }

    @Test
    void theScopeIsTheLiveGroupItsOversightTeamsAndTheConfiguredExtrasForAUserWhoMayView() {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId("board");
        gc.getMemberConversationIds().put("agent-1", MEMBER_CONV);
        registry.register(gc);
        var board = new AgentGroupConfiguration();
        board.setTeamBudget(new TeamBudgetConfig(50.0, CostPolicy.SYNTHESIZE_NOW, new TeamBudgetConfig.Oversight(List.of("studio", "radar"))));
        when(budget.config("board")).thenReturn(board);
        when(guard.principalMayView(anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> !"radar".equals(inv.getArgument(0)));
        var task = task(true);
        var agentOps = new AgentOpsConfig();
        var scorecard = new AgentOpsConfig.Scorecard();
        scorecard.setGroupIds(List.of("extra", "board"));
        agentOps.setScorecard(scorecard);
        task.setAgentOps(agentOps);

        AgentOpsScope scope = provider(true).scope(ctx(task, List.of("team_scorecard"), GC_ID), null, true);

        assertEquals(List.of("board", "studio", "radar", "extra"), scope.candidates(), "own group first, no duplicates");
        assertEquals(List.of("board", "studio", "extra"), scope.visible(), "radar is not viewable by alice");
    }

    @Test
    void aGroupConversationIdTheCallerIsNotAMemberOfGrantsNoScope() {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId("board");
        gc.getMemberConversationIds().put("agent-2", "someone-elses-conv");
        registry.register(gc);

        AgentOpsScope scope = provider(true).scope(ctx(task(true), List.of("team_scorecard"), GC_ID), null, true);

        assertTrue(scope.candidates().isEmpty());
        assertFalse(scope.contains("board"));
    }
}
