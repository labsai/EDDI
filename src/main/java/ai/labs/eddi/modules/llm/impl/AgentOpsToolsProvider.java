/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalAnalyticsService;
import ai.labs.eddi.engine.hitl.tools.ClearedToolCalls;
import ai.labs.eddi.engine.hitl.tools.TaskToolApprovalsResolver;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig;
import ai.labs.eddi.modules.llm.tools.impl.AgentOpsScope;
import ai.labs.eddi.modules.llm.tools.impl.FollowUpTools;
import ai.labs.eddi.modules.llm.tools.impl.TeamBudgetProposalTool;
import ai.labs.eddi.modules.llm.tools.impl.TeamScorecardTool;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The agent-operations tools: a team scorecard, a budget proposal and follow-up
 * scheduling. Each is <b>off unless its slug is named in the task's
 * {@code builtInToolsWhitelist}</b> — unlike the plain built-ins, an omitted
 * whitelist ("all built-in tools") does not admit them, because two of the
 * three spend or move money and the third books future work.
 * <p>
 * The budget tool has one more condition: it is assembled only when the task's
 * effective {@code toolApprovals} gates both of its tools. The approval card is
 * what makes "applied only when a human approves" true, so a configuration that
 * would let the call run ungated gets no tool at all (and a warning in the
 * log), rather than a tool that depends on the designer remembering a rule.
 * <p>
 * Tools are constructed per turn, bound to the conversation — group, user,
 * agent — so none of them takes an identity parameter the model could fill in.
 *
 * @since 6.7.0
 */
