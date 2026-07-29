/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Context key carrying the delegation hop count into the callee's conversation.
     * Mirrors the {@code groupDepth} mechanism {@code GroupConversationService}
     * uses for nested groups, so {@code AgentOrchestrator} can read the depth back
     * out of the callee's memory and refuse to go deeper.
     */
    public static final String CONTEXT_DELEGATION_DEPTH = "delegationDepth";

    private final IConversationService conversationService;
    private final String userId;
    private final DynamicAgentConfig config;
    private final int currentDepth;

    /**
     * Delegations performed by this tool instance. The instance is built per
     * {@code buildToolList} call (i.e. per LLM task execution), so this bounds
     * {@code maxDelegationsPerTask} — finding I4.
     */
    private final AtomicInteger delegationCount = new AtomicInteger();

    public ConverseWithAgentTool(IConversationService conversationService, String userId) {
        this(conversationService, userId, permissiveDefault(), 0);
    }

    /**
     * Permissive guardrails for a caller that supplies no config — delegation
     * allowed, still bounded by the {@link DynamicAgentConfig} defaults for depth
     * and per-task count. A bare {@code new DynamicAgentConfig()} would be
     * {@code enabled=false} and refuse everything.
     */
    private static DynamicAgentConfig permissiveDefault() {
        var permissive = new DynamicAgentConfig();
        permissive.setEnabled(true);
        permissive.setAllowDelegation(true);
        return permissive;
    }

    /**
     * @param config
     *            dynamic-agent guardrails governing this delegation. {@code null}
     *            falls back to the same {@link #permissiveDefault()} the two-arg
     *            constructor uses — delegation allowed, still bounded by the
     *            {@link DynamicAgentConfig} depth and per-task defaults. A bare
     *            {@code new DynamicAgentConfig()} would be {@code enabled=false}
     *            and refuse everything, which is not what a caller that supplies no
     *            config means.
     * @param currentDepth
     *            how many delegation hops led to the current conversation (0 when a
     *            human started it)
     */
    public ConverseWithAgentTool(IConversationService conversationService, String userId, DynamicAgentConfig config, int currentDepth) {
        this.conversationService = conversationService;
        this.userId = userId;
        this.config = config != null ? config : permissiveDefault();
        this.currentDepth = Math.max(0, currentDepth);
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

            // --- Guardrail: delegation allowed at all (finding F18 / I4) ---
            if (!config.isEnabled() || !config.isAllowDelegation()) {
                LOGGER.warnf("[CONVERSE] Delegation to agent '%s' refused: allowDelegation is off", agentId);
                return "⚠️ Delegating to another agent is not enabled for this agent.";
            }

            // --- Guardrail: target allowlist (finding F18) ---
            List<String> allowedTargets = config.getAllowedDelegationTargets();
            if (allowedTargets != null && !allowedTargets.isEmpty()
                    && allowedTargets.stream().filter(Objects::nonNull).noneMatch(t -> t.equals(agentId))) {
                LOGGER.warnf("[CONVERSE] Delegation to agent '%s' refused: not in allowedDelegationTargets", agentId);
                return "⚠️ Agent '%s' is not an allowed delegation target. Allowed: %s".formatted(agentId, allowedTargets);
            }

            // --- Guardrail: delegation depth (finding F18) ---
            // Without this an A→B→A cycle recurses unbounded: each hop without a
            // conversationId starts a FRESH conversation, so the busy-guard never fires.
            if (currentDepth >= config.getMaxDelegationDepth()) {
                LOGGER.warnf("[CONVERSE] Delegation to agent '%s' refused: depth %d would exceed maxDelegationDepth=%d",
                        agentId, currentDepth + 1, config.getMaxDelegationDepth());
                return ("⚠️ Maximum delegation depth (%d) reached — this conversation is already %d hop(s) deep. "
                        + "Answer directly instead of delegating further.").formatted(config.getMaxDelegationDepth(), currentDepth);
            }

            // --- Guardrail: delegations per task (finding I4) ---
            if (delegationCount.incrementAndGet() > config.getMaxDelegationsPerTask()) {
                LOGGER.warnf("[CONVERSE] Delegation to agent '%s' refused: maxDelegationsPerTask=%d exhausted",
                        agentId, config.getMaxDelegationsPerTask());
                return "⚠️ Maximum delegations for this task (%d) reached.".formatted(config.getMaxDelegationsPerTask());
            }

            // Propagate the hop count so the callee's own converse_with_agent knows how
            // deep it is — the same mechanism GroupConversationService uses for groupDepth.
            //
            // It must ride on BOTH the start context AND every per-turn InputData:
            // Conversation.init() materializes the start context as context:delegationDepth
            // on step 0 only, while the delegated message is processed on the NEXT step —
            // which is the step AgentOrchestrator.resolveDelegationDepth reads. Without the
            // per-turn context the callee always resolves depth 0 and the cycle guard below
            // can never fire. Continuing an existing conversation (conversationId supplied)
            // needs it for the same reason.
            Map<String, Context> delegationContext = Map.of(CONTEXT_DELEGATION_DEPTH,
                    new Context(Context.ContextType.string, String.valueOf(currentDepth + 1)));

            // --- Start new conversation if no conversationId provided ---
            if (conversationId == null || conversationId.isBlank()) {
                try {
                    ConversationResult convResult = conversationService.startConversation(
                            DEFAULT_ENV, agentId, userId, delegationContext);
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
            inputData.setContext(delegationContext);

            CompletableFuture<SimpleConversationMemorySnapshot> responseFuture = new CompletableFuture<>();
            final java.util.concurrent.atomic.AtomicBoolean skipped = new java.util.concurrent.atomic.AtomicBoolean();
            final String convId = conversationId;

            // Finding H7: a busy-skip (onSkipped, e.g. IN_PROGRESS) must NOT be
            // treated as a fresh response — the default onSkipped→onComplete would
            // return the PREVIOUS turn's output as if it answered this message.
            // Flag the skip and discriminate below (busy vs paused vs not-active).
            conversationService.say(DEFAULT_ENV, agentId, convId,
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

            SimpleConversationMemorySnapshot snapshot = responseFuture.get(60, TimeUnit.SECONDS);

            // Finding 25: the delegated conversation paused for human approval on
            // this turn. Return a structured, actionable result so the delegating
            // LLM can inform its user, rather than reporting "[no response]" and
            // silently losing the eventual approved output.
            if (snapshot != null
                    && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                return pausedForApprovalMessage(convId, snapshot);
            }

            // Finding H7: the input was dropped without being processed (busy or no
            // longer active). Return an actionable "retry"/"not active" result — not
            // the stale previous-turn output.
            if (skipped.get()) {
                ConversationState state = snapshot != null ? snapshot.getConversationState() : null;
                if (state == ConversationState.ENDED || state == ConversationState.EXECUTION_INTERRUPTED) {
                    return ("⚠️ Agent conversation %s is no longer active (state: %s); the message was not "
                            + "delivered.").formatted(convId, state);
                }
                return ("⏳ Agent conversation %s is busy processing another turn; the message was not "
                        + "delivered — retry shortly.").formatted(convId);
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
            // Already-paused at submit — no snapshot available here, so we cannot
            // determine the pause type; report the base (RULE-shaped) message.
            return pausedForApprovalMessage(conversationId, null);
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
    private String pausedForApprovalMessage(String conversationId, SimpleConversationMemorySnapshot snapshot) {
        var message = new StringBuilder(
                ("PAUSED_FOR_APPROVAL: the delegated agent's conversation %s requires human approval "
                        + "before it can continue. A reviewer must decide via POST /agents/%s/resume "
                        + "(APPROVED or REJECTED); re-invoke this tool with the same conversationId afterwards "
                        + "to retrieve the outcome.").formatted(conversationId, conversationId));

        // Task 13 (additive): a TOOL_CALL pause names the pause type and the gated
        // tool NAMES (names only — never arguments, raw or redacted). A RULE pause
        // leaves the message shape unchanged.
        String pauseType = snapshot != null ? snapshot.getHitlPauseType() : null;
        if ("TOOL_CALL".equals(pauseType)) {
            message.append(" pauseType=TOOL_CALL.");
            List<String> toolNames = pendingToolNames(snapshot);
            if (!toolNames.isEmpty()) {
                message.append(" Gated tools: ").append(String.join(", ", toolNames)).append('.');
            }
        }
        return message.toString();
    }

    /**
     * Task 13: the gated tool NAMES from a TOOL_CALL pause snapshot — names ONLY,
     * never arguments (raw or redacted), for privacy. Returns an empty list when no
     * batch is present.
     */
    private List<String> pendingToolNames(SimpleConversationMemorySnapshot snapshot) {
        var batch = snapshot != null ? snapshot.getHitlPendingToolCalls() : null;
        if (batch == null || batch.getCalls() == null) {
            return Collections.emptyList();
        }
        return batch.getCalls().stream()
                .map(PendingToolCall::getToolName)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Extracts the human-readable text from a conversation memory snapshot.
     * Delegates to shared utility.
     */
    private String extractResponse(SimpleConversationMemorySnapshot snapshot) {
        return ai.labs.eddi.engine.memory.ConversationOutputExtractor.extractResponse(snapshot);
    }
}
