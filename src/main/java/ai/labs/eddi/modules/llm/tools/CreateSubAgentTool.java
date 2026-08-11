/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import ai.labs.eddi.engine.setup.SetupResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;

/**
 * LLM tool for dynamically creating sub-agents during group conversations.
 * Constructed per-invocation by {@code AgentOrchestrator} with the parent
 * agent's context and the group's {@link DynamicAgentConfig} guardrails.
 *
 * <p>
 * The LLM can call this tool to spin up a new specialist agent on the fly,
 * optionally sending it an initial message to bootstrap its context.
 *
 * @since 6.0.0
 */
@Vetoed // Instantiated per-invocation by AgentOrchestrator — must NOT be a CDI bean
public class CreateSubAgentTool {

    private static final Logger LOGGER = Logger.getLogger(CreateSubAgentTool.class);
    private static final Environment DEFAULT_ENV = Environment.production;

    private final AgentSetupService agentSetupService;
    private final IConversationService conversationService;
    private final String parentAgentId;
    private final String userId;
    private final DynamicAgentConfig config;
    private final List<String> createdAgentIds;
    private final Set<String> retainedAgentIds;

    public CreateSubAgentTool(AgentSetupService agentSetupService,
            IConversationService conversationService,
            String parentAgentId,
            String userId,
            DynamicAgentConfig config,
            List<String> createdAgentIds,
            Set<String> retainedAgentIds) {
        this.agentSetupService = agentSetupService;
        this.conversationService = conversationService;
        this.parentAgentId = parentAgentId;
        this.userId = userId;
        this.config = config != null ? config : new DynamicAgentConfig();
        this.createdAgentIds = createdAgentIds != null ? createdAgentIds : new CopyOnWriteArrayList<>();
        this.retainedAgentIds = retainedAgentIds != null ? retainedAgentIds : ConcurrentHashMap.newKeySet();
    }

