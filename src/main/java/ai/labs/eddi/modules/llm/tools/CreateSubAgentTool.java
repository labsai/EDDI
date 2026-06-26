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
        this.createdAgentIds = createdAgentIds != null ? createdAgentIds : new java.util.concurrent.CopyOnWriteArrayList<>();
        this.retainedAgentIds = retainedAgentIds != null ? retainedAgentIds : java.util.concurrent.ConcurrentHashMap.newKeySet();
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

            // --- Guardrail: allowed providers ---
            if (provider != null && !provider.isBlank()
                    && config.getAllowedProviders() != null
                    && !config.getAllowedProviders().isEmpty()) {
                boolean providerAllowed = config.getAllowedProviders().stream()
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(p -> p.equalsIgnoreCase(provider));
                if (!providerAllowed) {
                    return "⚠️ Provider '%s' is not allowed. Allowed: %s"
                            .formatted(provider, config.getAllowedProviders());
                }
            }

            // --- Guardrail: allowed models ---
            if (model != null && !model.isBlank()
                    && config.getAllowedModels() != null
                    && !config.getAllowedModels().isEmpty()) {
                if (provider != null && !provider.isBlank()) {
                    // Provider specified — check against that provider's model list
                    // (case-insensitive key match)
                    List<String> allowedModels = config.getAllowedModels().entrySet().stream()
                            .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(provider))
                            .map(Map.Entry::getValue)
                            .filter(java.util.Objects::nonNull)
                            .findFirst().orElse(null);
                    if (allowedModels != null && !allowedModels.isEmpty()
                            && allowedModels.stream().noneMatch(m -> m.equalsIgnoreCase(model))) {
                        return "⚠️ Model '%s' is not allowed for provider '%s'. Allowed: %s"
                                .formatted(model, provider, allowedModels);
                    }
                } else {
                    // No provider specified — model must appear in at least one provider's
                    // allow-list
                    boolean modelFoundInAnyProvider = config.getAllowedModels().values().stream()
                            .filter(java.util.Objects::nonNull)
                            .flatMap(List::stream)
                            .filter(java.util.Objects::nonNull)
                            .anyMatch(m -> m.equalsIgnoreCase(model));
                    if (!modelFoundInAnyProvider) {
                        return "⚠️ Model '%s' is not in any provider's allowed models list."
                                .formatted(model);
                    }
                }
            }

            // --- Build and execute setup ---
            String prefixedName = parentAgentId + "/" + name.trim();
            SetupAgentRequest request = new SetupAgentRequest(
                    prefixedName,
                    systemPrompt,
                    provider,
                    model,
                    null, // apiKey — inherited from vault
                    null, // baseUrl
                    null, // introMessage (handled separately below)
                    null, // enableBuiltInTools
                    null, // builtInToolsWhitelist
                    null, // enableQuickReplies
                    null, // enableSentimentAnalysis
                    null, // mcpServerUrls
                    true, // deploy
                    null // environment
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

                    CompletableFuture<String> responseFuture = new CompletableFuture<>();
                    conversationService.say(DEFAULT_ENV, agentId, conversationId,
                            false, true, null, inputData, false, snapshot -> {
                                String text = extractResponse(snapshot);
                                responseFuture.complete(text);
                            });

                    response = responseFuture.get(60, TimeUnit.SECONDS);
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
}
