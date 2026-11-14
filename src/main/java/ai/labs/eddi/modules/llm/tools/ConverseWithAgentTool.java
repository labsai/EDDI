/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * LLM tool for conversing with another deployed EDDI agent. Constructed
 * per-invocation by {@code AgentOrchestrator} with the conversation service and
 * user identity.
 *
 * <p>
 * Supports both single-turn (fire-and-forget) and multi-turn conversations.
 * When a {@code conversationId} is provided, the tool continues an existing
 * conversation; otherwise it starts a new one.
 * <p>
 * Every delegation, new or continued, is gated on the bound user being allowed
 * to use the target agent ({@link #useCheck}), the same USE rule the REST, MCP
 * and {@code /v1} starts apply: the engine-internal start and {@code say} this
 * tool drives run no such gate of their own.
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

    /**
     * Context key carrying the chain's depth ceiling: the strictest
     * {@code maxDelegationDepth} of every hop so far. Without it a delegate
     * compared the hop count against its own (possibly larger, possibly default)
     * limit, so a restrictive originator's cap applied to the first hop only. The
     * name lies inside the reserved {@code delegationDepth*} namespace
     * ({@code ClientContextGuard}), so a client cannot set it; and it can only
     * lower a delegate's limit, never raise it.
     */
    public static final String CONTEXT_DELEGATION_DEPTH_MAX = "delegationDepthMax";

    private final IConversationService conversationService;
    private final String userId;
    private final DynamicAgentConfig config;
    private final int currentDepth;

    /**
     * The ceiling inherited from earlier hops, or {@code null} at the chain's
     * start.
     */
    private final Integer inheritedMaxDepth;

    /**
     * Delegations this LLM task has made this turn, bounded by
     * {@code maxDelegationsPerTask} — finding I4. Not a count of this instance: a
     * turn builds a new instance per tool assembly (each cascade step, a HITL
     * resume), so {@code DynamicAgentToolsProvider} passes a count kept on the
     * conversation step.
     */
    private final TurnCounter delegationCount;

    /**
     * {@code (agentId, principal) -> may that principal use that agent}, asked
     * before any delegation. {@code null} skips it (direct construction;
     * {@code DynamicAgentToolsProvider} always supplies one under CDI).
     */
    private final BiPredicate<String, String> useCheck;

    /**
     * The delegating conversation's cost ceiling: passed down to the callee before
     * the turn, and charged the callee's spend after it. Never null.
     */
    private final DelegationBudget budget;

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

    /** The stricter of this agent's limit and the one the chain carried in. */
    private int effectiveMaxDepth() {
        int own = config.getMaxDelegationDepth();
        return inheritedMaxDepth == null ? own : Math.min(own, inheritedMaxDepth);
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
        this(conversationService, userId, config, currentDepth, null);
    }

    /**
     * @param useCheck
     *            {@code (agentId, principal) -> boolean}: whether this user may use
     *            the target agent. Without it the model could open — or, through a
     *            conversation this user owns, keep talking to — any deployed agent
     *            in any workspace. {@code null} skips the check.
     */
    public ConverseWithAgentTool(IConversationService conversationService, String userId, DynamicAgentConfig config, int currentDepth,
            BiPredicate<String, String> useCheck) {
        this(conversationService, userId, config, currentDepth, useCheck, null);
    }

    /**
     * @param inheritedMaxDepth
     *            the depth ceiling the delegation chain carried in
     *            ({@link #CONTEXT_DELEGATION_DEPTH_MAX}), or {@code null} when this
     *            conversation was not reached by delegation. The stricter of it and
     *            this agent's own {@code maxDelegationDepth} applies, and is passed
     *            on.
     */
    public ConverseWithAgentTool(IConversationService conversationService, String userId, DynamicAgentConfig config, int currentDepth,
            BiPredicate<String, String> useCheck, Integer inheritedMaxDepth) {
        this(conversationService, userId, config, currentDepth, useCheck, inheritedMaxDepth, TurnCounter.local(), null);
    }

    /**
     * @param delegationCount
     *            the count {@code maxDelegationsPerTask} bounds. Pass
     *            {@link TurnCounter#onStep} so every instance built for the task
     *            during one turn spends the same budget; {@code null} counts per
     *            instance.
     */
    public ConverseWithAgentTool(IConversationService conversationService, String userId, DynamicAgentConfig config, int currentDepth,
            BiPredicate<String, String> useCheck, Integer inheritedMaxDepth, TurnCounter delegationCount) {
        this(conversationService, userId, config, currentDepth, useCheck, inheritedMaxDepth, delegationCount, null);
    }

    /**
     * @param budget
     *            the delegating conversation's cost ceiling
     *            ({@link DelegationBudget}); {@code null} means none — no ceiling
     *            is passed down and nothing is charged back
     */
    public ConverseWithAgentTool(IConversationService conversationService, String userId, DynamicAgentConfig config, int currentDepth,
            BiPredicate<String, String> useCheck, Integer inheritedMaxDepth, DelegationBudget budget) {
        this(conversationService, userId, config, currentDepth, useCheck, inheritedMaxDepth, TurnCounter.local(), budget);
    }

    /**
     * @param delegationCount
     *            see the seven-argument constructor taking it
     * @param budget
     *            see the seven-argument constructor taking it
     */
    public ConverseWithAgentTool(IConversationService conversationService, String userId, DynamicAgentConfig config, int currentDepth,
            BiPredicate<String, String> useCheck, Integer inheritedMaxDepth, TurnCounter delegationCount, DelegationBudget budget) {
        this.delegationCount = delegationCount != null ? delegationCount : TurnCounter.local();
        this.budget = budget != null ? budget : DelegationBudget.UNLIMITED;
        this.inheritedMaxDepth = inheritedMaxDepth;
        this.useCheck = useCheck;
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

        // Outside the try: a timeout settles what the delegated turn spent so far.
        double costBefore = 0.0;
        OptionalDouble messageAllowance = OptionalDouble.empty();
        // The whole allowance handed down, before a start turn's spend is subtracted.
        OptionalDouble delegatedAllowance = OptionalDouble.empty();
        String delegatedConversation = null;
        // Held from admission until settled; settled on every exit (see finally).
        DelegationBudget.Hold hold = null;
        boolean settled = false;
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

            // --- Guardrail: the user may use the target (M-A1) ---
            // For a continuation too: the ownership check below admits any conversation
            // of this user's, which says nothing about whether the user may still use the
            // agent behind it. Before the per-task counter, so a refusal burns no slot.
            if (useCheck != null && !useCheck.test(agentId, userId)) {
                LOGGER.warnf("[CONVERSE] Delegation to agent '%s' refused: the user has no access to it", sanitize(agentId));
                return "⚠️ Agent '%s' is not available to this user, so it cannot be consulted.".formatted(agentId);
            }

            // --- Guardrail: delegation depth (finding F18) ---
            // Without this an A→B→A cycle recurses unbounded: each hop without a
            // conversationId starts a FRESH conversation, so the busy-guard never fires.
            int maxDepth = effectiveMaxDepth();
            if (currentDepth >= maxDepth) {
                LOGGER.warnf("[CONVERSE] Delegation to agent '%s' refused: depth %d would exceed maxDelegationDepth=%d",
                        agentId, currentDepth + 1, maxDepth);
                return ("⚠️ Maximum delegation depth (%d) reached — this conversation is already %d hop(s) deep. "
                        + "Answer directly instead of delegating further.").formatted(maxDepth, currentDepth);
            }

            // --- Guardrail: the caller's cost ceiling ---
            // A delegated turn spends in a conversation of its own. What the caller has
            // left under its ceiling is handed down as the callee's ceiling for the turn,
            // and a caller with nothing left does not delegate at all. Before the
            // per-task counter, so a refusal burns no slot.
            hold = budget.admit();
            OptionalDouble remaining = hold.allowance();
            if (remaining.isPresent() && !(remaining.getAsDouble() > 0.0)) {
                LOGGER.warnf("[CONVERSE] Delegation to agent '%s' refused: the conversation's cost ceiling is reached", sanitize(agentId));
                return "⚠️ This conversation's cost ceiling is reached, so no other agent can be consulted. Answer directly.";
            }

            // --- Guardrail: delegations per task (finding I4) ---
            if (delegationCount.increment() > config.getMaxDelegationsPerTask()) {
                LOGGER.warnf("[CONVERSE] Delegation to agent '%s' refused: maxDelegationsPerTask=%d exhausted",
                        agentId, config.getMaxDelegationsPerTask());
                return "⚠️ Maximum delegations for this task (%d) reached.".formatted(config.getMaxDelegationsPerTask());
            }

            // --- Guardrail: cross-user conversation access (IDOR) ---
            // A model-chosen conversationId must belong to the same user this tool is
            // bound to (the parent conversation's owner). Without this the LLM could
            // continue ANOTHER user's conversation by supplying its id: the agent-id
            // say(...) overload this tool drives checks only that the agentId matches
            // the conversation, not who owns it. A new conversation (no id) is started
            // as this user below and needs no check; the target agent itself is already
            // bounded by the allowDelegation / allowedDelegationTargets guardrails
            // above. Internal group-orchestrator callers use IConversationService
            // directly and never pass through this tool.
            // What the callee's conversation had spent before this turn — the base the
            // charge-back below is measured from. Zero for a conversation started here.
            if (conversationId != null && !conversationId.isBlank()) {
                OwnershipCheck ownership = checkOwnedByBoundUser(conversationId);
                if (ownership.refusal() != null) {
                    return ownership.refusal();
                }
                costBefore = ownership.costBefore();
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
            // never goes through startConversation at all and needs it for the same reason.
            Map<String, Context> delegationContext = new HashMap<>(Map.of(CONTEXT_DELEGATION_DEPTH,
                    new Context(Context.ContextType.string, String.valueOf(currentDepth + 1)),
                    CONTEXT_DELEGATION_DEPTH_MAX, new Context(Context.ContextType.string, String.valueOf(maxDepth))));
            // The caller's remaining allowance rides the same way, on both the start and
            // the message turn: the callee's spend gate holds the turn to it.
            DelegationBudget.addCeiling(delegationContext, remaining);

            // --- Start new conversation if no conversationId provided ---
            boolean startedHere = false;
            messageAllowance = remaining;
            delegatedAllowance = remaining;
            if (conversationId == null || conversationId.isBlank()) {
                try {
                    ConversationResult convResult = conversationService.startConversation(
                            DEFAULT_ENV, agentId, userId, delegationContext);
                    conversationId = convResult.conversationId();
                    startedHere = true;
                    LOGGER.debugf("[CONVERSE] Started new conversation '%s' with agent '%s'",
                            conversationId, agentId);
                } catch (Exception e) {
                    LOGGER.errorf("[CONVERSE] Failed to start conversation with agent '%s': %s",
                            agentId, e.getMessage());
                    return "❌ Failed to start conversation with agent '%s': %s"
                            .formatted(agentId, e.getMessage());
                }
                // One allowance for both turns: what the start turn spent is not handed
                // to the message turn again.
                if (remaining.isPresent()) {
                    double startCost = DelegationBudget.costOf(conversationService, conversationId, remaining.getAsDouble());
                    messageAllowance = DelegationBudget.remainderAfter(remaining, startCost);
                    if (!(messageAllowance.getAsDouble() > 0.0)) {
                        budget.charge(startCost);
                        hold.release();
                        settled = true;
                        return "⚠️ This conversation's cost ceiling was reached while agent '%s' started, so the message was not sent."
                                .formatted(agentId);
                    }
                }
            }

            // --- Send message and wait for response ---
            // The context rides on the MESSAGE turn too — this is the turn the callee's
            // AgentOrchestrator actually builds its tool list for, and the branch that
            // reuses an existing conversationId never went through startConversation at
            // all.
            InputData inputData = new InputData();
            inputData.setInput(message);
            // Own copy: the map above is shared with the startConversation call, and
            // downstream turn processing may add context entries of its own.
            inputData.setContext(new HashMap<>(delegationContext));
            DelegationBudget.addCeiling(inputData.getContext(), messageAllowance);

            CompletableFuture<SimpleConversationMemorySnapshot> responseFuture = new CompletableFuture<>();
            final AtomicBoolean skipped = new AtomicBoolean();
            final String convId = conversationId;
            delegatedConversation = convId;

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

            int timeoutSeconds = config.getDelegationTimeoutSeconds();
            SimpleConversationMemorySnapshot snapshot = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);

            // Charge-back: the callee's spend this turn (a new conversation's start
            // included) counts against the caller's ceilings. A turn that timed out is
            // not charged here — it was held to the ceiling passed down above.
            // A skipped message ran nothing for this call; only a conversation started
            // here still owes its start turn.
            if (!skipped.get() || startedHere) {
                budget.charge(DelegationBudget.spentSince(costBefore, snapshot));
            }
            hold.release();
            settled = true;

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
            // delegation watchdog or surfacing a generic error.
            LOGGER.debugf("[CONVERSE] Conversation '%s' with agent '%s' is awaiting approval",
                    conversationId, agentId);
            // Already-paused at submit — no snapshot available here, so we cannot
            // determine the pause type; report the base (RULE-shaped) message.
            return pausedForApprovalMessage(conversationId, null);
        } catch (TimeoutException e) {
            LOGGER.warnf("[CONVERSE] Timeout waiting for agent '%s' response", agentId);
            // The delegated turn may still be running: charge what it has recorded so
            // far, and hold the rest of its allowance for this turn, so repeated
            // timeouts cannot hand the same money out again.
            // The ORIGINAL allowance: settleUnfinished subtracts everything observed since
            // costBefore, the start turn's spend included — the reduced message allowance
            // would subtract it twice and hold too little.
            DelegationBudget.settleUnfinished(budget, hold, conversationService, delegatedConversation, costBefore, delegatedAllowance, true);
            settled = true;
            // The number has to come from the same config the wait used, or the
            // message tells the model a limit that was never applied — which is how
            // a stale "(60s limit)" survived the timeout becoming configurable.
            return "⚠️ Timeout waiting for agent '%s' to respond (%ds limit).".formatted(agentId,
                    config.getDelegationTimeoutSeconds());
        } catch (Exception e) {
            LOGGER.errorf("[CONVERSE] Error conversing with agent '%s': %s", agentId, e.getMessage());
            return "❌ Error conversing with agent '%s': %s".formatted(agentId, e.getMessage());
        } finally {
            if (!settled && hold != null) {
                // Every other exit — a refusal, a failed start, say() throwing after the
                // start spent, an approval pause: charge what the delegated conversation
                // recorded (nothing when there is none) and stop holding.
                DelegationBudget.settleUnfinished(budget, hold, conversationService, delegatedConversation, costBefore, delegatedAllowance,
                        false);
            }
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

    private record OwnershipCheck(String refusal, double costBefore) {
        static OwnershipCheck refused(String refusal) {
            return new OwnershipCheck(refusal, 0.0);
        }
    }

    private static final String OWNERSHIP_UNVERIFIED = "⚠️ Conversation '%s' cannot be continued because its ownership could not be verified.";

    /**
     * Refuses when a model-supplied {@code conversationId} belongs to a user other
     * than the one this tool is bound to.
     *
     * <p>
     * Without a bound identity (the parent conversation records no user) nothing
     * can be compared, so a supplied id is refused outright: otherwise an anonymous
     * parent could continue any user's conversation by id. Starting a new
     * conversation (no id) is unaffected. A conversation that cannot be found is
     * left to the downstream {@code say(...)} call to surface; any other
     * verification failure — including a conversation that records no owner — fails
     * closed: the conversation is not driven unless ownership could be confirmed.
     *
     * @return a user-facing refusal (null when the conversation may be driven), and
     *         the conversation's spend so far — the base of the charge-back
     */
    private OwnershipCheck checkOwnedByBoundUser(String conversationId) {
        if (userId == null || userId.isBlank()) {
            // Fail closed: with no bound identity there is nothing to compare the
            // owner against, and the agent-id say(...) overload checks only the agent
            // match — so allowing this would let an anonymous parent conversation
            // continue any user's conversation by id.
            LOGGER.warnf("[CONVERSE] Refused continuation of conversation '%s': no bound user to verify ownership against",
                    conversationId);
            return OwnershipCheck.refused(OWNERSHIP_UNVERIFIED.formatted(conversationId));
        }
        try {
            var snapshot = conversationService.getConversationMemorySnapshot(conversationId);
            String owner = snapshot != null ? snapshot.getUserId() : null;
            if (owner == null || owner.isBlank()) {
                // Fail closed: the conversation id is model-supplied, and the agent-id
                // say(...) overload checks only the agent match — so an ownerless
                // (legacy) conversation would otherwise be drivable by any bound user.
                LOGGER.warnf("[CONVERSE] Refused continuation of conversation '%s': ownership could not be established",
                        conversationId);
                return OwnershipCheck.refused(OWNERSHIP_UNVERIFIED.formatted(conversationId));
            }
            if (!owner.equals(userId)) {
                LOGGER.warnf("[CONVERSE] Refused continuation of conversation '%s' owned by another user", conversationId);
                return OwnershipCheck.refused("⚠️ Conversation '%s' does not belong to you and cannot be continued.".formatted(conversationId));
            }
            return new OwnershipCheck(null, ConversationMemoryUtilities.totalCost(snapshot));
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Unknown conversation — let the downstream say(...) path surface it.
            return new OwnershipCheck(null, 0.0);
        } catch (Exception e) {
            LOGGER.warnf("[CONVERSE] Could not verify ownership of conversation '%s': %s", conversationId, e.getMessage());
            return OwnershipCheck.refused("⚠️ Could not verify access to conversation '%s'.".formatted(conversationId));
        }
    }

    /**
     * Extracts the human-readable text from a conversation memory snapshot.
     * Delegates to shared utility.
     */
    private String extractResponse(SimpleConversationMemorySnapshot snapshot) {
        return ConversationOutputExtractor.extractResponse(snapshot);
    }
}