    /**
     * The models permitted for {@code provider}, or {@code null} when the policy
     * has no entry for it. Null elements are filtered — a hand-written config can
     * carry them, and they must neither crash the check nor widen the list.
     */
    private List<String> allowedModelsFor(String provider) {
        if (config.getAllowedModels() == null || provider == null) {
            return null;
        }
        return config.getAllowedModels().entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(provider))
                .map(Map.Entry::getValue)
                .filter(Objects::nonNull)
                .findFirst()
                .map(models -> models.stream().filter(Objects::nonNull).toList())
                .orElse(null);
    }

    @Tool("Create a new sub-agent dynamically. The agent is set up, deployed, and optionally sent an initial message. "
            + "Use this when the current discussion requires a specialist that doesn't exist yet. "
            + "The created agent's name will be auto-prefixed with the parent agent's ID.")
    public String createSubAgent(
                                 @P("Name for the new agent (will be prefixed with parent agent ID)") String name,
                                 @P("System prompt defining the agent's behavior and expertise") String systemPrompt,
                                 @P("LLM provider (e.g. 'openai', 'anthropic'). Optional — inherits parent if omitted") String provider,
                                 @P("Model name (e.g. 'gpt-4o'). Optional — inherits parent if omitted") String model,
                                 @P("Optional initial message to send to the agent after creation") String initialMessage,
                                 @P("If true, the agent will be retained after the discussion ends. Default: false") Boolean retain) {

        try {
            // --- Guardrail: creation allowed ---
            if (!config.isEnabled() || !config.isAllowCreation()) {
                return "⚠️ Dynamic agent creation is not enabled for this group.";
            }

            // --- Guardrail: max created agents ---
            if (createdAgentIds.size() >= config.getMaxCreatedAgentsPerDiscussion()) {
                return "⚠️ Maximum created agents (%d) reached for this discussion."
                        .formatted(config.getMaxCreatedAgentsPerDiscussion());
            }

            // --- Guardrail: required parameters ---
            if (name == null || name.isBlank()) {
                return "⚠️ Agent name is required.";
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                return "⚠️ System prompt is required.";
            }

            // --- Inherit the parent's LLM identity where the caller omitted it ---
            // The @P docs above promised this and nothing implemented it: apiKey was
            // passed as null with a comment claiming vault inheritance, so
            // AgentSetupService's required-API-key check rejected every provider that
            // needs one — including the default (anthropic) that an omitted provider
            // resolves to. Sub-agent creation only ever worked for ollama/jlama/
            // bedrock/oracle-genai.
            //
            // Only a vault REFERENCE is inherited, never a plaintext key — see
            // AgentSetupService#resolveParentLlmProfile.
            var parentProfile = agentSetupService.resolveParentLlmProfile(parentAgentId);
            boolean inherit = config.isInheritParentModel() && parentProfile != null;

            // TRIMMED before anything compares it. AgentSetupService.resolveParams
            // trims, so " openai " is accepted downstream as openai while an untrimmed
            // guardrail here would reject it against allowedProviders=["openai"] and
            // fail to match the parent for credential inheritance — the policy and the
            // deployment disagreeing about the same string.
            String requestedProvider = (provider == null || provider.isBlank()) && inherit
                    ? parentProfile.provider()
                    : provider;
            requestedProvider = requestedProvider != null ? requestedProvider.trim() : null;

            // The provider AgentSetupService will ACTUALLY use, defaults applied.
            // Guarding the merely-requested value left the bypass open: with
            // inheritParentModel=false and no provider argument nothing was inherited,
            // the value stayed null, the allow-list check below skipped it, and
            // resolveParams then substituted the default.
            final String resolvedProvider = requestedProvider == null || requestedProvider.isBlank()
                    ? AgentSetupService.DEFAULT_PROVIDER
                    : requestedProvider;

            // Model inheritance is gated on the provider STILL being the parent's.
            // The two are independent inputs, so an anthropic parent creating a
            // sub-agent with provider="ollama" and no model would otherwise pair Ollama
            // with the parent's Claude model — and because Ollama needs no key, nothing
            // downstream rejects it and the failure surfaces only at model load.
            String requestedModel = model;
            if ((requestedModel == null || requestedModel.isBlank()) && inherit
                    && resolvedProvider.equalsIgnoreCase(parentProfile.provider())) {
                requestedModel = parentProfile.model();
            }
            requestedModel = requestedModel != null ? requestedModel.trim() : null;

            // Same reasoning as the provider: the model allow-list was skippable by
            // omission, because a null model skipped the guard and AgentSetupService
            // then substituted DEFAULT_MODEL — deploying a model the policy never saw.
            final String resolvedModel = requestedModel == null || requestedModel.isBlank()
                    ? AgentSetupService.DEFAULT_MODEL
                    : requestedModel;

            // A vault reference names ONE provider's secret. Handing the parent's
            // anthropic reference to an openai sub-agent both breaks it at model load
            // and writes a reference to the parent's secret into an unrelated config.
            String inheritedApiKey = parentProfile != null
                    && resolvedProvider.equalsIgnoreCase(parentProfile.provider())
                            ? parentProfile.apiKeyReference()
                            : null;

            // --- Guardrail: allowed providers ---
            // Checked against the EFFECTIVE provider, not the requested one. Guarding
            // the requested value left the allow-list skippable: with
            // inheritParentModel=false and no provider argument nothing was inherited,
            // the value stayed null, this branch was skipped, and AgentSetupService
            // then substituted its default — so a group restricting allowedProviders
            // was bypassed by simply omitting the parameter.
            if (config.getAllowedProviders() != null
                    && !config.getAllowedProviders().isEmpty()) {
                boolean providerAllowed = config.getAllowedProviders().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(p -> p.equalsIgnoreCase(resolvedProvider));
                if (!providerAllowed) {
                    return "⚠️ Provider '%s' is not allowed. Allowed: %s"
                            .formatted(resolvedProvider, config.getAllowedProviders());
                }
            }

            // --- Guardrail: allowed models ---
            // allowedModels maps a provider to the models permitted FOR THAT PROVIDER,
            // so a model may only be judged against the provider it will be paired
            // with. The two branches differ in what an absent entry means, and
            // deliberately so.
            // resolvedModel always carries a value now (the effective default when the
            // caller named none), so there is no "no model requested" escape from this
            // guard any more.
            if (config.getAllowedModels() != null && !config.getAllowedModels().isEmpty()) {
                List<String> allowedForProvider = allowedModelsFor(resolvedProvider);

                if (requestedProvider != null && !requestedProvider.isBlank()) {
                    // A provider was named (or inherited from the parent). An absent or
                    // empty list means "no restriction for that provider" — a documented
                    // behaviour, and a defensible one: the operator made a deliberate
                    // per-provider statement and said nothing about this one.
                    if (allowedForProvider != null && !allowedForProvider.isEmpty()
                            && allowedForProvider.stream().noneMatch(m -> m.equalsIgnoreCase(resolvedModel))) {
                        return "⚠️ Model '%s' is not allowed for provider '%s'. Allowed: %s"
                                .formatted(resolvedModel, resolvedProvider, allowedForProvider);
                    }
                } else if (allowedForProvider == null || allowedForProvider.isEmpty()) {
                    // No provider named, so the model is about to be paired with the
                    // DEFAULT provider — one the caller never chose and the policy never
                    // mentions. The previous rule accepted any model appearing in ANY
                    // provider's list, which meant a config restricting openai to
                    // "gpt-4o-mini" would happily build an ANTHROPIC agent running
                    // "gpt-4o-mini": a combination that fails at model load and that the
                    // operator plainly did not authorise. An unnamed provider must land
                    // on a provider the policy actually covers.
                    return ("⚠️ Model '%s' cannot be used without naming a provider: the default provider '%s' has no "
                            + "allowed models configured. Name one of: %s")
                            .formatted(resolvedModel, resolvedProvider, config.getAllowedModels().keySet());
                } else if (allowedForProvider.stream().noneMatch(m -> m.equalsIgnoreCase(resolvedModel))) {
                    return "⚠️ Model '%s' is not allowed for provider '%s'. Allowed: %s"
                            .formatted(resolvedModel, resolvedProvider, allowedForProvider);
                }
            }

            // --- Build and execute setup ---
            String prefixedName = parentAgentId + "/" + name.trim();
            SetupAgentRequest request = new SetupAgentRequest(
                    prefixedName,
                    systemPrompt,
                    resolvedProvider,
                    resolvedModel,
                    inheritedApiKey, // the parent's vault reference, or null if it has none
                    null, // baseUrl
                    null, // introMessage (handled separately below)
                    null, // enableBuiltInTools
                    null, // builtInToolsWhitelist
                    null, // enableQuickReplies
                    null, // enableSentimentAnalysis
                    null, // mcpServerUrls
                    true, // deploy
                    null, // environment
                    null // hitlConfig — dynamic sub-agents are not gated; see the
                         // dynamicAgents.allowCreation escalation flag on the group
                         // that provisioned this one
            );

            SetupResult result = agentSetupService.setupAgent(request);
            String agentId = result.agentId();
            createdAgentIds.add(agentId);
            if (Boolean.TRUE.equals(retain)) {
                retainedAgentIds.add(agentId);
            }

            LOGGER.infof("[SUB-AGENT] Created sub-agent: name='%s', agentId='%s', parent='%s'",
                    prefixedName, agentId, parentAgentId);

            // --- Optional: send initial message ---
            String conversationId = null;
            String response = null;
            if (initialMessage != null && !initialMessage.isBlank()) {
                try {
                    ConversationResult convResult = conversationService.startConversation(
                            DEFAULT_ENV, agentId, userId, Collections.emptyMap());
                    conversationId = convResult.conversationId();

                    InputData inputData = new InputData();
                    inputData.setInput(initialMessage);

                    CompletableFuture<SimpleConversationMemorySnapshot> responseFuture = new CompletableFuture<>();
                    final AtomicBoolean skipped = new AtomicBoolean();

                    // Finding H7: a busy-skip (onSkipped, e.g. IN_PROGRESS) must NOT be
                    // treated as a fresh response — the default onSkipped→onComplete would
                    // report the PREVIOUS turn's output as the "Initial response".
                    // Flag the skip and discriminate below (busy vs not-active).
                    conversationService.say(DEFAULT_ENV, agentId, conversationId,
                            false, true, null, inputData, false,
                            new IConversationService.ConversationResponseHandler() {
                                @Override
                                public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                                    responseFuture.complete(snapshot);
                                }

                                @Override
                                public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                                    skipped.set(true);
                                    responseFuture.complete(snapshot);
                                }
                            });

                    var snapshot = responseFuture.get(60, TimeUnit.SECONDS);
                    // Finding 25: a sub-agent that pauses for approval on its first
                    // message must report the pending approval, not "[no response]".
                    if (snapshot != null && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                        response = "PAUSED_FOR_APPROVAL: the sub-agent's conversation " + conversationId
                                + " requires human approval before it can continue. A reviewer must decide via "
                                + "POST /agents/" + conversationId + "/resume." + toolPauseSuffix(snapshot);
                    } else if (skipped.get()) {
                        // Finding H7: the initial message was dropped without being
                        // processed (busy or no longer active) — report accurately
                        // instead of returning the stale previous-turn output.
                        var state = snapshot != null ? snapshot.getConversationState() : null;
                        if (state == ConversationState.ENDED
                                || state == ConversationState.EXECUTION_INTERRUPTED) {
                            response = "[Sub-agent conversation " + conversationId + " is no longer active (state: "
                                    + state + "); initial message not delivered]";
                        } else {
                            response = "[Sub-agent conversation " + conversationId + " is busy processing another turn; "
                                    + "initial message not delivered — retry shortly]";
                        }
                    } else {
                        response = extractResponse(snapshot);
                    }
                } catch (IConversationService.ConversationAwaitingApprovalException e) {
                    response = "PAUSED_FOR_APPROVAL: the sub-agent's conversation " + conversationId
                            + " is awaiting human approval; a reviewer must decide via POST /agents/"
                            + conversationId + "/resume before it can continue.";
                } catch (Exception e) {
                    LOGGER.warnf("[SUB-AGENT] Initial message failed for agent '%s': %s",
                            agentId, e.getMessage());
                    response = "[Initial message failed: " + e.getMessage() + "]";
                }
            }

            // --- Build result string ---
            var sb = new StringBuilder();
            sb.append("✅ Sub-agent created successfully!\n");
            sb.append("• Agent ID: ").append(agentId).append("\n");
            sb.append("• Name: ").append(prefixedName).append("\n");
            if (result.provider() != null) {
                sb.append("• Provider: ").append(result.provider()).append("\n");
            }
            if (result.model() != null) {
                sb.append("• Model: ").append(result.model()).append("\n");
            }
            if (conversationId != null) {
                sb.append("• Conversation ID: ").append(conversationId).append("\n");
            }
            if (response != null) {
                sb.append("• Initial response: ").append(response).append("\n");
            }
            if (Boolean.TRUE.equals(retain)) {
                sb.append("• Lifecycle: retained (will not be auto-deleted)\n");
            }

            return sb.toString();

        } catch (AgentSetupException e) {
            LOGGER.errorf("[SUB-AGENT] Failed to create sub-agent: %s", e.getMessage());
            return "❌ Failed to create sub-agent: " + e.getMessage();
        } catch (Exception e) {
            LOGGER.errorf("[SUB-AGENT] Unexpected error creating sub-agent: %s", e.getMessage());
            return "❌ Unexpected error: " + e.getMessage();
        }
    }

    /**
     * Extracts the human-readable text from a conversation memory snapshot.
     * Delegates to shared utility.
     */
    private String extractResponse(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
        return ai.labs.eddi.engine.memory.ConversationOutputExtractor.extractResponse(snapshot);
    }

    /**
     * Task 13 (additive): for a TOOL_CALL pause, a suffix naming the pause type and
     * the gated tool NAMES (names ONLY — never arguments, raw or redacted). Empty
     * for a RULE pause so the existing message shape is preserved.
     */
    private String toolPauseSuffix(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || !"TOOL_CALL".equals(snapshot.getHitlPauseType())) {
            return "";
        }
        var suffix = new StringBuilder(" pauseType=TOOL_CALL.");
        var batch = snapshot.getHitlPendingToolCalls();
        if (batch != null && batch.getCalls() != null) {
            var toolNames = batch.getCalls().stream()
                    .map(ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall::getToolName)
                    .filter(Objects::nonNull)
                    .toList();
            if (!toolNames.isEmpty()) {
                suffix.append(" Gated tools: ").append(String.join(", ", toolNames)).append('.');
            }
        }
        return suffix.toString();
    }
}
