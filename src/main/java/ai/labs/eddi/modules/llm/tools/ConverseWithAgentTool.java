/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LLM tool for conversing with another deployed EDDI agent. Constructed
 * per-invocation by {@code AgentOrchestrator} with the conversation service and
 * user identity.
 *
 * <p>
 * Supports both single-turn (fire-and-forget) and multi-turn conversations.
 * When a {@code conversationId} is provided, the tool continues an existing
 * conversation; otherwise it starts a new one.
 *
 * @since 6.0.0
 */
@Vetoed // Instantiated per-invocation by AgentOrchestrator — must NOT be a CDI bean
public class ConverseWithAgentTool {

    private static final Logger LOGGER = Logger.getLogger(ConverseWithAgentTool.class);
    private static final Environment DEFAULT_ENV = Environment.production;

    private final IConversationService conversationService;
    private final String userId;

    public ConverseWithAgentTool(IConversationService conversationService, String userId) {
        this.conversationService = conversationService;
        this.userId = userId;
    }

    @Tool("Send a message to another deployed EDDI agent and receive its response. "
            + "Use this for inter-agent delegation or consultation. "
            + "Provide an existing conversationId for multi-turn conversations, "
            + "or omit it to start a new conversation.")
    public String converseWithAgent(
                                    @P("The ID of the target agent to converse with") String agentId,
                                    @P("The message to send to the agent") String message,
                                    @P("Optional conversation ID for continuing a multi-turn conversation") String conversationId) {

        try {
            // --- Validate parameters ---
            if (agentId == null || agentId.isBlank()) {
                return "⚠️ Agent ID is required.";
            }
            if (message == null || message.isBlank()) {
                return "⚠️ Message is required.";
            }

            // --- Start new conversation if no conversationId provided ---
            if (conversationId == null || conversationId.isBlank()) {
                try {
                    ConversationResult convResult = conversationService.startConversation(
                            DEFAULT_ENV, agentId, userId, Collections.emptyMap());
                    conversationId = convResult.conversationId();
                    LOGGER.debugf("[CONVERSE] Started new conversation '%s' with agent '%s'",
                            conversationId, agentId);
                } catch (Exception e) {
                    LOGGER.errorf("[CONVERSE] Failed to start conversation with agent '%s': %s",
                            agentId, e.getMessage());
                    return "❌ Failed to start conversation with agent '%s': %s"
                            .formatted(agentId, e.getMessage());
                }
            }

            // --- Send message and wait for response ---
            InputData inputData = new InputData();
            inputData.setInput(message);

            CompletableFuture<SimpleConversationMemorySnapshot> responseFuture = new CompletableFuture<>();
            final String convId = conversationId;

            conversationService.say(DEFAULT_ENV, agentId, convId,
                    false, true, null, inputData, false, responseFuture::complete);

            SimpleConversationMemorySnapshot snapshot = responseFuture.get(60, TimeUnit.SECONDS);

            // Finding 25: the delegated conversation paused for human approval on
            // this turn. Return a structured, actionable result so the delegating
            // LLM can inform its user, rather than reporting "[no response]" and
            // silently losing the eventual approved output.
            if (snapshot != null
                    && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                return pausedForApprovalMessage(convId);
            }

            String response = extractResponse(snapshot);
            if (response == null && snapshot != null
                    && snapshot.getConversationState() == ConversationState.ERROR) {
                response = "[Agent failed to produce output — conversation entered ERROR state]";
            }

            LOGGER.debugf("[CONVERSE] Agent '%s' responded in conversation '%s'", agentId, convId);

            return "✅ Agent response (conversationId: %s):\n%s".formatted(convId,
                    response != null && !response.isEmpty() ? response : "[no response]");

        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            // Finding 25: re-invoking against an already-paused delegated
            // conversation. Report the pending approval instead of hanging on the
            // 60s watchdog or surfacing a generic error.
            LOGGER.debugf("[CONVERSE] Conversation '%s' with agent '%s' is awaiting approval",
                    conversationId, agentId);
            return pausedForApprovalMessage(conversationId);
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.warnf("[CONVERSE] Timeout waiting for agent '%s' response", agentId);
            return "⚠️ Timeout waiting for agent '%s' to respond (60s limit).".formatted(agentId);
        } catch (Exception e) {
            LOGGER.errorf("[CONVERSE] Error conversing with agent '%s': %s", agentId, e.getMessage());
            return "❌ Error conversing with agent '%s': %s".formatted(agentId, e.getMessage());
        }
    }

    /**
     * Structured, actionable result for a delegated conversation that is awaiting
     * human approval. The delegating LLM should relay this to its user and NOT
     * treat the delegation as failed — the nested pause is intentional and must NOT
     * be auto-cancelled. Re-invoke this tool with the same conversationId once a
     * reviewer has decided.
     */
    private String pausedForApprovalMessage(String conversationId) {
        return ("PAUSED_FOR_APPROVAL: the delegated agent's conversation %s requires human approval "
                + "before it can continue. A reviewer must decide via POST /agents/%s/resume "
                + "(APPROVED or REJECTED); re-invoke this tool with the same conversationId afterwards "
                + "to retrieve the outcome.").formatted(conversationId, conversationId);
    }

    /**
     * Extracts the human-readable text from a conversation memory snapshot.
     * Delegates to shared utility.
     */
    private String extractResponse(SimpleConversationMemorySnapshot snapshot) {
        return ai.labs.eddi.engine.memory.ConversationOutputExtractor.extractResponse(snapshot);
    }
}
