/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import ai.labs.eddi.modules.llm.tools.CreateSubAgentTool;
import ai.labs.eddi.modules.llm.tools.FindAgentsByCapabilityTool;
import ai.labs.eddi.modules.llm.tools.RecruitAgentTool;
import ai.labs.eddi.modules.llm.tools.TeardownAgentTool;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Builds the four dynamic-agent tools — create / converse / discover-by-
 * capability / teardown — with their shared per-conversation tracking lists and
 * the guardrails ({@code allowRecruitment}, delegation depth,
 * {@code maxCreatedAgentsPerDiscussion} seeding) that bound them (R2 step 2).
 * Extracted from the anonymous block inside
 * {@code AgentOrchestrator#collectAllBuiltInTools} as a pure move — no behavior
 * change.
 * <p>
 * Constructed fresh per call (see {@code AgentOrchestrator
 * .dynamicAgentToolsProvider()}), mirroring {@code GroupAttachmentBinder}'s and
 * {@code GroupLifecycleOps}' pattern from Wave R: {@code deploymentStore} is
 * {@code @Inject}-field-injected on {@code AgentOrchestrator} and not yet
 * populated when that class's own constructor runs, so this provider must read
 * its current value at call time rather than capture it once eagerly.
 * <p>
 * <b>V7 note (unchanged by this move):</b> these tools are added only when a
 * {@code builtInToolsWhitelist} is configured. An agent with
 * {@code enableBuiltInTools=true}, no whitelist, and {@code
 * dynamicAgents.enabled=true} gets none of them — the no-whitelist branch of
 * {@code collectAllBuiltInTools} never calls this provider. That asymmetry is
 * preserved verbatim here and is tracked as verify-task V7 in
 * {@code planning/group-collaboration-improvements-plan.md} §2; fixing it is a
 * deliberate behavior change belonging in its own labeled commit, with a
 * deliberate update to {@code AgentOrchestratorBuiltInToolWiringTest}, not in a
 * pure-move refactor.
 */
class DynamicAgentToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(DynamicAgentToolsProvider.class);

    /** Well-known data keys for dynamic agent lifecycle tracking. */
    static final String KEY_DYNAMIC_CREATED_AGENT_IDS = "dynamic:created_agent_ids";
    static final String KEY_DYNAMIC_RETAINED_AGENT_IDS = "dynamic:retained_agent_ids";

    private final AgentSetupService agentSetupService;
    private final CapabilityRegistryService capabilityRegistryService;
    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final IDeploymentStore deploymentStore;
    private final LiveDiscussionRegistry liveDiscussionRegistry;

    DynamicAgentToolsProvider(AgentSetupService agentSetupService, CapabilityRegistryService capabilityRegistryService,
            IConversationService conversationService, IAgentFactory agentFactory, IAgentStore agentStore,
            IDeploymentStore deploymentStore, LiveDiscussionRegistry liveDiscussionRegistry) {
        this.agentSetupService = agentSetupService;
        this.capabilityRegistryService = capabilityRegistryService;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.deploymentStore = deploymentStore;
        this.liveDiscussionRegistry = liveDiscussionRegistry;
    }

    @Override
    public String source() {
        return "dynamic";
    }

    /**
     * Gated on {@code enableBuiltInTools} as well as the whitelist, mirroring the
     * live path: {@code collectEnabledTools} returns before ever reaching
     * {@code collectAllBuiltInTools} when built-ins are off (null counts as off,
     * and null is the default), so these tools are unreachable in that case today.
     * <p>
     * The whitelist gate alone is NOT sufficient here. A config with
     * {@code enableBuiltInTools: false} and a stale {@code builtInToolsWhitelist}
     * still naming {@code "create_sub_agent"} currently gets nothing; without this
     * check it would get sub-agent creation, delegation and teardown — tools that
     * deploy real agents to production. That is the highest-blast-radius gate in
     * this class, so it is checked here rather than left to the caller.
     */
    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        Boolean enableBuiltInTools = ctx.task().getEnableBuiltInTools();
        if (enableBuiltInTools == null || !enableBuiltInTools) {
            return ToolContribution.empty();
        }
        List<Object> tools = new ArrayList<>();
        addDynamicAgentTools(tools, ctx.builtInToolsWhitelist(), ctx.memory(), ctx.groupConversationId());
        if (tools.isEmpty()) {
            return ToolContribution.empty();
        }
        var reflected = ToolObjectReflector.reflect(tools);
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of(),
                List.of(), reflected.toolCanonicalNames());
    }

    /**
     * Adds every whitelisted dynamic-agent tool to {@code tools}, wiring them to
     * one shared pair of tracking collections and persisting those collections into
     * step data so {@code GroupConversationService} can propagate them for
     * lifecycle cleanup.
     * <p>
     * Extracted verbatim from {@code collectAllBuiltInTools}; the whitelist is
     * passed in rather than re-read from the task so the caller keeps ownership of
     * the null/empty normalization.
     */
    void addDynamicAgentTools(List<Object> tools, List<String> whitelist, IConversationMemory memory) {
        addDynamicAgentTools(tools, whitelist, memory, null);
    }

    void addDynamicAgentTools(List<Object> tools, List<String> whitelist, IConversationMemory memory, String groupConversationId) {
        if (whitelist == null || whitelist.isEmpty()) {
            return;
        }
        // Finding F17: this list used to start EMPTY on every buildToolList call,
        // i.e. once per LLM task execution — so maxCreatedAgentsPerDiscussion was
        // enforced per TURN, and a 5-member x 3-phase discussion with the default
        // cap of 5 could deploy up to 75 agents to production. Seed it with the
        // agents already created in this conversation so the cap actually bounds
        // the discussion.
        List<String> sharedCreatedIds = new CopyOnWriteArrayList<>(seedCreatedAgentIds(memory));
        Set<String> sharedRetainedIds = ConcurrentHashMap.newKeySet();
        String parentAgentId = memory.getAgentId();
        String userId = memory.getUserId();
        DynamicAgentConfig dynamicConfig = resolveDynamicAgentConfig(memory);

        boolean anyDynamicToolAdded = false;
        if (whitelist.contains("create_sub_agent") && agentSetupService != null && conversationService != null) {
            tools.add(new CreateSubAgentTool(agentSetupService,
                    conversationService, parentAgentId, userId, dynamicConfig,
                    sharedCreatedIds, sharedRetainedIds));
            LOGGER.debugf("[DYNAMIC] CreateSubAgentTool enabled for agent='%s' (%d already created)",
                    sanitize(parentAgentId), sharedCreatedIds.size());
            anyDynamicToolAdded = true;
        }
        if (whitelist.contains("converse_with_agent") && conversationService != null) {
            // Findings F18/I4: the tool used to take only (service, userId) and
            // consult no guardrails at all — allowDelegation was never read and
            // nothing bounded delegation depth or target.
            int delegationDepth = resolveDelegationDepth(memory);
            tools.add(new ConverseWithAgentTool(conversationService, userId, dynamicConfig, delegationDepth));
            LOGGER.debugf("[DYNAMIC] ConverseWithAgentTool enabled for agent='%s' at delegation depth %d",
                    sanitize(parentAgentId), delegationDepth);
        }
        // Finding I4: allowRecruitment was documented as an enforced cap but was
        // never read. Capability lookup is the recruitment entry point, so it is
        // gated here.
        if (whitelist.contains("find_agents_by_capability") && capabilityRegistryService != null) {
            if (dynamicConfig.isEnabled() && dynamicConfig.isAllowRecruitment()) {
                tools.add(new FindAgentsByCapabilityTool(capabilityRegistryService));
                LOGGER.debugf("[DYNAMIC] FindAgentsByCapabilityTool enabled for agent='%s'", sanitize(parentAgentId));
            } else {
                LOGGER.debugf("[DYNAMIC] FindAgentsByCapabilityTool suppressed for agent='%s': allowRecruitment is off",
                        sanitize(parentAgentId));
            }
        }
        // I7: acting on that discovery. Same gate as the lookup above — finding an
        // agent and bringing it in are two halves of one capability, and allowing
        // one without the other is either a dead end or an ungated roster write.
        // Additionally requires a live group discussion: recruiting into a
        // standalone conversation has no roster to join.
        if (whitelist.contains("recruit_agent") && dynamicConfig.isEnabled() && dynamicConfig.isAllowRecruitment()
                && groupConversationId != null && liveDiscussionRegistry != null
                && liveDiscussionRegistry.get(groupConversationId).isPresent()) {
            tools.add(new RecruitAgentTool(liveDiscussionRegistry, groupConversationId, parentAgentId,
                    dynamicConfig, deploymentStore));
            LOGGER.debugf("[DYNAMIC] RecruitAgentTool enabled for agent='%s'", sanitize(parentAgentId));
            anyDynamicToolAdded = true;
        }
        if (whitelist.contains("teardown_agent") && agentFactory != null && agentStore != null) {
            tools.add(new TeardownAgentTool(agentFactory, agentStore, deploymentStore, sharedCreatedIds, sharedRetainedIds));
            LOGGER.debugf("[DYNAMIC] TeardownAgentTool enabled for agent='%s'", sanitize(parentAgentId));
            anyDynamicToolAdded = true;
        }

        // Store tracking lists in memory step data so GroupConversationService
        // can read them from the snapshot after each member turn and propagate
        // to GroupConversation for lifecycle cleanup (Copilot PR review fix).
        // The lists are stored by reference — after tool execution, they'll
        // contain all agent IDs accumulated during this turn.
        // getCurrentStep() is treated as nullable by seedCreatedAgentIds a few lines
        // up; assume the same here rather than half-guarding one of two reads of the
        // same value in the same method.
        var currentStep = memory.getCurrentStep();
        if (anyDynamicToolAdded && currentStep != null) {
            currentStep.storeData(new Data<>(KEY_DYNAMIC_CREATED_AGENT_IDS, sharedCreatedIds));
            currentStep.storeData(new Data<>(KEY_DYNAMIC_RETAINED_AGENT_IDS, sharedRetainedIds));
        }
    }

    /**
     * Resolves the DynamicAgentConfig for the current conversation. If the agent is
     * participating in a group discussion, the group's {@link DynamicAgentConfig}
     * is passed via context variable {@code dynamicAgentConfig} by
     * {@code GroupConversationService}. If no group config is present (standalone
     * agent), a permissive default is used.
     *
     * @param memory
     *            the conversation memory to check for group context
     * @return the resolved DynamicAgentConfig — group-level if available,
     *         permissive default otherwise
     */
    static DynamicAgentConfig resolveDynamicAgentConfig(IConversationMemory memory) {
        // Check if GroupConversationService injected a DynamicAgentConfig via context
        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            var contextData = currentStep.getLatestData("context:dynamicAgentConfig");
            if (contextData != null) {
                Object value = contextData.getResult();
                if (value instanceof Context ctx && ctx.getValue() instanceof DynamicAgentConfig groupConfig) {
                    LOGGER.debugf("[DYNAMIC] Using group-level DynamicAgentConfig for agent='%s'", sanitize(memory.getAgentId()));
                    return groupConfig;
                }
            }
        }
        // Fallback: standalone agent — use permissive defaults
        return createDefaultDynamicConfig();
    }

    /**
     * Creates a default DynamicAgentConfig for agents without explicit group
     * config. Used when individual agents have dynamic agent tools in their
     * whitelist.
     */
    static DynamicAgentConfig createDefaultDynamicConfig() {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowCreation(true);
        config.setAllowRecruitment(true);
        config.setAllowDelegation(true);
        return config;
    }

    /**
     * Finding F17: the agent IDs already created in this conversation, so
     * {@code maxCreatedAgentsPerDiscussion} bounds every turn of a conversation
     * rather than a single turn. The list used to start EMPTY on every
     * {@code buildToolList} call — i.e. once per LLM task execution — so a 5-member
     * × 3-phase discussion with the default cap of 5 could deploy up to 75 agents.
     * <p>
     * Two sources are consulted, in order:
     * <ol>
     * <li>a {@code dynamicCreatedAgentIds} context variable — an injection point
     * for an orchestrator that owns the true discussion-wide total. <b>Nothing in
     * {@code src/main} writes it today</b>: {@code GroupConversationService}
     * propagates member → group ({@code propagateDynamicAgentTracking}) but does
     * not inject the running total back into the per-turn member context alongside
     * {@code groupId}/{@code groupDepth}. Until it does, the cap bounds each MEMBER
     * conversation across the whole discussion, not the discussion total across
     * members;</li>
     * <li>every {@code dynamic:created_agent_ids} entry already written to this
     * conversation's memory by an earlier turn — the source that actually carries
     * the count today.</li>
     * </ol>
     * Returns a de-duplicated list, never null.
     */
    static List<String> seedCreatedAgentIds(IConversationMemory memory) {
        Set<String> seeded = new LinkedHashSet<>();

        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            IData<Object> contextData = currentStep.getLatestData("context:dynamicCreatedAgentIds");
            if (contextData != null && contextData.getResult() instanceof Context ctx) {
                collectAgentIds(ctx.getValue(), seeded);
            }
        }

        var allSteps = memory.getAllSteps();
        if (allSteps != null) {
            List<IData<Object>> priorEntries = allSteps.getAllLatestData(KEY_DYNAMIC_CREATED_AGENT_IDS);
            if (priorEntries != null) {
                for (IData<Object> entry : priorEntries) {
                    if (entry != null) {
                        collectAgentIds(entry.getResult(), seeded);
                    }
                }
            }
        }

        return new ArrayList<>(seeded);
    }

    /**
     * Add every String element of {@code value} (if it is a collection) to sink.
     */
    private static void collectAgentIds(Object value, Set<String> sink) {
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element instanceof String agentId && !agentId.isBlank()) {
                    sink.add(agentId);
                }
            }
        } else if (value instanceof String single && !single.isBlank()) {
            sink.add(single);
        }
    }

    /**
     * Finding F18: how many delegation hops led to the current conversation.
     * {@code ConverseWithAgentTool} propagates this as a {@code delegationDepth}
     * context variable on the callee's start context AND on every message it sends,
     * mirroring the {@code groupDepth} mechanism. 0 when a human started the
     * conversation.
     * <p>
     * The current step is consulted first — that is where the per-turn context
     * lands. Earlier steps are the fallback: the start context materializes on step
     * 0 only, and a turn issued without the delegation context (a resume, or any
     * other caller saying into the delegated conversation) must not silently reset
     * an already-delegated conversation back to depth 0. Without that fallback the
     * guard is inert on exactly the turns that matter.
     */
    static int resolveDelegationDepth(IConversationMemory memory) {
        String contextKey = "context:" + ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH;

        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            Integer depth = parseDelegationDepth(currentStep.getLatestData(contextKey));
            if (depth != null) {
                return depth;
            }
        }

        var allSteps = memory.getAllSteps();
        if (allSteps != null) {
            List<IData<Object>> priorEntries = allSteps.getAllLatestData(contextKey);
            if (priorEntries != null) {
                int deepest = 0;
                for (IData<Object> entry : priorEntries) {
                    Integer depth = parseDelegationDepth(entry);
                    if (depth != null) {
                        deepest = Math.max(deepest, depth);
                    }
                }
                return deepest;
            }
        }

        return 0;
    }

    /**
     * The hop count carried by a {@code context:delegationDepth} entry, or
     * {@code null} when the entry is absent, not a {@link Context}, or carries a
     * non-numeric value.
     */
    private static Integer parseDelegationDepth(IData<Object> contextData) {
        if (contextData == null || !(contextData.getResult() instanceof Context ctx)) {
            return null;
        }
        Object value = ctx.getValue();
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException e) {
                LOGGER.debugf("Ignoring non-numeric delegationDepth context value '%s'", sanitize(text));
            }
        }
        return null;
    }
}
