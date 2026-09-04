/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

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
 * <b>V7 — RESOLVED.</b> These tools used to be added only when a
 * {@code builtInToolsWhitelist} was configured, so an agent with
 * {@code enableBuiltInTools=true}, no whitelist and {@code
 * dynamicAgents.enabled=true} got none of them — the no-whitelist branch of
 * {@code collectAllBuiltInTools} never called this provider, while
 * {@code docs/langchain.md} states twice that omitting the whitelist enables
 * all built-in tools. {@code collectAllBuiltInTools} now calls this provider
 * unconditionally, and {@link #addDynamicAgentTools} honours an omitted
 * whitelist <em>only under a governing group policy</em> — see the reasoning
 * there for why a standalone conversation still requires the explicit entry.
 */
class DynamicAgentToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(DynamicAgentToolsProvider.class);

    /**
     * Converts a stored context map back into a typed {@link DynamicAgentConfig}.
     * Built from the shared {@link SerializationCustomizer} recipe rather than as a
     * bare {@link ObjectMapper}, and a static instance is thread-safe for
     * {@code convertValue}.
     * <p>
     * The recipe matters for one setting: {@code FAIL_ON_UNKNOWN_PROPERTIES=false}.
     * This map is a first-party config POJO that the engine wrote — but not
     * necessarily <em>this</em> engine, and not necessarily this version of it. A
     * group conversation's context map outlives the release that created it, so
     * during a rolling upgrade, a rollback, or after any field is dropped from
     * {@code DynamicAgentConfig} (which this project treats as a safe change — see
     * {@code SerializationCustomizer}, where the same flag is pinned false for
     * exactly this reason), a strict mapper would throw, the catch below would fire
     * and every in-flight discussion would silently lose agent creation,
     * recruitment and delegation for the turn. Failing closed is right for a policy
     * we genuinely cannot read; a retired key is not that.
     * <p>
     * The recipe's other half is deliberately undone. It sets a default property
     * inclusion of {@code NON_NULL}, and Jackson turns a bare {@code Include} into
     * both a value <em>and</em> a content inclusion — content inclusion applies to
     * map entries, and {@code convertValue} serializes the source map before it
     * reads the POJO back. A stored {@code "allowDelegation": null} would therefore
     * be dropped rather than applied, leaving the primitive at its POJO default of
     * {@code true}, where feeding the null through yields {@code false}. On the one
     * path whose whole invariant is "never let a conversion failure widen the
     * policy", silently widening a stored {@code null} is that same mistake wearing
     * a different hat — so inclusion goes back to {@code ALWAYS} and nothing is
     * filtered on the way in.
     */
    private static final ObjectMapper MAPPER = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false)
            .setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);

    /** Well-known data keys for dynamic agent lifecycle tracking. */
    static final String KEY_DYNAMIC_CREATED_AGENT_IDS = MemoryKeys.DYNAMIC_CREATED_AGENT_IDS;
    static final String KEY_DYNAMIC_RETAINED_AGENT_IDS = MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS;
    /**
     * Agents torn down during this conversation. Subtracted by
     * {@link #seedCreatedAgentIds} so a teardown frees a
     * {@code maxCreatedAgentsPerDiscussion} slot, and read by
     * {@code GroupLifecycleOps#propagateDynamicAgentTracking} so the group stops
     * tracking them too.
     */
    static final String KEY_DYNAMIC_TORN_DOWN_AGENT_IDS = MemoryKeys.DYNAMIC_TORN_DOWN_AGENT_IDS;

    private final AgentSetupService agentSetupService;
    private final CapabilityRegistryService capabilityRegistryService;
    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final IDeploymentStore deploymentStore;
    private final LiveDiscussionRegistry liveDiscussionRegistry;
    private final IAgentGroupStore agentGroupStore;

    DynamicAgentToolsProvider(AgentSetupService agentSetupService, CapabilityRegistryService capabilityRegistryService,
            IConversationService conversationService, IAgentFactory agentFactory, IAgentStore agentStore,
            IDeploymentStore deploymentStore, LiveDiscussionRegistry liveDiscussionRegistry,
            IAgentGroupStore agentGroupStore) {
        this.agentSetupService = agentSetupService;
        this.capabilityRegistryService = capabilityRegistryService;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.deploymentStore = deploymentStore;
        this.liveDiscussionRegistry = liveDiscussionRegistry;
        this.agentGroupStore = agentGroupStore;
    }

    /**
     * The agent ids on a running discussion's CONFIGURED roster, for
     * {@link RecruitAgentTool}'s duplicate check.
     * <p>
     * Read from the group config, the same way {@code ArtifactToolsProvider}
     * resolves its artifact policy — the {@link GroupConversation} itself does not
     * carry the roster.
     * <p>
     * {@link Optional#empty()} means <b>unavailable</b>, which is not the same as a
     * group that genuinely has no members, and the caller must not conflate the
     * two: the duplicate check is the entire reason the tool receives this set, so
     * handing it an empty set on a store failure would silently restore exactly the
     * defect it exists to prevent. An unavailable roster withholds
     * {@code recruit_agent} for that turn instead — gate by absence, the same
     * fail-closed discipline the artifact and group-task providers use.
     */
    private Optional<Set<String>> configuredMemberIds(GroupConversation gc) {
        if (agentGroupStore == null || gc == null || gc.getGroupId() == null) {
            return Optional.empty();
        }
        try {
            var resourceId = agentGroupStore.getCurrentResourceId(gc.getGroupId());
            if (resourceId == null) {
                return Optional.empty();
            }
            var groupConfiguration = agentGroupStore.read(gc.getGroupId(), resourceId.getVersion());
            if (groupConfiguration == null) {
                return Optional.empty();
            }
            if (groupConfiguration.getMembers() == null) {
                // Read successfully, and it has no roster. Distinct from unavailable.
                return Optional.of(Set.of());
            }
            return Optional.of(groupConfiguration.getMembers().stream()
                    .map(GroupMember::agentId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()));
        } catch (Exception e) {
            LOGGER.warnf("[DYNAMIC] Could not read the configured roster of group '%s' — withholding recruit_agent for this turn: %s",
                    sanitize(gc.getGroupId()), e.getMessage());
            return Optional.empty();
        }
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
        // V7. An omitted whitelist means "all built-in tools" everywhere else —
        // docs/langchain.md states it twice ("(all if not specified)", "Omitting
        // builtInToolsWhitelist enables all available built-in tools") and
        // BuiltinToolsProvider implements exactly that for the nine plain beans.
        // This method alone returned early, so an agent with enableBuiltInTools=true,
        // no whitelist and dynamicAgents.enabled=true silently got none of them.
        //
        // The omitted case is honoured ONLY when a group policy governs this turn.
        // dynamicAgents is a field on AgentGroupConfiguration, so a standalone
        // conversation has no configuration surface on which an operator could have
        // said "no" to sub-agent creation — resolveDynamicAgentConfig invents a
        // fully-permissive default for it. Handing unconfigurable, production-
        // deploying capabilities to an agent because it omitted a list would be a
        // worse defect than the asymmetry it fixes. Under a group policy the
        // operator HAS an explicit surface (enabled / allowCreation /
        // allowRecruitment / allowDelegation), which is what makes "all" safe to
        // mean all.
        boolean whitelistOmitted = whitelist == null || whitelist.isEmpty();
        if (whitelistOmitted && !hasGroupPolicy(memory)) {
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
        // Seeded like sharedCreatedIds so a teardown recorded on an earlier turn is
        // still known on this one — otherwise a torn-down id would be subtracted from
        // the seed once and then re-appear the turn after.
        Set<String> sharedTornDownIds = ConcurrentHashMap.newKeySet();
        sharedTornDownIds.addAll(collectFromAllSteps(memory, KEY_DYNAMIC_TORN_DOWN_AGENT_IDS));
        String parentAgentId = memory.getAgentId();
        String userId = memory.getUserId();
        DynamicAgentConfig dynamicConfig = resolveDynamicAgentConfig(memory);

        boolean anyDynamicToolAdded = false;
        if (allows(whitelist, whitelistOmitted, "create_sub_agent") && agentSetupService != null && conversationService != null) {
            tools.add(new CreateSubAgentTool(agentSetupService,
                    conversationService, parentAgentId, userId, dynamicConfig,
                    sharedCreatedIds, sharedRetainedIds));
            LOGGER.debugf("[DYNAMIC] CreateSubAgentTool enabled for agent='%s' (%d already created)",
                    sanitize(parentAgentId), sharedCreatedIds.size());
            anyDynamicToolAdded = true;
        }
        if (allows(whitelist, whitelistOmitted, "converse_with_agent") && conversationService != null) {
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
        if (allows(whitelist, whitelistOmitted, "find_agents_by_capability") && capabilityRegistryService != null) {
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
        // getForMember, not get: groupConversationId is a caller-supplied context
        // variable, so existence is not authorization. Without the membership check
        // any principal who can start a conversation could name another discussion's
        // id and recruit into it.
        // No null-check: this method already dereferences memory unconditionally above
        // (getAgentId/getUserId/seedCreatedAgentIds), so a null would have thrown long
        // before here. A guard only on this line implied a nullability the rest of the
        // method does not honour, which reads as though one path were safe and the
        // others overlooked.
        String callerConversationId = memory.getConversationId();
        if (allows(whitelist, whitelistOmitted, "recruit_agent") && dynamicConfig.isEnabled() && dynamicConfig.isAllowRecruitment()
                && groupConversationId != null && liveDiscussionRegistry != null) {
            var liveDiscussion = liveDiscussionRegistry.getForMember(groupConversationId, callerConversationId);
            // The roster must be READ, not merely attempted: without it the tool
            // cannot tell a configured member from an outsider, which is the whole
            // point of the duplicate check.
            var roster = liveDiscussion.flatMap(this::configuredMemberIds);
            if (liveDiscussion.isPresent() && roster.isPresent()) {
                tools.add(new RecruitAgentTool(liveDiscussionRegistry, groupConversationId, parentAgentId,
                        dynamicConfig, deploymentStore, roster.get()));
                LOGGER.debugf("[DYNAMIC] RecruitAgentTool enabled for agent='%s'", sanitize(parentAgentId));
                anyDynamicToolAdded = true;
            } else if (liveDiscussion.isPresent()) {
                LOGGER.warnf("[DYNAMIC] RecruitAgentTool suppressed for agent='%s': the group's configured roster could not be read",
                        sanitize(parentAgentId));
            }
        }
        // dynamicConfig.isEnabled(): TeardownAgentTool takes no DynamicAgentConfig and
        // performs no policy check of its own, so without this gate a group whose
        // policy is disabled — or unreadable, which resolves fail-closed — could still
        // undeploy and PERMANENTLY DELETE a tracked agent. Every other dynamic tool is
        // either gated here or refuses internally; this one was neither.
        if (allows(whitelist, whitelistOmitted, "teardown_agent") && dynamicConfig.isEnabled()
                && agentFactory != null && agentStore != null) {
            tools.add(new TeardownAgentTool(agentFactory, agentStore, deploymentStore, sharedCreatedIds, sharedRetainedIds,
                    sharedTornDownIds));
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
            currentStep.storeData(new Data<>(KEY_DYNAMIC_TORN_DOWN_AGENT_IDS, sharedTornDownIds));
        }
    }

    /**
     * Every String id stored under {@code key} across this conversation's steps,
     * de-duplicated. The cumulative read the tracking keys need — a per-turn
     * collection only ever holds what that turn produced.
     */
    private static Set<String> collectFromAllSteps(IConversationMemory memory, String key) {
        Set<String> collected = new LinkedHashSet<>();
        var allSteps = memory.getAllSteps();
        if (allSteps == null) {
            return collected;
        }
        List<IData<Object>> entries = allSteps.getAllLatestData(key);
        if (entries == null) {
            return collected;
        }
        for (IData<Object> entry : entries) {
            if (entry != null) {
                collectAgentIds(entry.getResult(), collected);
            }
        }
        return collected;
    }

    /** Context key {@code MemberTurnExecutor} injects the group's policy under. */
    static final String CONTEXT_DYNAMIC_AGENT_CONFIG = "context:dynamicAgentConfig";

    /**
     * Whether {@code toolName} is enabled for this turn.
     * <p>
     * An omitted whitelist means every tool, matching {@code BuiltinToolsProvider}
     * and the documented contract; a present whitelist is an exact allow-list. The
     * caller decides whether the omitted case is admissible at all (see
     * {@link #addDynamicAgentTools}) — this only answers the membership question.
     */
    private static boolean allows(List<String> whitelist, boolean whitelistOmitted, String toolName) {
        return whitelistOmitted || whitelist.contains(toolName);
    }

    /**
     * Whether a group's {@link DynamicAgentConfig} governs this turn — i.e. the
     * conversation carries the context key {@code MemberTurnExecutor} injects on
     * every member turn.
     * <p>
     * Presence, not readability: an unreadable policy still means "a group is in
     * charge here", and {@link #resolveDynamicAgentConfig} independently resolves
     * that case to a fully-disabled config. Treating unreadable as "no group" would
     * hand the turn back to the permissive standalone path, which is precisely the
     * inversion both guards exist to prevent.
     */
    static boolean hasGroupPolicy(IConversationMemory memory) {
        var currentStep = memory.getCurrentStep();
        if (currentStep == null) {
            return false;
        }
        var contextData = currentStep.getLatestData(CONTEXT_DYNAMIC_AGENT_CONFIG);
        return contextData != null && contextData.getResult() instanceof Context ctx && ctx.getValue() != null;
    }

    /**
     * Resolves the DynamicAgentConfig for the current conversation.
     * <p>
     * A member turn inside a group carries the group's {@link DynamicAgentConfig}
     * as the {@code dynamicAgentConfig} context variable — injected by
     * {@code MemberTurnExecutor} on EVERY turn, including an explicitly disabled
     * one when the group configured none, precisely so a group can never inherit
     * the permissive standalone default. Only a conversation with no such context
     * at all is a standalone agent.
     * <p>
     * <b>Three-state, and the middle state is the point.</b> The key is absent →
     * standalone → permissive default. The key is present and readable → the
     * group's policy. The key is present but <em>unreadable</em> → the group said
     * something about this and we could not parse it, which must fail CLOSED.
     * <p>
     * That middle state is reachable, and was a live guardrail bypass. A
     * {@link Context} whose value round-trips through the conversation store comes
     * back as a raw {@code LinkedHashMap}, not a typed {@code DynamicAgentConfig} —
     * {@code ConversationMemoryStore} rebuilds it as
     * {@code new Context(type, map.get("value"))}. Any turn that runs against a
     * RELOADED step therefore missed the old {@code instanceof} and fell through to
     * the fully-permissive default: creation, recruitment and delegation all on,
     * for a group that may have disabled every one of them. The reachable trigger
     * is a member's gated tool call inside a group —
     * {@code MemberTurnExecutor#tryResolveMemberToolPause} auto-rejects it and
     * resumes the member conversation, and {@code Conversation#resume} re-enters
     * the same LlmTask at the same index against memory freshly loaded from the
     * store. The orchestrator is still blocked in that call, so the discussion is
     * still live in {@code LiveDiscussionRegistry} and the group-gated tools are
     * available too.
     *
     * @param memory
     *            the conversation memory to check for group context
     * @return the group's policy when one is present and readable, a fully-disabled
     *         policy when one is present but unreadable, and the permissive
     *         standalone default only when none is present at all
     */
    static DynamicAgentConfig resolveDynamicAgentConfig(IConversationMemory memory) {
        var currentStep = memory.getCurrentStep();
        if (currentStep == null) {
            return createDefaultDynamicConfig();
        }
        var contextData = currentStep.getLatestData(CONTEXT_DYNAMIC_AGENT_CONFIG);
        if (contextData == null || !(contextData.getResult() instanceof Context ctx) || ctx.getValue() == null) {
            // No group context — a standalone agent whose operator whitelisted these
            // tools deliberately.
            return createDefaultDynamicConfig();
        }

        Object value = ctx.getValue();
        if (value instanceof DynamicAgentConfig groupConfig) {
            LOGGER.debugf("[DYNAMIC] Using group-level DynamicAgentConfig for agent='%s'", sanitize(memory.getAgentId()));
            return groupConfig;
        }
        if (value instanceof Map<?, ?> serialized) {
            // The store round-trip shape. Convert rather than give up: giving up here
            // would be indistinguishable from "no group context" and would hand back
            // the permissive default.
            try {
                DynamicAgentConfig converted = MAPPER.convertValue(serialized, DynamicAgentConfig.class);
                LOGGER.debugf("[DYNAMIC] Recovered group-level DynamicAgentConfig from a stored context map for agent='%s'",
                        sanitize(memory.getAgentId()));
                return converted;
            } catch (IllegalArgumentException e) {
                // A map that is not this config — a type mismatch, a value the model
                // cannot hold. (A merely unknown key is NOT this case: the mapper
                // ignores it, so a config written by a different version still
                // resolves.) Fall through to fail closed — never let a conversion
                // failure widen the policy.
                LOGGER.warnf("[DYNAMIC] A stored group DynamicAgentConfig for agent='%s' could not be converted (%s) — "
                        + "disabling dynamic agent capabilities for this turn", sanitize(memory.getAgentId()), e.getMessage());
                return disabledDynamicConfig();
            }
        }

        // Present but unreadable. Fail closed — see the Javadoc.
        LOGGER.warnf("[DYNAMIC] A group DynamicAgentConfig was present for agent='%s' but could not be read (type=%s) — "
                + "disabling dynamic agent capabilities for this turn rather than falling back to the permissive default",
                sanitize(memory.getAgentId()), value.getClass().getName());
        return disabledDynamicConfig();
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
     * The fail-closed policy: every capability off. Used when a group policy is
     * present but unreadable — "the operator said something we cannot parse" must
     * never resolve to "the operator said yes to everything".
     */
    static DynamicAgentConfig disabledDynamicConfig() {
        var config = new DynamicAgentConfig();
        config.setEnabled(false);
        config.setAllowCreation(false);
        config.setAllowRecruitment(false);
        config.setAllowDelegation(false);
        return config;
    }

    /**
     * Context key carrying the discussion-wide created-agent total into a member
     * turn. Written by {@code MemberTurnExecutor}, read by
     * {@link #seedCreatedAgentIds}.
     */
    static final String CONTEXT_DYNAMIC_CREATED_AGENT_IDS = "context:dynamicCreatedAgentIds";

    /**
     * Finding F17: the agent IDs already created in this conversation, so
     * {@code maxCreatedAgentsPerDiscussion} bounds every turn of a conversation
     * rather than a single turn. The list used to start EMPTY on every
     * {@code buildToolList} call — i.e. once per LLM task execution — so a 5-member
     * × 3-phase discussion with the default cap of 5 could deploy up to 75 agents.
     * <p>
     * Two sources are consulted, in order:
     * <ol>
     * <li>the {@code dynamicCreatedAgentIds} context variable — the discussion-wide
     * total, injected per member turn by {@code MemberTurnExecutor} from
     * {@code GroupConversation#getCreatedAgentIds()}, which
     * {@code propagateDynamicAgentTracking} keeps current after every member turn.
     * This is what makes {@code maxCreatedAgentsPerDiscussion} mean what its name
     * (and {@code docs/group-conversations.md}) says. Until it was injected, the
     * cap bounded each MEMBER conversation independently, so a 5-member group with
     * the default cap of 5 could deploy 25 agents to production per
     * discussion;</li>
     * <li>every {@code dynamic:created_agent_ids} entry already written to this
     * conversation's memory by an earlier turn — the per-conversation half, which
     * still matters for a standalone agent with no group around it.</li>
     * </ol>
     * Returns a de-duplicated list, never null.
     */
    static List<String> seedCreatedAgentIds(IConversationMemory memory) {
        Set<String> seeded = new LinkedHashSet<>();

        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            IData<Object> contextData = currentStep.getLatestData(CONTEXT_DYNAMIC_CREATED_AGENT_IDS);
            if (contextData != null && contextData.getResult() instanceof Context ctx) {
                collectAgentIds(ctx.getValue(), seeded);
            }
        }

        seeded.addAll(collectFromAllSteps(memory, KEY_DYNAMIC_CREATED_AGENT_IDS));

        // An agent that was torn down no longer exists and must not keep occupying a
        // maxCreatedAgentsPerDiscussion slot. Subtracted last so it wins over both
        // sources: the group-wide context total and this conversation's own history
        // can each still name an id whose teardown they have not observed yet.
        seeded.removeAll(collectFromAllSteps(memory, KEY_DYNAMIC_TORN_DOWN_AGENT_IDS));

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
