/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.HandoffPolicies;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.GroupHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.TeamHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffRunState;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.SourceKind;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffRequest;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffService;
import ai.labs.eddi.modules.llm.tools.impl.TeamHandoffTool;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Contributes {@code handoff_to_team} when, and only when, a handoff policy
 * applies to this turn.
 * <p>
 * <b>Absence is off.</b> No policy means no tool: it costs no prompt tokens and
 * cannot be argued with, whereas a tool that exists and always refuses invites
 * retries. Every doubt resolves to "no tool":
 * <ul>
 * <li>{@code enableBuiltInTools} must be true, and a non-empty
 * {@code builtInToolsWhitelist} must name {@value #WHITELIST_SLUG} — the group
 * or the agent config cannot widen what the agent's own switches narrowed;</li>
 * <li><b>Inside a group turn the GROUP's policy governs.</b> The agent's own
 * {@code teamHandoff} is ignored there, so a member cannot hand off as itself
 * to get around the group. An unverified group conversation id (it arrives as a
 * client-suppliable context variable) yields no tool at all, rather than
 * falling back to the agent's own policy;</li>
 * <li>an unreadable group config withholds the tool: "could not read the
 * policy" is not "there is no policy".</li>
 * </ul>
 * The source identity (agent id, or the live discussion's group id) is read
 * from the engine's own state here, never from a tool argument.
 *
 * @since 6.7.0
 */
class TeamHandoffToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(TeamHandoffToolsProvider.class);

    /** The {@code builtInToolsWhitelist} slug that admits this tool. */
    public static final String WHITELIST_SLUG = "team_handoff";
    /** Step-data key counting a standalone conversation's filed handoffs. */
    static final String FILED_KEY = "handoff:filed";

    private final LiveDiscussionRegistry liveDiscussionRegistry;
    private final IAgentGroupStore groupStore;
    private final TeamHandoffService service;

    TeamHandoffToolsProvider(LiveDiscussionRegistry liveDiscussionRegistry, IAgentGroupStore groupStore, TeamHandoffService service) {
        this.liveDiscussionRegistry = liveDiscussionRegistry;
        this.groupStore = groupStore;
        this.service = service;
    }

    @Override
    public String source() {
        return TeamHandoffTool.SOURCE_TAG;
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        if (service == null) {
            return ToolContribution.empty();
        }
        Boolean enableBuiltInTools = ctx.task() != null ? ctx.task().getEnableBuiltInTools() : null;
        if (enableBuiltInTools == null || !enableBuiltInTools) {
            return ToolContribution.empty();
        }
        if (!ctx.hasNoWhitelist() && !ctx.isWhitelisted(WHITELIST_SLUG)) {
            LOGGER.debugf("Handoff tool withheld from agent '%s': its builtInToolsWhitelist does not name '%s'", sanitize(ctx.agentId()),
                    WHITELIST_SLUG);
            return ToolContribution.empty();
        }
        TeamHandoffTool.Context toolContext = ctx.groupConversationId() != null ? groupContext(ctx) : standaloneContext(ctx);
        if (toolContext == null || toolContext.targets().isEmpty()) {
            return ToolContribution.empty();
        }
        TeamHandoffTool tool = new TeamHandoffTool(service, toolContext);
        return new ToolContribution(List.of(tool.specification()), Map.of(TeamHandoffTool.TOOL_NAME, tool.executor()),
                Map.of(TeamHandoffTool.TOOL_NAME, TeamHandoffTool.SOURCE_TAG), Map.of(), List.of(),
                Map.of(TeamHandoffTool.TOOL_NAME, TeamHandoffTool.TOOL_NAME));
    }

    // ------------------------------------------------------------- standalone

    private TeamHandoffTool.Context standaloneContext(ToolAssemblyContext ctx) {
        IConversationMemory memory = ctx.memory();
        TeamHandoffConfig policy = ctx.task() != null ? ctx.task().getTeamHandoff() : null;
        if (policy == null || memory == null || memory.getAgentId() == null) {
            return null;
        }
        List<HandoffTarget> targets = usable(policy.targets());
        int alreadyFiled = countFiled(memory);
        var quota = new ConversationRunQuota(alreadyFiled, policy.maxPerConversation());
        return new TeamHandoffTool.Context(SourceKind.AGENT, memory.getAgentId(), memory.getAgentVersion(), memory.getConversationId(), null,
                memory.getUserId(), targets, policy.maxPerTurn(), policy.maxDescriptionChars(), 0, List.of(), quota, null, outcome -> {
                    if (outcome.result().outcome() == HandoffResult.Outcome.FILED
                            || outcome.result().outcome() == HandoffResult.Outcome.QUEUED_FOR_REVIEW) {
                        recordFiled(memory, outcome.result().handoffId());
                    }
                });
    }

    static int countFiled(IConversationMemory memory) {
        var allSteps = memory.getAllSteps();
        if (allSteps == null) {
            return 0;
        }
        List<IData<Object>> entries = allSteps.getAllLatestData(FILED_KEY);
        int count = 0;
        if (entries != null) {
            for (IData<Object> entry : entries) {
                if (entry != null && entry.getResult() instanceof List<?> ids) {
                    count += ids.size();
                }
            }
        }
        return count;
    }

    private static void recordFiled(IConversationMemory memory, String handoffId) {
        var step = memory.getCurrentStep();
        if (step == null || handoffId == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        IData<Object> existing = step.getLatestData(FILED_KEY);
        if (existing != null && existing.getResult() instanceof List<?> previous) {
            previous.forEach(id -> ids.add(String.valueOf(id)));
        }
        ids.add(handoffId);
        step.storeData(new Data<>(FILED_KEY, ids));
    }

    /**
     * A per-conversation cap that holds under concurrent tool calls: the count
     * already filed in earlier steps plus the slots reserved this turn.
     */
    static final class ConversationRunQuota implements HandoffRequest.RunQuota {
        private final int alreadyFiled;
        private final int cap;
        private final AtomicInteger reservedThisTurn = new AtomicInteger();

        ConversationRunQuota(int alreadyFiled, int cap) {
            this.alreadyFiled = alreadyFiled;
            this.cap = cap;
        }

        @Override
        public boolean tryReserve() {
            if (alreadyFiled + reservedThisTurn.incrementAndGet() > cap) {
                reservedThisTurn.decrementAndGet();
                return false;
            }
            return true;
        }

        @Override
        public void release() {
            reservedThisTurn.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------ group

    private TeamHandoffTool.Context groupContext(ToolAssemblyContext ctx) {
        if (liveDiscussionRegistry == null || groupStore == null) {
            return null;
        }
        // Membership, not existence: groupConversationId is a client-suppliable context
        // variable, so get(id).isPresent() would authorize nothing.
        String callerConversationId = ctx.memory() != null ? ctx.memory().getConversationId() : null;
        var live = liveDiscussionRegistry.getForMember(ctx.groupConversationId(), callerConversationId);
        if (live.isEmpty()) {
            return null;
        }
        GroupConversation gc = live.get();
        AgentGroupConfiguration group = resolveGroup(gc.getGroupId());
        GroupHandoffConfig policy = group != null ? group.getHandoffConfig() : null;
        if (policy == null || !policy.sends() || !participates(policy, group, gc, ctx.agentId())) {
            return null;
        }
        HandoffRunState state = gc.handoffStateOrCreate();
        HandoffRequest.RunQuota quota = new HandoffRequest.RunQuota() {
            @Override
            public boolean tryReserve() {
                return state.tryReserve(policy.maxPerDiscussion());
            }

            @Override
            public void release() {
                state.release();
            }
        };
        return new TeamHandoffTool.Context(SourceKind.GROUP, gc.getGroupId(), null, gc.getId(), ctx.agentId(), gc.getUserId(),
                usable(policy.targets()),
                policy.maxPerTurn(), policy.maxDescriptionChars(), state.getHop(), state.getLineage(), quota,
                reference -> artifactIdByName(gc, reference),
                outcome -> {
                    state.recordFiled(
                            new HandoffRunState.FiledHandoff(outcome.result().handoffId(), outcome.target().alias(), outcome.target().groupId(),
                                    outcome.result().outcome().name(), outcome.subject()));
                    state.queueEvent(
                            new HandoffRunState.PendingEvent(outcome.result().handoffId(), outcome.target().alias(), outcome.target().groupId(),
                                    outcome.result().outcome().name(), null, "TOOL", outcome.subject()));
                });
    }

    /** Who gets the tool inside a discussion, per {@code toolParticipants}. */
    static boolean participates(GroupHandoffConfig policy, AgentGroupConfiguration group, GroupConversation gc, String agentId) {
        String participants = policy.toolParticipants();
        if (HandoffPolicies.PARTICIPANTS_ALL.equals(participants)) {
            return true;
        }
        if (agentId == null) {
            return false;
        }
        if (HandoffPolicies.PARTICIPANTS_MODERATOR.equals(participants)) {
            return agentId.equals(group.getModeratorAgentId());
        }
        if (participants.startsWith(HandoffPolicies.PARTICIPANTS_ROLE_PREFIX)) {
            String role = participants.substring(HandoffPolicies.PARTICIPANTS_ROLE_PREFIX.length()).strip();
            for (GroupMember member : GroupConversationService.rosterWithRecruits(group, gc)) {
                if (agentId.equals(member.agentId()) && member.role() != null && member.role().strip().equalsIgnoreCase(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String artifactIdByName(GroupConversation gc, String reference) {
        List<SharedArtifact> artifacts = gc.getArtifacts();
        if (artifacts == null || reference == null) {
            return null;
        }
        for (SharedArtifact artifact : artifacts) {
            if (artifact != null && reference.equalsIgnoreCase(artifact.getName())) {
                return artifact.getId();
            }
        }
        return null;
    }

    /**
     * Targets that can still be resolved: a missing group id is dropped with one
     * WARN, so a ZIP that named a team this instance does not have still imports
     * and the model is simply not offered a dead alias.
     */
    private List<HandoffTarget> usable(List<HandoffTarget> declared) {
        List<HandoffTarget> out = new ArrayList<>();
        for (HandoffTarget target : declared) {
            if (target == null || target.groupId() == null || target.groupId().isBlank()) {
                continue;
            }
            try {
                if (groupStore != null && groupStore.getCurrentResourceId(target.groupId()) == null) {
                    LOGGER.warnf("Handoff target '%s' (group '%s') does not exist — not offered to the model", sanitize(target.alias()),
                            sanitize(target.groupId()));
                    continue;
                }
            } catch (Exception e) {
                LOGGER.warnf("Handoff target '%s' (group '%s') could not be resolved — not offered to the model: %s", sanitize(target.alias()),
                        sanitize(target.groupId()), e.getMessage());
                continue;
            }
            out.add(target);
        }
        return out;
    }

    private AgentGroupConfiguration resolveGroup(String groupId) {
        if (groupId == null) {
            return null;
        }
        try {
            var resourceId = groupStore.getCurrentResourceId(groupId);
            return resourceId == null ? null : groupStore.read(groupId, resourceId.getVersion());
        } catch (Exception e) {
            LOGGER.warnf("Could not read handoff policy for group '%s' — withholding the handoff tool: %s", sanitize(groupId), e.getMessage());
            return null;
        }
    }
}