@ApplicationScoped
class AgentOpsToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(AgentOpsToolsProvider.class);

    private final TeamBudgetService budgetService;
    private final IGroupWorkspaceStore workspaceStore;
    private final ApprovalAnalyticsService analytics;
    private final IUserMemoryStore userMemoryStore;
    private final IScheduleStore scheduleStore;
    private final IAgentGroupStore groupStore;
    private final LiveDiscussionRegistry liveDiscussionRegistry;
    private final ResourceAccessGuard guard;
    private final CallerIdentityContext callerIdentityContext;
    private final boolean toolHitlEnabled;
    private final Clock clock;

    @Inject
    AgentOpsToolsProvider(TeamBudgetService budgetService, IGroupWorkspaceStore workspaceStore, ApprovalAnalyticsService analytics,
            IUserMemoryStore userMemoryStore, IScheduleStore scheduleStore, IAgentGroupStore groupStore,
            LiveDiscussionRegistry liveDiscussionRegistry, ResourceAccessGuard guard, CallerIdentityContext callerIdentityContext,
            @ConfigProperty(name = "eddi.hitl.tool.enabled", defaultValue = "true") boolean toolHitlEnabled) {
        this(budgetService, workspaceStore, analytics, userMemoryStore, scheduleStore, groupStore, liveDiscussionRegistry, guard,
                callerIdentityContext, toolHitlEnabled, Clock.systemUTC());
    }

    AgentOpsToolsProvider(TeamBudgetService budgetService, IGroupWorkspaceStore workspaceStore, ApprovalAnalyticsService analytics,
            IUserMemoryStore userMemoryStore, IScheduleStore scheduleStore, IAgentGroupStore groupStore,
            LiveDiscussionRegistry liveDiscussionRegistry, ResourceAccessGuard guard, CallerIdentityContext callerIdentityContext,
            boolean toolHitlEnabled, Clock clock) {
        this.budgetService = budgetService;
        this.workspaceStore = workspaceStore;
        this.analytics = analytics;
        this.userMemoryStore = userMemoryStore;
        this.scheduleStore = scheduleStore;
        this.groupStore = groupStore;
        this.liveDiscussionRegistry = liveDiscussionRegistry;
        this.guard = guard;
        this.callerIdentityContext = callerIdentityContext;
        this.toolHitlEnabled = toolHitlEnabled;
        this.clock = clock;
    }

    @Override
    public String source() {
        return "builtin";
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        Boolean enableBuiltInTools = ctx.task() != null ? ctx.task().getEnableBuiltInTools() : null;
        IConversationMemory memory = ctx.memory();
        if (enableBuiltInTools == null || !enableBuiltInTools || memory == null) {
            return ToolContribution.empty();
        }
        AgentOpsConfig config = ctx.task().getAgentOps();
        List<Object> tools = new ArrayList<>();
        // Captured here, on the turn's thread where the caller is bound.
        CallerIdentity caller = callerIdentityContext != null ? callerIdentityContext.current() : null;

        if (ctx.isWhitelisted(AgentOpsConfig.SLUG_SCORECARD)) {
            tools.add(new TeamScorecardTool(scope(ctx, caller, true), AgentOpsConfig.scorecardOf(config), budgetService, workspaceStore, analytics,
                    userMemoryStore, clock));
        }
        if (ctx.isWhitelisted(AgentOpsConfig.SLUG_BUDGET_PROPOSAL)) {
            if (budgetProposalIsGated(ctx)) {
                tools.add(new TeamBudgetProposalTool(scope(ctx, caller, true), AgentOpsConfig.budgetProposalOf(config), budgetService,
                        () -> callerIdentityContext != null ? callerIdentityContext.current() : null, ctx.agentId(), memory.getConversationId(),
                        clock));
            } else {
                LOGGER.warnf("Budget proposal tools withheld from agent '%s': its effective toolApprovals does not require approval for '%s' "
                        + "(or tool approvals are switched off). Add requireApproval: [\"%s\"] to hitlConfig.toolApprovals.", sanitize(ctx.agentId()),
                        TeamBudgetProposalTool.GATE_PATTERN, TeamBudgetProposalTool.GATE_PATTERN, TeamBudgetProposalTool.GATE_PATTERN);
            }
        }
        if (ctx.isWhitelisted(AgentOpsConfig.SLUG_FOLLOW_UP) && scheduleStore != null) {
            tools.add(new FollowUpTools(new FollowUpTools.Booker(ctx.agentId(), memory.getUserId(), memory.getConversationId(),
                    ctx.groupConversationId() != null, creatorTeams()), AgentOpsConfig.followUpOf(config), scheduleStore, clock));
        }
        if (tools.isEmpty()) {
            return ToolContribution.empty();
        }
        var reflected = ToolObjectReflector.reflect(tools);
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of(), List.of(),
                reflected.toolCanonicalNames());
    }

    /**
     * Whether the task's effective approval config gates both budget tools. The
     * same merge the tool loop applies (agent-level block combined with the task's
     * per the configured mode) and the same matcher, run on a probe call per tool.
     */
    boolean budgetProposalIsGated(ToolAssemblyContext ctx) {
        if (!toolHitlEnabled) {
            return false;
        }
        ToolApprovalsConfig effective = TaskToolApprovalsResolver.resolve(ctx.memory().getAgentToolApprovalsConfig(),
                ctx.task().getToolApprovals());
        if (effective == null) {
            return false;
        }
        ToolApprovalGate gate = new ToolApprovalGate();
        for (String name : List.of(TeamBudgetProposalTool.RAISE_TOOL, TeamBudgetProposalTool.TRANSFER_TOOL)) {
            var probe = ToolExecutionRequest.builder().id("agent-ops-probe").name(name).arguments("{}").build();
            if (gate.classify(List.of(probe), Map.of(name, "builtin"), effective, ClearedToolCalls.none()).gated().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<String> creatorTeams() {
        try {
            return guard != null ? guard.currentTeamSubjects() : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The agent's own group (a running discussion's group), the teams that group's
     * budget oversees, then the designer's extra groups. The membership check on
     * the live discussion is the same as every other group-bound tool: a
     * client-suppliable {@code groupConversationId} authorizes nothing by itself.
     */
    AgentOpsScope scope(ToolAssemblyContext ctx, CallerIdentity caller, boolean withOversight) {
        Set<String> groups = new LinkedHashSet<>();
        String callerConversationId = ctx.memory() != null ? ctx.memory().getConversationId() : null;
        if (ctx.groupConversationId() != null && liveDiscussionRegistry != null) {
            var live = liveDiscussionRegistry.getForMember(ctx.groupConversationId(), callerConversationId);
            if (live.isPresent() && live.get().getGroupId() != null) {
                String own = live.get().getGroupId();
                groups.add(own);
                if (withOversight) {
                    groups.addAll(oversightTeams(own));
                }
            }
        }
        for (String id : AgentOpsConfig.scorecardOf(ctx.task().getAgentOps()).effectiveGroupIds()) {
            if (id != null && !id.isBlank()) {
                groups.add(id.strip());
            }
        }
        String userId = ctx.userId();
        return new AgentOpsScope(List.copyOf(groups), groupId -> mayView(groupId, userId, caller));
    }

    private List<String> oversightTeams(String groupId) {
        try {
            AgentGroupConfiguration config = budgetService.config(groupId);
            TeamBudgetConfig budget = config != null ? config.getTeamBudget() : null;
            TeamBudgetConfig.Oversight oversight = budget != null ? budget.oversight() : null;
            return oversight != null && oversight.teams() != null ? oversight.teams() : List.of();
        } catch (RuntimeException e) {
            LOGGER.debugf("Oversight teams of group %s unreadable: %s", sanitize(groupId), sanitize(e.getMessage()));
            return List.of();
        }
    }

    private boolean mayView(String groupId, String userId, CallerIdentity caller) {
        if (guard == null) {
            return true;
        }
        return guard.principalMayView(groupId, userId, caller != null && caller.isAdminActingAs(userId));
    }
}
