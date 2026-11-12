/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.agenttests.ToolMockRegistry;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.ToolDisplay;
import ai.labs.eddi.engine.hitl.tools.ClearedToolCalls;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolCallTrace;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.TokenCountEstimator;
import ai.labs.eddi.engine.hitl.tools.AllowedToolCallObserver;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRules;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.ConfigReferenceRefusedException;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.guardrails.ToolArgumentSchemaValidator;
import ai.labs.eddi.modules.llm.tools.spi.ToolArgumentValidator;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import ai.labs.eddi.modules.llm.governance.ToolResultProvenance;
import ai.labs.eddi.modules.llm.guardrails.PaidCallGate;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolNameResolver;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaGate;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaService;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Metrics;
import io.opentelemetry.context.Scope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * The live tool-calling loop (R2 step 5): drive the model, gate and execute the
 * tools it asks for, meter the cost and the context budget, and pause the turn
 * when a gated call needs a human. Extracted from {@code AgentOrchestrator} as
 * a pure move — no behavior change.
 * <p>
 * This is the class the R2 decomposition was working toward. What was left in
 * {@code AgentOrchestrator} around it — tool assembly, the gate's supporting
 * cast, the token budget — were all the things the loop <em>uses</em>; the loop
 * itself is the one part that is genuinely orchestration, and it is now the
 * only thing in here.
 * <p>
 * Shares its execution pipeline with the resume path by construction rather
 * than by convention: {@code ToolLoopResumer} calls {@link #runToolCallLoop} to
 * continue a turn from the iteration that paused, and
 * {@link #executeSingleToolCallResult} to apply each human-approved verdict, so
 * a rate limit, cache hit, cost charge or LAZY activation behaves identically
 * whether the call was made by the model or approved by a person. That was
 * already true before the extraction — it is stated here because two classes
 * sharing one pipeline is easier to break than one class calling itself.
 * <p>
 * Holds no conversation state. Every method takes the turn's
 * {@link AgentOrchestrator.ToolSetup} and memory as parameters, which is what
 * lets a single instance be constructed once per orchestrator and shared across
 * every concurrent conversation.
 */
class ToolLoopRunner {

    private static final Logger LOGGER = Logger.getLogger(ToolLoopRunner.class);

    private final ToolExecutionService toolExecutionService;
    private final ToolResponseTruncator toolResponseTruncator;
    private final TenantQuotaService tenantQuotaService;
    private final MemorySnapshotService memorySnapshotService;
    private final ToolApprovalGate toolApprovalGate;
    private final ToolApprovalGateSupport gateSupport;
    private final ToolContextBudget toolContextBudgetGuard;
    private final ToolResultGuardrail toolResultGuardrail;
    /**
     * The task's {@code toolCallGuardrails}, run over each response's admitted
     * calls before the approval gate. Set by {@code AgentOrchestrator}'s CDI
     * initializer rather than the constructor, which tests call directly; null
     * there, and then inert.
     */
    volatile ToolCallGuardrails toolCallGuardrails;

    /**
     * Told about every allowed call after it ran (the sampled audit); set by the
     * owner.
     */
    private volatile AllowedToolCallObserver allowedCallObserver = AllowedToolCallObserver.NONE;

    ToolLoopRunner(ToolExecutionService toolExecutionService, ToolResponseTruncator toolResponseTruncator,
            TenantQuotaService tenantQuotaService, MemorySnapshotService memorySnapshotService,
            ToolApprovalGate toolApprovalGate, ToolApprovalGateSupport gateSupport,
            ToolContextBudget toolContextBudgetGuard, ToolResultGuardrail toolResultGuardrail) {
        this.toolExecutionService = toolExecutionService;
        this.toolResponseTruncator = toolResponseTruncator;
        this.tenantQuotaService = tenantQuotaService;
        this.memorySnapshotService = memorySnapshotService;
        this.toolApprovalGate = toolApprovalGate;
        this.gateSupport = gateSupport;
        this.toolContextBudgetGuard = toolContextBudgetGuard;
        this.toolResultGuardrail = toolResultGuardrail;
    }

    void setAllowedCallObserver(AllowedToolCallObserver observer) {
        this.allowedCallObserver = observer != null ? observer : AllowedToolCallObserver.NONE;
    }

    /**
     * Computes the specs the LLM initially sees given a
     * {@link AgentOrchestrator.ToolSetup}. For EAGER, that is every registered
     * spec. For LAZY, only {@code discover_tools} plus the external (http/mcp/a2a)
     * specs — the built-ins stay hidden until discovery activates them. Shared by
     * the live loop and resume so both present an identical initial surface.
     * Returns a fresh mutable list (LAZY activation mutates it in place).
     */
    static List<ToolSpecification> computeInitialActiveSpecs(AgentOrchestrator.ToolSetup setup, boolean isLazy) {
        if (!isLazy) {
            return setup.toolSpecs();
        }
        // External = every registered spec whose name is not a built-in spec name.
        Set<String> builtInNames = new HashSet<>();
        for (ToolSpecification spec : setup.builtInSpecs()) {
            builtInNames.add(spec.name());
        }
        List<ToolSpecification> activeSpecs = new ArrayList<>();
        int externalCount = 0;
        for (ToolSpecification spec : setup.toolSpecs()) {
            if ("discover_tools".equals(spec.name())) {
                activeSpecs.add(spec);
            } else if (!builtInNames.contains(spec.name())) {
                activeSpecs.add(spec);
                externalCount++;
            }
        }
        LOGGER.debugf("LAZY mode: presenting %d specs initially (discover_tools + %d external)",
                activeSpecs.size(), externalCount);
        return activeSpecs;
    }

    /**
     * Executes the tool-calling loop using direct ChatModel API against a pre-built
     * {@link AgentOrchestrator.ToolSetup} (shared with the resume path).
     */
    AgentOrchestrator.ExecutionResult executeWithTools(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages,
                                                       AgentOrchestrator.ToolSetup setup,
                                                       LlmConfiguration.Task task, IConversationMemory memory,
                                                       ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex, int transcriptMaxBytes,
                                                       JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {
        return executeWithTools(chatModel, systemMessage, chatMessages, setup, task, memory, effectiveToolApprovals, llmTaskIndex,
                transcriptMaxBytes, jsonPolicy, null);
    }

    /**
     * As above, additionally recording every completed tool call and its result
     * into {@code exchangeRecorder} as the loop runs (see
     * {@link ToolExchangeRecorder}); {@code null} records nothing.
     */
    AgentOrchestrator.ExecutionResult executeWithTools(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages,
                                                       AgentOrchestrator.ToolSetup setup,
                                                       LlmConfiguration.Task task, IConversationMemory memory,
                                                       ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex, int transcriptMaxBytes,
                                                       JsonResponseFormatPolicy jsonPolicy, ToolExchangeRecorder exchangeRecorder)
            throws LifecycleException {

        // The setup's executors / sources / built-in specs are deliberately NOT
        // unpacked here: this method hands the whole ToolSetup to runToolCallLoop,
        // which reads them itself. Three locals used to sit here reading exactly
        // those three components and nothing ever used them — dead since before the
        // extraction, and only visible now that the method has a file of its own.
        boolean isLazy = task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY;

        // Active specs: what the LLM currently sees (LAZY starts narrow).
        List<ToolSpecification> activeSpecs = computeInitialActiveSpecs(setup, isLazy);
        if (isLazy) {
            // What discover_tools already activated in this turn (a cascade step
            // before this one) or, with lazyRetention: conversation, earlier.
            LazyToolCatalog.reactivate(memory, task, setup.builtInSpecs(), activeSpecs);
        }

        // Build message list with system message if provided
        List<ChatMessage> messages = new ArrayList<>();
        if (!isNullOrEmpty(systemMessage)) {
            messages.add(SystemMessage.from(systemMessage));
        }
        messages.addAll(chatMessages);

        // Trace for tool calls
        List<Map<String, Object>> trace = new ArrayList<>();

        // Live path: iteration loop starts at 0, no pre-cleared call ids.
        String conversationId = memory != null ? memory.getConversationId() : null;
        double toolCostBefore = conversationToolCost(conversationId);
        TokenUsage[] tokenHolder = new TokenUsage[1];
        List<ChatMessage> finalTranscript = new ArrayList<>();
        String response = runToolCallLoop(chatModel, messages, activeSpecs, trace, 0, toolCostBefore,
                setup, isLazy, task, memory, effectiveToolApprovals, llmTaskIndex, ClearedToolCalls.none(), transcriptMaxBytes, tokenHolder,
                jsonPolicy,
                finalTranscript, exchangeRecorder);

        Map<String, Object> responseMetadata = new HashMap<>();
        if (tokenHolder[0] != null) {
            responseMetadata.put("tokenUsage", ToolContextBudget.tokenUsageMap(tokenHolder[0]));
        }
        responseMetadata.put("toolCostUsd", toolCostDelta(conversationId, toolCostBefore));
        return new AgentOrchestrator.ExecutionResult(response, trace, responseMetadata, toolExchange(finalTranscript));
    }

    /**
     * The tool exchange of the final transcript, in order: every assistant message
     * that requested tools and every tool result, but not the final answer and not
     * the text-only conversation history. Includes an exchange the loop was started
     * with (a cascade step that received an earlier step's tools), so it is the
     * complete record to hand on. What a cascade escalation gives the next step so
     * it continues from the executed tools instead of executing them again.
     * <p>
     * <b>Every call carries an id.</b> The exchange may be replayed to a DIFFERENT
     * provider, and some bindings (Ollama, Gemini) pass the provider's id through
     * as it arrives — often null — while OpenAI and Anthropic reject a tool call or
     * result without one. A null-id call gets a synthetic {@code gen-} id, and the
     * result that answers it (the next null-id result for the same tool, in order)
     * gets the same id, so the pairing the next provider checks still holds.
     */
    static List<ChatMessage> toolExchange(List<ChatMessage> transcript) {
        List<ChatMessage> exchange = new ArrayList<>();
        // tool name -> synthetic ids assigned to calls whose results are still to come
        Map<String, ArrayDeque<String>> pendingIds = new HashMap<>();
        for (ChatMessage message : transcript) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                exchange.add(withCallIds(ai, pendingIds));
            } else if (message instanceof ToolExecutionResultMessage result) {
                exchange.add(withResultId(result, pendingIds));
            }
        }
        return exchange;
    }

    private static AiMessage withCallIds(AiMessage ai, Map<String, ArrayDeque<String>> pendingIds) {
        if (ai.toolExecutionRequests().stream().allMatch(r -> r.id() != null)) {
            return ai;
        }
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
            if (request.id() != null) {
                requests.add(request);
                continue;
            }
            String id = "gen-" + UUID.randomUUID();
            pendingIds.computeIfAbsent(request.name(), n -> new ArrayDeque<>()).add(id);
            requests.add(ToolExecutionRequest.builder().id(id).name(request.name())
                    .arguments(request.arguments() != null && !request.arguments().isBlank() ? request.arguments() : "{}").build());
        }
        return ai.toBuilder().toolExecutionRequests(requests).build();
    }

    private static ToolExecutionResultMessage withResultId(ToolExecutionResultMessage result,
                                                           Map<String, ArrayDeque<String>> pendingIds) {
        if (result.id() != null) {
            return result;
        }
        var ids = pendingIds.get(result.toolName());
        String id = ids != null && !ids.isEmpty() ? ids.poll() : "gen-" + UUID.randomUUID();
        return result.toBuilder().id(id).build();
    }

    /**
     * Accumulated tool cost for a conversation, or {@code 0.0} when nothing has
     * been tracked for it yet — {@link ToolCostTracker#getConversationCosts}
     * returns null for an untracked conversation.
     */
    double conversationToolCost(String conversationId) {
        if (conversationId == null || toolExecutionService == null) {
            return 0.0;
        }
        ToolCostTracker tracker = toolExecutionService.getCostTracker();
        if (tracker == null) {
            return 0.0;
        }
        ToolCostTracker.ConversationCostMetrics metrics = tracker.getConversationCosts(conversationId);
        return metrics != null ? metrics.getTotalCost() : 0.0;
    }

    /**
     * Dollar cost the tools of THIS model call added, as the difference between two
     * snapshots of the conversation total. Clamped at zero because the tracker can
     * drop the conversation's entry between the snapshots (its size-bounded
     * eviction, {@link ToolCostTracker#resetAll}), which would otherwise yield a
     * negative "cost".
     */
    double toolCostDelta(String conversationId, double costBefore) {
        return Math.max(0.0, conversationToolCost(conversationId) - costBefore);
    }

    /**
     * The single shared tool-calling loop. Each model call inside it is wrapped in
     * {@link AgentExecutionHelper#executeWithRetry}; the loop itself never is, so a
     * retry can never re-execute a tool that already ran. Used by the live path
     * (start iteration 0, no cleared calls) and by {@link #resumeToolLoop} (start
     * iteration {@code batch.getIterationIndex()+1}, {@code clearedCalls} = the
     * approved calls the resumer already ran, so an identical reissue is gated
     * again as a repeat). A fresh gated batch throws
     * {@link ToolApprovalRequiredException} to re-pause — it escapes unchanged.
     *
     * @param initialMessages
     *            the message list the loop starts from (defensively copied inside);
     *            for resume this already carries the replayed transcript + the
     *            verdict-applied tool results
     * @param startIteration
     *            first loop index — carries budget continuity across a pause
     * @param toolCostBefore
     *            the conversation's tracked tool cost before this run's first tool
     *            ran — for a resume, before the approved calls, so the cost ceiling
     *            counts what they charged
     * @param clearedCalls
     *            calls a human already approved this pause, bound to id, tool and
     *            arguments and consumed on first match
     * @param transcriptMaxBytes
     *            the configured cap (bytes) for freezing the transcript into a
     *            {@link PendingToolCallBatch} if this iteration re-pauses
     * @param jsonPolicy
     *            decides, per request, whether {@code ResponseFormat.JSON} is set;
     *            resolved against whether THAT request carries tool specifications
     * @param transcriptOut
     *            when non-null, receives the loop's final message list on a normal
     *            return (not on a pause or a failure)
     * @param exchangeRecorder
     *            when non-null, receives each tool call together with its result
     *            the moment the result is appended — the part of the exchange that
     *            survives a pause, a failure or a cancellation of this run
     */
    String runToolCallLoop(ChatModel chatModel, List<ChatMessage> initialMessages, List<ToolSpecification> activeSpecs,
                           List<Map<String, Object>> trace, int startIteration, double toolCostBefore, AgentOrchestrator.ToolSetup setup,
                           boolean isLazy,
                           LlmConfiguration.Task task, IConversationMemory memory, ToolApprovalsConfig effectiveToolApprovals,
                           int llmTaskIndex, ClearedToolCalls clearedCalls, int transcriptMaxBytes, TokenUsage[] tokenHolder,
                           JsonResponseFormatPolicy jsonPolicy, List<ChatMessage> transcriptOut, ToolExchangeRecorder exchangeRecorder)
            throws LifecycleException {

        Map<String, ToolExecutor> toolExecutors = setup.toolExecutors();
        Map<String, String> toolSources = setup.toolSources();
        Map<String, String> toolCanonicalNames = setup.toolCanonicalNames();
        List<ToolSpecification> builtInSpecs = setup.builtInSpecs();

        boolean enableRateLimiting = task.getEnableRateLimiting() != null ? task.getEnableRateLimiting() : true;
        boolean enableCaching = task.getEnableToolCaching() != null ? task.getEnableToolCaching() : true;
        boolean enableCostTracking = task.getEnableCostTracking() != null ? task.getEnableCostTracking() : true;
        int defaultRateLimit = task.getDefaultRateLimit() != null ? task.getDefaultRateLimit() : 100;
        Map<String, Integer> toolRateLimits = task.getToolRateLimits();
        Double maxBudget = task.getMaxBudgetPerConversation();
        String conversationId = memory.getConversationId();

        // In-turn tool-context ceiling. Resolved once per loop: <= 0 disables the
        // guard entirely (and skips estimator construction with it).
        int toolContextBudget = task.getMaxToolContextTokens() != null
                ? task.getMaxToolContextTokens()
                : AgentOrchestrator.DEFAULT_MAX_TOOL_CONTEXT_TOKENS;
        TokenCountEstimator toolContextEstimator = toolContextBudget > 0 ? toolContextBudgetGuard.resolveToolContextEstimator(task) : null;

        // One backoff budget for every model request of this loop run, so per-request
        // retries keep the documented total ceiling instead of one per request.
        long[] backoffSpentMs = new long[1];
        // What this run has spent and not yet recorded: its model rounds at the
        // prices of the model it runs on (a cascade step's, when a cascade runs it)
        // and its tool calls. One figure for every exit and every check — the
        // mid-loop ceiling, the tenant check before each tool call, the pause and a
        // failure — so they cannot price the same rounds differently.
        ModelPrices prices = exchangeRecorder != null && exchangeRecorder.prices() != null ? exchangeRecorder.prices() : ModelPrices.ofTask(task);
        DoubleSupplier runSpend = () -> prices.cost(tokenHolder[0] != null ? ToolContextBudget.tokenUsageMap(tokenHolder[0]) : null)
                + toolCostDelta(conversationId, toolCostBefore);
        try {
            tokenHolder[0] = null;
            List<ChatMessage> currentMessages = new ArrayList<>(initialMessages);
            // Per-message token memo, local to this attempt: messages are immutable and
            // re-counted on every iteration, so without it a 10-iteration loop tokenizes
            // the first tool result ten times. Lives on the call stack — the orchestrator
            // is a shared singleton and must stay stateless.
            Map<ChatMessage, Integer> toolContextTokenMemo = new IdentityHashMap<>();
            int maxIterations = effectiveMaxToolIterations(task);
            boolean modelAnswered = false;

            // Call caps. The per-turn count starts from the calls the trace already
            // records, so a HITL resume continues the turn's count instead of
            // restarting it.
            int perResponseCap = effectiveCallCap(task.getMaxToolCallsPerIteration(), DEFAULT_MAX_TOOL_CALLS_PER_ITERATION);
            int perTurnCap = effectiveCallCap(task.getMaxToolCallsPerTurn(), DEFAULT_MAX_TOOL_CALLS_PER_TURN);
            int toolCallsThisTurn = countToolCalls(trace);

            // answerMode "tool": the reply tool is always on the surface — a LAZY
            // start and a HITL resume restore their own surfaces, and neither may lose
            // it — and the request forces a tool call where the provider can.
            String answerToolName = setup.answerToolName();
            ToolChoice answerToolChoice = null;
            if (answerToolName != null) {
                if (activeSpecs.stream().noneMatch(spec -> answerToolName.equals(spec.name()))) {
                    activeSpecs.add(setup.answerTool());
                }
                answerToolChoice = AnswerTool.toolChoice(task, jsonPolicy != null ? jsonPolicy.provider() : null);
            }

            // Repeat guard: stack-local on purpose (the runner is a shared singleton),
            // so a HITL resume, which is a new run of this method, starts a fresh one.
            // Null when the task switched it off.
            ToolRepeatGuard repeatGuard = ToolRepeatGuard.forTask(task, toolCanonicalNames);
            ToolCallGuardrails.RunBudget argumentModelChecks = ToolCallGuardrails.newRunBudget();

            // Engine-enforced counterweight: strict mode caps iterations
            var counterweight = task.getCounterweight();
            if (counterweight != null && counterweight.isEnabled()
                    && "strict".equalsIgnoreCase(counterweight.getLevel())) {
                int strictCap = 5;
                if (maxIterations > strictCap) {
                    LOGGER.infof("Counterweight strict mode: capping tool iterations from %d to %d",
                            maxIterations, strictCap);
                    maxIterations = strictCap;
                }
            }

            // The agent's per-conversation cost ceiling, checked between rounds: the
            // spend before this loop (every step's audit:cost) plus what this loop has
            // spent so far — its tokens at the task's prices and its tool cost. Without
            // it one tool-heavy turn could run far past the ceiling, since the task-level
            // gate only sees a loop's cost once the whole loop has finished.
            // A scheduled fire's maxCostPerFire tightens it
            // (LlmSpendGate.effectiveCeiling).
            OptionalDouble costCeiling = LlmSpendGate.effectiveCeiling(memory);
            boolean ceilingApplies = LlmSpendGate.ceilingApplies(costCeiling);
            double costBeforeLoop = ceilingApplies ? LlmSpendGate.conversationCost(memory) : 0.0;
            // The live path's first request was gated by the task-level preflight, so
            // the check starts once the model has answered. A resume (startIteration
            // > 0) has already run the approved calls, whose cost no preflight saw:
            // its first request is checked too.
            boolean resumed = startIteration > 0;

            for (int i = startIteration; i < maxIterations; i++) {
                if (ceilingApplies && (modelAnswered || resumed)) {
                    double spent = costBeforeLoop + runSpend.getAsDouble();
                    if (spent >= costCeiling.getAsDouble()) {
                        String detail = String.format("Conversation cost ceiling ($%.4f) reached: $%.4f spent", costCeiling.getAsDouble(),
                                spent);
                        LlmSpendGate.countMidLoop(conversationId, detail);
                        Map<String, Object> ceilingStep = new HashMap<>();
                        ceilingStep.put("type", "cost_ceiling");
                        ceilingStep.put("error", detail);
                        trace.add(ceilingStep);
                        return finish(currentMessages, transcriptOut, costCeilingReachedMessage());
                    }
                }

                // Cooperative cancellation: if this step was interrupted (e.g. a cascade
                // per-step timeout called future.cancel(true)), stop before issuing another
                // model call — avoids launching further side-effectful tools on a step whose
                // result will be discarded. Thread.interrupted() also CLEARS the flag so it
                // cannot leak to any later work on this thread.
                if (Thread.interrupted()) {
                    throw new LifecycleException("Agent execution cancelled (interrupted)");
                }
                // The turn's own cancel flag, which nothing below can clear: raised by a
                // caller's cancel, a GDPR stop, the watchdog and a lost cluster lease (whose
                // conversation another node now runs). Without this check a tool loop ran
                // all its remaining rounds after any of those — more model calls and more
                // side-effectful tools for a turn whose result is discarded.
                if (memory.isCancelled()) {
                    throw new LifecycleException("Agent execution cancelled");
                }

                // Keep the accumulated tool traffic inside its aggregate ceiling BEFORE
                // the request is built — this is the only point at which the loop knows
                // everything it is about to send. Without it the list only ever grows and
                // a tool-heavy turn hard-fails mid-loop on a provider context-window 400.
                if (toolContextEstimator != null) {
                    ToolContextBudget.enforceToolContextBudget(currentMessages, toolContextBudget, toolContextEstimator,
                            toolContextTokenMemo, trace, conversationId);
                }

                ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(currentMessages);

                boolean toolsInRequest = !activeSpecs.isEmpty();
                if (toolsInRequest) {
                    requestBuilder.toolSpecifications(activeSpecs);
                }

                // API-level JSON, decided against THIS request's tool surface. Baking it
                // into the model instead would put it on every request the cached model
                // ever serves — the Gemini 400 documented in docs/changelog.md.
                // Not with the reply tool: the answer arrives as its arguments, which the
                // provider's function calling already holds to the schema.
                if (answerToolName != null) {
                    if (answerToolChoice != null) {
                        requestBuilder.toolChoice(answerToolChoice);
                    }
                } else if (jsonPolicy != null) {
                    var responseFormat = jsonPolicy.resolve(toolsInRequest);
                    if (responseFormat != null) {
                        requestBuilder.responseFormat(responseFormat);
                    }
                }

                ChatRequest chatRequest = requestBuilder.build();

                // The retry wraps THIS model call and nothing else. It used to wrap the
                // whole loop, so a 429 on iteration 4 restarted from initialMessages and
                // re-executed every tool iterations 0-3 had already run — a second POST,
                // a second created agent, a second memory write — and charged and traced
                // them twice. Retrying the single request resends the same transcript,
                // tool results included, so the model continues where it stopped.
                ChatResponse chatResponse;
                try {
                    chatResponse = AgentExecutionHelper.executeWithRetry(() -> chatModel.chat(chatRequest), task, "Agent execution",
                            backoffSpentMs, memory.getTurnDeadline());
                } catch (LifecycleException requestFailure) {
                    // Nothing has executed in this run until the first request is
                    // answered. Say so, so a caller that may retry the run (the cascade's
                    // carried-exchange fallback) can tell "rejected up front" apart from
                    // "failed after tools already ran", where a retry would replay them.
                    if (!modelAnswered) {
                        throw new FailedBeforeToolsException(requestFailure);
                    }
                    throw requestFailure;
                }
                modelAnswered = true;
                // The reply tool's calls are taken out of the response BEFORE anything
                // else sees it: the call caps, the approval gate, quotas, rate limits,
                // the cache and cost tracking only ever see the other calls, and the
                // transcript (a pause freezes it, a cascade hands it on) never holds a
                // call without a result.
                AnswerTool.Extraction answerCall = AnswerTool.extract(
                        ToolApprovalGateSupport.normalizeToolCallIds(chatResponse.aiMessage(), effectiveToolApprovals), answerToolName);
                AiMessage aiMessage = answerCall.remaining();
                if (answerCall.replyCalls() > 1) {
                    AnswerTool.duplicate(conversationId, answerCall.replyCalls());
                }
                // A response that is only the reply call adds nothing to the transcript
                // here; answerWith appends the answer as the final assistant message.
                if (!answerCall.answered() || answerCall.hasOtherCalls()) {
                    currentMessages.add(aiMessage);
                }

                // Accumulate token usage for cost/observability reporting.
                if (chatResponse.metadata() != null && chatResponse.metadata().tokenUsage() != null) {
                    tokenHolder[0] = ToolContextBudget.sumTokens(tokenHolder[0], chatResponse.metadata().tokenUsage());
                    if (exchangeRecorder != null) {
                        exchangeRecorder.recordUsage(chatResponse.metadata().tokenUsage());
                    }
                }

                if (aiMessage.hasToolExecutionRequests()) {
                    // === Call caps ===
                    // Admit at most the per-response cap, and no more than what is left of
                    // the per-turn cap. The rest never reach the gate, so a refused call
                    // can never pause the turn for an approval of a call that was not going
                    // to run. They are answered after the admitted calls, which keeps the
                    // results in the order the model listed the calls (the refused ones are
                    // always the tail).
                    CallAdmission admission = admitWithinCallCaps(aiMessage, perResponseCap, perTurnCap, toolCallsThisTurn);
                    toolCallsThisTurn += admission.admitted().size();
                    List<ToolExecutionRequest> admitted = admission.admitted();
                    // LAZY: a lazily-managed tool the model calls without discover_tools
                    // having activated it is refused here, before the gate — never run,
                    // and never put in front of a human for approval.
                    if (isLazy) {
                        admitted = refuseUnactivated(admitted, builtInSpecs, activeSpecs, toolSources, aiMessage, currentMessages, trace,
                                exchangeRecorder);
                    }
                    // === Argument schema (before the gate) ===
                    // A call whose arguments break its tool's declared schema is answered
                    // NOT_EXECUTED here and never reaches the gate: no human is asked to
                    // approve a request that cannot be right. Only executors that declare
                    // a schema check anything (ToolArgumentValidator).
                    admitted = refuseInvalidArguments(admitted, toolExecutors, toolSources, currentMessages, aiMessage, trace,
                            exchangeRecorder);

                    // === Tool-call guardrails (toolCallGuardrails), before the approval gate ===
                    // A refused call never reaches a human or a tool; a redaction is what the
                    // approver, the transcript and the tool all see. Null: no guardrails.
                    var argumentScreen = toolCallGuardrails == null
                            ? null
                            : toolCallGuardrails.screenBatch(task, memory, aiMessage, admitted, toolSources, setup.toolEndpoints(), currentMessages,
                                    trace, argumentModelChecks, sideCallSpend(memory, conversationId, enableCostTracking, runSpend));
                    if (argumentScreen != null) {
                        aiMessage = argumentScreen.message();
                        admitted = argumentScreen.admitted();
                        for (ToolCallGuardrails.Refusal refused : argumentScreen.refused()) {
                            addResult(currentMessages, aiMessage, refused.request(), refused.result(), exchangeRecorder);
                        }
                    }

                    // === Tool-approval gate (tool-level HITL) ===
                    // Split the batch into gated (require human approval) and allowed
                    // calls. clearedCalls carries the approved calls the resumer already
                    // ran (bound to id + tool + arguments); a reissue of one is gated
                    // again as a repeat. Inert when
                    // effectiveToolApprovals is
                    // null/empty — byte-identical to the pre-HITL path.
                    var screened = refuseDoomedGatedCalls(
                            refuseExhaustedGatedCalls(
                                    admitted.isEmpty()
                                            ? new ToolApprovalGate.GateResult(List.of(), List.of(), Map.of())
                                            : toolApprovalGate.classify(admitted, toolSources, setup.toolEndpoints(),
                                                    effectiveToolApprovals, clearedCalls),
                                    task, memory, toolCanonicalNames, currentMessages, aiMessage, trace, exchangeRecorder),
                            setup.toolRequestResolvers(), currentMessages, aiMessage, trace, exchangeRecorder);
                    var gateResult = screened.gateResult();
                    // The pin step below reuses what the screening already resolved.
                    var pinResolvers = screened.resolvers();

                    if (!gateResult.gated().isEmpty()) {
                        int pausesSoFar = ToolApprovalGateSupport.readToolPauseCount(memory);
                        if (pausesSoFar >= ToolApprovalGateSupport.maxPausesPerTurn(effectiveToolApprovals)) {
                            // Fail-closed: the pause budget for this turn is spent —
                            // do not pause and do not execute the gated calls; tell
                            // the model to stop asking so the loop can still finish
                            // with the ungated results.
                            for (ToolExecutionRequest gatedReq : gateResult.gated()) {
                                addResult(currentMessages, aiMessage, gatedReq,
                                        "{\"status\":\"DENIED\",\"reason\":\"approval-pause limit for this turn reached; do not retry\"}",
                                        exchangeRecorder);
                                Map<String, Object> capStep = new HashMap<>();
                                capStep.put("type", "tool_error");
                                capStep.put("tool", gatedReq.name());
                                withCallId(capStep, gatedReq);
                                capStep.put("error", "hitl_pause_cap");
                                trace.add(capStep);
                            }
                            // Task 10 — the pause-cap guard fired: record it as a guard
                            // (metric + audit) HERE where the audit context lives (the
                            // memory's audit collector, set by ConversationService on the
                            // say/resume paths). AgentOrchestrator is not CDI and has no
                            // MeterRegistry, so the metric uses the Micrometer global
                            // registry (same idiom as LifecycleManager). Emitted once per
                            // capped batch, carrying the gated fingerprint.
                            gateSupport.recordPauseCapGuard(memory, ToolApprovalGateSupport.fingerprint(gateResult.gated()));
                            // ungated calls still execute below
                        } else {
                            // 1) execute the ungated calls of this batch normally
                            for (ToolExecutionRequest allowedReq : gateResult.allowed()) {
                                if (memory.isCancelled()) {
                                    throw new LifecycleException("Agent execution cancelled before tool: " + allowedReq.name());
                                }
                                // An interrupted (abandoned) thread must not start another
                                // side-effectful tool either. Checked without clearing the flag,
                                // and with the same exception, as the abandoned-thread guard
                                // after this loop: this branch must end without committing a pause.
                                if (Thread.currentThread().isInterrupted()) {
                                    throw new LifecycleException.LifecycleInterruptedException(
                                            "Tool-approval batch abandoned: executing thread was interrupted before tool: "
                                                    + allowedReq.name());
                                }
                                // Same absolute rule as the main allowed() loop below.
                                // This branch runs BEFORE the pause is thrown, and its
                                // results are frozen into the batch the resume replays
                                // — a self-conversation call slipping through here is
                                // never checked again anywhere. A mixed batch (one
                                // gated call, one ungated self-message) was exactly
                                // the remaining hole.
                                if (refusedAsSelfTargeted(allowedReq, aiMessage, setup, conversationId, currentMessages, trace,
                                        exchangeRecorder)) {
                                    continue;
                                }
                                boolean executed = executeGuarded(repeatGuard, allowedReq, aiMessage, currentMessages, trace, toolSources,
                                        exchangeRecorder,
                                        () -> executeToolCall(allowedReq, memory, trace, toolExecutors, toolRateLimits, toolCanonicalNames,
                                                toolSources, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching,
                                                enableCostTracking, task, isLazy, builtInSpecs, activeSpecs, runSpend));
                                if (executed) {
                                    allowedCallObserver.afterAllowed(memory, allowedReq, toolSources, setup.toolEndpoints(), effectiveToolApprovals,
                                            trace);
                                }
                            }
                            // Abandoned-thread guard: a cascade step that timed out (or
                            // the agentTimeout watchdog on the live path) cancels the future
                            // via cancel(true), interrupting THIS thread — but it keeps
                            // running to here on the shared live memory while the caller has
                            // already moved on. Committing pause state now would leave a
                            // stale batch on a conversation the caller abandoned (self-heals
                            // at next turn start) or overwrite a later step's real pause.
                            // Abort WITHOUT mutating shared memory: throw the interrupted
                            // signal instead of the pause. NOT ToolApprovalRequiredException,
                            // which would commit a pause with a null batch. The guarantee
                            // here is only that this abandoned thread never writes stale/
                            // overwriting pause state; the resulting terminal state is
                            // fail-safe either way — a cascade discards the Future's exception
                            // entirely, and on the live path wrapLoopFailure
                            // re-wraps this into a plain LifecycleException so the turn settles
                            // to ERROR (where the watchdog fired it already persisted
                            // EXECUTION_INTERRUPTED). Both are recoverable by the next say.
                            if (Thread.currentThread().isInterrupted()) {
                                throw new LifecycleException.LifecycleInterruptedException(
                                        "Tool-approval pause abandoned: executing thread was interrupted before commit");
                            }
                            // 2) resolve the per-tool friction rule (iteration 1). Done HERE,
                            // once, because this is the last place the gated calls' endpoints
                            // exist — the persisted batch keeps names and sources only, so an
                            // endpoint-addressed rule could never be re-matched downstream.
                            var ruleByCallId = ToolApprovalRules.matchByCallId(gateResult.gated(), toolSources,
                                    setup.toolEndpoints(), effectiveToolApprovals);
                            var governingRule = ToolApprovalRules.governing(ruleByCallId.values());
                            gateSupport.recordRuleMatches(ruleByCallId.values());
                            // 3) answer the refused calls, so the frozen transcript has a result
                            // for every call the resume will not answer
                            admission.answerRefused(aiMessage, currentMessages, trace, exchangeRecorder);
                            // 4) snapshot + persist the pending batch, then abort the loop
                            PendingToolCallBatch batch = gateSupport.buildPendingBatch(currentMessages, gateResult, task, memory,
                                    i, ToolApprovalGateSupport.activatedToolNames(isLazy, activeSpecs), trace, pausesSoFar + 1, llmTaskIndex,
                                    toolSources, effectiveToolApprovals, transcriptMaxBytes, ruleByCallId, governingRule,
                                    pinResolvers);
                            memory.setHitlPendingToolCalls(batch);
                            ToolApprovalGateSupport.incrementToolPauseCount(memory, pausesSoFar);
                            var pause = new ToolApprovalRequiredException(
                                    ToolApprovalGateSupport.buildPauseReason(effectiveToolApprovals, gateResult, governingRule), batch);
                            // What this run spent up to the pause — its model rounds and its
                            // tool calls (for a resume, from before the approved calls).
                            // LlmTask records it on the step as the pause passes, so the
                            // resume's spend checks count it.
                            Map<String, Object> usageSoFar = tokenHolder[0] != null ? ToolContextBudget.tokenUsageMap(tokenHolder[0]) : null;
                            pause.addSpend(usageSoFar, runSpend.getAsDouble());
                            if (answerCall.answered()) {
                                // The turn did not end: the reply sat next to a call that now
                                // waits for a human. It is dropped (it is not in the frozen
                                // transcript); the resumed loop offers the reply tool again.
                                AnswerTool.count(AnswerTool.OUTCOME_DISCARDED);
                            }
                            throw pause;
                        }
                    }

                    // Execute the allowed calls (was: aiMessage.toolExecutionRequests()).
                    // When the gate is inert, gateResult.allowed() == the full batch.
                    for (ToolExecutionRequest toolRequest : gateResult.allowed()) {
                        // Cooperative cancellation before each (potentially side-effectful) tool.
                        // Thread.interrupted() clears the flag so it cannot leak to later work.
                        if (Thread.interrupted()) {
                            throw new LifecycleException("Agent execution cancelled (interrupted) before tool: " + toolRequest.name());
                        }
                        if (memory.isCancelled()) {
                            throw new LifecycleException("Agent execution cancelled before tool: " + toolRequest.name());
                        }

                        // "An agent may not send a request to its own conversation" is
                        // an ABSOLUTE rule, and this loop is the one place it was not
                        // enforced: a call the gate lets through live (ungated method,
                        // or the whole gate inert) executed with no check anywhere.
                        // The resume path has its own copy; a rule enforced only where
                        // approvals funnel is a rule that vanishes with the gate.
                        if (refusedAsSelfTargeted(toolRequest, aiMessage, setup, conversationId, currentMessages, trace,
                                exchangeRecorder)) {
                            continue;
                        }

                        boolean executed = executeGuarded(repeatGuard, toolRequest, aiMessage, currentMessages, trace, toolSources, exchangeRecorder,
                                () -> executeToolCall(toolRequest, memory, trace, toolExecutors, toolRateLimits, toolCanonicalNames,
                                        toolSources, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching,
                                        enableCostTracking, task, isLazy, builtInSpecs, activeSpecs, runSpend));
                        if (executed) {
                            allowedCallObserver.afterAllowed(memory, toolRequest, toolSources, setup.toolEndpoints(), effectiveToolApprovals,
                                    trace);
                        }
                    }
                    admission.answerRefused(aiMessage, currentMessages, trace, exchangeRecorder);

                    // The other calls of a response that also called the reply tool have
                    // run (none paused — a pause throws above and drops the reply): the
                    // reply is the turn's answer.
                    if (answerCall.answered()) {
                        return answerWith(answerCall, answerToolName, currentMessages, trace, transcriptOut);
                    }

                    // The model keeps re-sending calls the guard has to refuse: stop
                    // asking it. Every call of this round has its result, so the
                    // transcript is well-formed; completed calls are kept as they are.
                    if (repeatGuard != null && repeatGuard.shouldEscalate()) {
                        LOGGER.warnf("Tool repeat guard stopped the loop after %d refused repeated calls (conversation %s)",
                                repeatGuard.refusals(), sanitize(conversationId));
                        trace.add(Map.of("type", "tool_repeat_stopped", "refusals", repeatGuard.refusals()));
                        return finish(currentMessages, transcriptOut, ToolRepeatGuard.stoppedMessage(repeatGuard.refusals()));
                    }
                } else if (answerCall.answered()) {
                    return answerWith(answerCall, answerToolName, currentMessages, trace, transcriptOut);
                } else {
                    if (answerToolName != null) {
                        // answerMode "tool", answered in plain text: the text goes on to the
                        // convertToObject parse and its invalid-JSON handling, as in "text" mode.
                        AnswerTool.count(AnswerTool.OUTCOME_TEXT);
                    }
                    return finish(currentMessages, transcriptOut, aiMessage.text());
                }
            }

            // Loop exhausted its iteration budget. The last message is usually the
            // model's final AiMessage; on resume with a spent budget it may instead be
            // a verdict-applied tool result — guard the cast either way.
            //
            // The fallback is user-visible: it becomes the turn's whole answer when
            // the model was still mid-tool-call at the cap. The old bare "Max tool
            // iterations reached" read like an internal error code and hid the two
            // facts the user actually needs — the tool calls that already ran HAVE
            // taken effect (nothing rolls back), and the work can be resumed by
            // asking to continue. Observed live: the Platform Operator building an
            // agent died at the cap after 22 calls, with real resources created and a
            // four-word answer.
            if (answerToolName != null) {
                // answerMode "tool" without a reply: whatever text comes back below goes
                // through the invalid-JSON handling — never silently lost.
                AnswerTool.count(AnswerTool.OUTCOME_NO_REPLY);
            }
            ChatMessage last = currentMessages.get(currentMessages.size() - 1);
            if (last instanceof AiMessage aiLast && aiLast.text() != null) {
                return finish(currentMessages, transcriptOut, aiLast.text());
            }
            return finish(currentMessages, transcriptOut, iterationBudgetSpentMessage(maxIterations));
        } catch (ToolApprovalRequiredException e) {
            // The pause signal travels up unchanged; it carries its own spend.
            throw e;
        } catch (LifecycleException.LifecycleInterruptedException e) {
            // Deliberately NOT unchanged: see wrapLoopFailure.
            throw withRunSpend(wrapLoopFailure(e), tokenHolder, prices, runSpend);
        } catch (LifecycleException e) {
            throw withRunSpend(e, tokenHolder, prices, runSpend);
        } catch (Exception e) {
            throw withRunSpend(wrapLoopFailure(e), tokenHolder, prices, runSpend);
        }
    }

    /**
     * Attaches what this run spent before it failed to the failure
     * ({@link UnrecordedSpend}): the rounds the model answered and the tools that
     * ran are paid for whether or not the run returns.
     */
    private static <T extends Exception> T withRunSpend(T failure, TokenUsage[] tokenHolder, ModelPrices prices, DoubleSupplier runSpend) {
        Map<String, Object> usage = tokenHolder[0] != null ? ToolContextBudget.tokenUsageMap(tokenHolder[0]) : null;
        double llmCost = prices.cost(usage);
        return UnrecordedSpend.attach(failure, usage, llmCost, runSpend.getAsDouble() - llmCost);
    }

    /**
     * The absolute rule that an agent may not send a request into its own
     * conversation, for a call about to execute on the live path. When the call
     * targets this conversation it is answered with a {@code NOT_EXECUTED} result
     * and traced, and the caller skips it.
     * <p>
     * One copy for both live-path sites — the ungated half of a batch that is about
     * to pause, and the main execution loop. The resume path enforces the same rule
     * through {@link ToolLoopResumer#targetsOwnConversation}, with an audit entry
     * of its own because a human approved the call.
     *
     * @return true when the call was refused
     */
    private static boolean refusedAsSelfTargeted(ToolExecutionRequest request, AiMessage call, AgentOrchestrator.ToolSetup setup,
                                                 String conversationId, List<ChatMessage> currentMessages,
                                                 List<Map<String, Object>> trace, ToolExchangeRecorder exchangeRecorder) {
        String selfTargeted = ToolLoopResumer.targetsOwnConversationLive(request, setup.toolRequestResolvers(), conversationId);
        if (selfTargeted == null) {
            return false;
        }
        LOGGER.warnf("Refusing ungated tool '%s': %s", sanitize(request.name()), selfTargeted);
        addResult(currentMessages, call, request, SELF_CONVERSATION_REFUSAL, exchangeRecorder);
        trace.add(Map.of("type", "hitl_self_conversation", "tool", request.name(), "detail", selfTargeted));
        return true;
    }

    /**
     * Answers every call to a lazily-managed tool that is not active with
     * {@link LazyToolCatalog#NOT_ACTIVATED_REFUSAL} and returns the rest. A refused
     * call reaches no gate, no executor and no mock; it is traced as
     * {@code tool_not_activated}.
     */
    private static List<ToolExecutionRequest> refuseUnactivated(List<ToolExecutionRequest> admitted, List<ToolSpecification> lazySpecs,
                                                                List<ToolSpecification> activeSpecs, Map<String, String> toolSources,
                                                                AiMessage call, List<ChatMessage> currentMessages,
                                                                List<Map<String, Object>> trace, ToolExchangeRecorder exchangeRecorder) {
        List<ToolExecutionRequest> kept = null;
        for (int i = 0; i < admitted.size(); i++) {
            ToolExecutionRequest request = admitted.get(i);
            if (!LazyToolCatalog.isUnactivated(request, lazySpecs, activeSpecs)) {
                if (kept != null) {
                    kept.add(request);
                }
                continue;
            }
            if (kept == null) {
                kept = new ArrayList<>(admitted.subList(0, i));
            }
            String source = toolSources == null ? null : toolSources.get(request.name());
            LOGGER.debugf("Refusing call to not-yet-activated LAZY tool '%s'", sanitize(request.name()));
            LazyToolCatalog.countRefusal(source);
            addResult(currentMessages, call, request, LazyToolCatalog.NOT_ACTIVATED_REFUSAL, exchangeRecorder);
            trace.add(Map.of("type", "tool_not_activated", "tool", request.name()));
        }
        return kept != null ? kept : admitted;
    }

    /**
     * Answers, with a {@code NOT_EXECUTED} result listing each violation, every
     * call whose executor is a {@link ToolArgumentValidator} and finds its
     * arguments invalid, and returns the rest in order. Runs before the approval
     * gate, so a refused call is never paused for a human and never executed; the
     * model reads the violations and corrects the call on its next iteration.
     * <p>
     * Traced as {@code tool_error} with {@code error: "argument_schema"} and
     * counted in {@code eddi.tool.arguments.refused}. A validator that throws
     * refuses the call (fail closed): a call that could not be checked is never
     * shown to the gate as if it had passed.
     */
    static List<ToolExecutionRequest> refuseInvalidArguments(List<ToolExecutionRequest> admitted, Map<String, ToolExecutor> toolExecutors,
                                                             Map<String, String> toolSources, List<ChatMessage> currentMessages,
                                                             AiMessage aiMessage, List<Map<String, Object>> trace,
                                                             ToolExchangeRecorder exchangeRecorder) {
        if (admitted.isEmpty() || toolExecutors == null) {
            return admitted;
        }
        List<ToolExecutionRequest> valid = null;
        for (int index = 0; index < admitted.size(); index++) {
            ToolExecutionRequest request = admitted.get(index);
            List<String> violations = List.of();
            if (toolExecutors.get(request.name()) instanceof ToolArgumentValidator validator) {
                try {
                    violations = validator.argumentViolations(request);
                } catch (RuntimeException e) {
                    // Fail closed: a call whose arguments could not be checked is not
                    // shown to the gate unchecked.
                    LOGGER.warnf("Argument check of tool '%s' failed (%s); the call is refused", sanitize(request.name()),
                            e.getClass().getSimpleName());
                    violations = List.of("$: the arguments of this call could not be checked; the call was refused");
                }
            }
            if (violations == null || violations.isEmpty()) {
                if (valid != null) {
                    valid.add(request);
                }
                continue;
            }
            if (valid == null) {
                valid = new ArrayList<>(admitted.subList(0, index));
            }
            LOGGER.infof("Refusing tool '%s' before the approval gate: %d argument schema violation(s)", sanitize(request.name()),
                    violations.size());
            addResult(currentMessages, aiMessage, request, ToolArgumentSchemaValidator.refusal(violations), exchangeRecorder);
            Map<String, Object> step = new HashMap<>();
            step.put("type", "tool_error");
            step.put("tool", request.name());
            step.put("error", "argument_schema");
            step.put("violations", List.copyOf(violations));
            trace.add(step);
            String source = toolSources != null ? toolSources.get(request.name()) : null;
            Metrics.globalRegistry.counter("eddi.tool.arguments.refused", "source", source != null ? source : "unknown").increment();
        }
        return valid == null ? admitted : valid;
    }

    /** Tool result for a call refused by {@link #refusedAsSelfTargeted}. */
    static final String SELF_CONVERSATION_REFUSAL = "{\"status\":\"NOT_EXECUTED\",\"reason\":\"an agent may not send a request to its own conversation\"}";

    /** Default {@code maxToolIterations}. */
    static final int DEFAULT_MAX_TOOL_ITERATIONS = 10;

    /**
     * Engine ceiling on {@code maxToolIterations}. Configuration chooses a value
     * below it; it cannot buy an unbounded loop.
     */
    static final int MAX_TOOL_ITERATIONS_CEILING = 100;

    /** Default {@code maxToolCallsPerIteration}. */
    static final int DEFAULT_MAX_TOOL_CALLS_PER_ITERATION = 20;

    /** Default {@code maxToolCallsPerTurn}. */
    static final int DEFAULT_MAX_TOOL_CALLS_PER_TURN = 100;

    /** {@code maxToolIterations}, defaulted and clamped to the engine ceiling. */
    static int effectiveMaxToolIterations(LlmConfiguration.Task task) {
        Integer configured = task.getMaxToolIterations();
        if (configured == null) {
            return DEFAULT_MAX_TOOL_ITERATIONS;
        }
        if (configured > MAX_TOOL_ITERATIONS_CEILING) {
            LOGGER.warnf("maxToolIterations=%d exceeds the engine ceiling of %d; using %d", configured, MAX_TOOL_ITERATIONS_CEILING,
                    MAX_TOOL_ITERATIONS_CEILING);
            return MAX_TOOL_ITERATIONS_CEILING;
        }
        return configured;
    }

    /**
     * A call cap as configured: null means the default, and a non-positive value
     * (the documented {@code -1}) means no cap.
     */
    static int effectiveCallCap(Integer configured, int defaultCap) {
        if (configured == null) {
            return defaultCap;
        }
        return configured > 0 ? configured : Integer.MAX_VALUE;
    }

    /** Tool calls the trace already records for this turn. */
    static int countToolCalls(List<Map<String, Object>> trace) {
        int count = 0;
        for (Map<String, Object> step : trace) {
            if ("tool_call".equals(step.get("type"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * The outcome of applying the call caps to one model response: the calls to
     * run, in the order the model listed them, and the tail that is refused.
     *
     * @param admitted
     *            calls within both caps
     * @param refused
     *            calls past a cap, never shown to the gate or an executor
     * @param refusal
     *            the {@code NOT_EXECUTED} result each refused call receives
     * @param limit
     *            the setting that refused them, for the trace
     */
    record CallAdmission(List<ToolExecutionRequest> admitted, List<ToolExecutionRequest> refused, String refusal, String limit) {

        /**
         * Answers every refused call and traces it as {@code tool_call_capped}. Called
         * once the admitted calls have their results, so the results stay in call
         * order.
         */
        void answerRefused(AiMessage call, List<ChatMessage> currentMessages, List<Map<String, Object>> trace,
                           ToolExchangeRecorder exchangeRecorder) {
            for (ToolExecutionRequest skipped : refused) {
                addResult(currentMessages, call, skipped, refusal, exchangeRecorder);
                trace.add(Map.of("type", "tool_call_capped", "tool", skipped.name(), "limit", limit));
            }
        }
    }

    /**
     * Refuses, before a human is asked, every gated call whose {@code toolQuotas}
     * allowance is already spent. Approving it would be an approval spent on
     * nothing: the quota is applied again when the approved call runs, and it is
     * refused then. The check is a read — nothing is counted here; the count
     * happens when the call actually executes, on the live path and on the HITL
     * resume path alike.
     * <p>
     * Deliberately only "already exhausted": with one call left and two gated calls
     * proposed, both go to the human, because the first may be rejected and leave
     * the second its slot. The second is refused at execution if it did not. A
     * store that cannot answer refuses the call too (fail closed) — it would be
     * refused at execution anyway.
     */
    private ToolApprovalGate.GateResult refuseExhaustedGatedCalls(ToolApprovalGate.GateResult gateResult, LlmConfiguration.Task task,
                                                                  IConversationMemory memory, Map<String, String> toolCanonicalNames,
                                                                  List<ChatMessage> currentMessages, AiMessage aiMessage,
                                                                  List<Map<String, Object>> trace, ToolExchangeRecorder exchangeRecorder) {
        ToolQuotaService quotaService = toolExecutionService.getQuotaService();
        if (task.getToolQuotas() == null || quotaService == null || memory == null || gateResult.gated().isEmpty()) {
            return gateResult;
        }
        List<ToolExecutionRequest> stillGated = new ArrayList<>();
        for (ToolExecutionRequest req : gateResult.gated()) {
            String canonicalName = ToolNameResolver.canonical(req.name(), toolCanonicalNames);
            var refusal = quotaService.precheck(task.getToolQuotas(), req.name(), canonicalName, memory);
            if (refusal.isEmpty()) {
                stillGated.add(req);
                continue;
            }
            addResult(currentMessages, aiMessage, req, refusal.get(), exchangeRecorder);
            Map<String, Object> step = new HashMap<>();
            step.put("type", "tool_error");
            step.put("tool", req.name());
            withCallId(step, req);
            step.put("error", "tool_quota_refused");
            trace.add(step);
        }
        return stillGated.size() == gateResult.gated().size()
                ? gateResult
                : new ToolApprovalGate.GateResult(stillGated, gateResult.allowed(), gateResult.gateReasonByCallId());
    }

    /**
     * Refuses, before a human is asked, every gated call that is certain to be
     * refused when it runs: one whose rendered request carries a credential
     * reference conversation data supplied
     * ({@link ConfigReferenceRefusedException}, the {@code ConfigReferenceGuard}
     * rule).
     * <p>
     * <b>Why here.</b> Gate time already resolves each gated http call to pin it
     * ({@code ToolApprovalGateSupport#pinResolvedRequest}), and that resolution
     * runs the same request build — the guard included — as execution. But the
     * pinning step is deliberately never allowed to fail a pause, so the guard's
     * refusal was swallowed there ("approved unpinned") and the approver was shown
     * a card for a call that was refused the instant they approved it: an approval
     * spent on nothing, and a model told "refused" only afterwards. Refusing first
     * costs the human nothing, and the refusal — which names the reference and the
     * field, never a value — goes back to the model as the tool result so it can
     * correct the request instead of waiting on a decision.
     * <p>
     * Only {@link ConfigReferenceRefusedException} counts. Any other failure to
     * resolve is not known to be deterministic (and its message may quote the
     * material being rendered), so those calls stay gated and are approved
     * unpinned, exactly as before.
     */
    private static Screened refuseDoomedGatedCalls(ToolApprovalGate.GateResult gateResult,
                                                   Map<String, ToolRequestResolver> resolvers,
                                                   List<ChatMessage> currentMessages, AiMessage aiMessage,
                                                   List<Map<String, Object>> trace,
                                                   ToolExchangeRecorder exchangeRecorder) {
        if (resolvers == null || resolvers.isEmpty() || gateResult.gated().isEmpty()) {
            return new Screened(gateResult, resolvers);
        }
        List<ToolExecutionRequest> stillGated = new ArrayList<>();
        // Each gated call is resolved ONCE: the pin step that builds the pending batch
        // is handed this outcome instead of building the same request a second time.
        // A failure is remembered too and rethrown there, so the pin step still treats
        // it exactly as before — a failure that may never stop a pause.
        Map<ToolExecutionRequest, Object> outcomes = new IdentityHashMap<>();
        for (ToolExecutionRequest req : gateResult.gated()) {
            var resolver = resolvers.get(req.name());
            if (resolver == null) {
                stillGated.add(req);
                continue;
            }
            try {
                outcomes.put(req, resolver.resolve(req));
                stillGated.add(req);
            } catch (ConfigReferenceRefusedException refused) {
                LOGGER.warnf("Refusing gated tool '%s' before asking for approval: a credential reference in its request came "
                        + "from conversation data.", sanitize(req.name()));
                addResult(currentMessages, aiMessage, req, errorJson(refused.getMessage()), exchangeRecorder);
                Map<String, Object> step = new HashMap<>();
                step.put("type", "tool_error");
                step.put("tool", req.name());
                withCallId(step, req);
                step.put("error", "credential_reference_refused");
                trace.add(step);
            } catch (Exception e) {
                // Not known to be deterministic: leave it to the pause, which pins it
                // best-effort and approves it unpinned.
                outcomes.put(req, e);
                stillGated.add(req);
            }
        }
        var screenedGate = stillGated.size() == gateResult.gated().size()
                ? gateResult
                : new ToolApprovalGate.GateResult(stillGated, gateResult.allowed(), gateResult.gateReasonByCallId());
        Map<String, ToolRequestResolver> memoised = new HashMap<>();
        for (var entry : resolvers.entrySet()) {
            ToolRequestResolver original = entry.getValue();
            memoised.put(entry.getKey(), req -> {
                Object outcome = outcomes.get(req);
                if (outcome == null) {
                    return original.resolve(req);
                }
                if (outcome instanceof RuntimeException failure) {
                    throw failure;
                }
                if (outcome instanceof LifecycleException failure) {
                    throw failure;
                }
                return (ResolvedRequest) outcome;
            });
        }
        return new Screened(screenedGate, memoised);
    }

    /**
     * The gate result after screening, and the resolvers to pin it with — the same
     * ones, except that a call already resolved during screening answers from that
     * resolution.
     */
    private record Screened(ToolApprovalGate.GateResult gateResult, Map<String, ToolRequestResolver> resolvers) {
    }

    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    /** One JSON object with an {@code error} string, escaped. */
    private static String errorJson(String message) {
        try {
            return ERROR_MAPPER.writeValueAsString(Map.of("error", message != null ? message : "refused"));
        } catch (Exception e) {
            return "{\"error\":\"refused\"}";
        }
    }

    /**
     * Splits one model response at the call caps. Nothing is appended here — see
     * {@link CallAdmission#answerRefused}.
     */
    static CallAdmission admitWithinCallCaps(AiMessage aiMessage, int perResponseCap, int perTurnCap, int toolCallsThisTurn) {
        List<ToolExecutionRequest> requested = aiMessage.toolExecutionRequests();
        long turnRoom = perTurnCap == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0L, (long) perTurnCap - toolCallsThisTurn);
        int room = (int) Math.min(perResponseCap, turnRoom);
        if (requested.size() <= room) {
            return new CallAdmission(requested, List.of(), null, null);
        }
        boolean turnBound = turnRoom < perResponseCap;
        String limit = turnBound
                ? "the limit of " + perTurnCap + " tool calls per turn (maxToolCallsPerTurn) is reached"
                : "at most " + perResponseCap + " tool calls are executed per response (maxToolCallsPerIteration)";
        String refusal = "{\"status\":\"NOT_EXECUTED\",\"reason\":\"" + limit
                + "; this call was not run. Do not repeat it in this turn — answer with the results you have.\"}";
        LOGGER.warnf("Model requested %d tool calls in one response; executing %d, refusing the rest: %s", requested.size(), room,
                limit);
        return new CallAdmission(requested.subList(0, room), requested.subList(room, requested.size()), refusal,
                turnBound ? "maxToolCallsPerTurn" : "maxToolCallsPerIteration");
    }

    /**
     * The loop's first model request failed, so no tool of this run executed.
     * Carries the original failure's message and wraps it as the cause, so every
     * caller that only sees a {@link LifecycleException} is unaffected.
     */
    static final class FailedBeforeToolsException extends LifecycleException {
        FailedBeforeToolsException(LifecycleException failure) {
            super(failure.getMessage(), failure);
        }
    }

    /**
     * Appends one tool result to the loop's transcript and, in the same step,
     * records the call and result as a completed pair — the only moment a call
     * becomes part of the exchange a cancelled run leaves behind.
     */
    private static void addResult(List<ChatMessage> currentMessages, AiMessage call, ToolExecutionRequest request, String result,
                                  ToolExchangeRecorder exchangeRecorder) {
        var message = ToolExecutionResultMessage.from(request, result);
        currentMessages.add(message);
        if (exchangeRecorder != null) {
            exchangeRecorder.record(call, request, message);
        }
    }

    /**
     * Ends the loop with the reply tool's arguments as the answer. The answer is
     * appended as the final assistant message — the shape a text answer has — so
     * the tool exchange handed on (a cascade, a format re-ask) holds no call
     * without a result, and a re-ask transcript ends where the answer begins.
     */
    private static String answerWith(AnswerTool.Extraction answerCall, String answerToolName, List<ChatMessage> currentMessages,
                                     List<Map<String, Object>> trace, List<ChatMessage> transcriptOut) {
        String answer = answerCall.answerText();
        trace.add(AnswerTool.traceStep(answerToolName));
        AnswerTool.count(AnswerTool.OUTCOME_REPLY);
        currentMessages.add(AiMessage.from(answer));
        return finish(currentMessages, transcriptOut, answer);
    }

    private static String finish(List<ChatMessage> currentMessages, List<ChatMessage> transcriptOut, String response) {
        if (transcriptOut != null) {
            transcriptOut.addAll(currentMessages);
        }
        return response;
    }

    /**
     * The exception shape a failure outside the model call used to have when the
     * whole loop ran inside {@code executeWithRetry}, which wrapped every
     * non-retryable failure as {@code "Agent execution failed: <message>"}. Kept so
     * callers see the same terminal state: in particular an abandoned-thread
     * {@link LifecycleException.LifecycleInterruptedException} must reach the live
     * path as a plain {@link LifecycleException}, which settles the turn to ERROR,
     * rather than as the interrupted signal the pipeline treats as "the caller
     * walked away".
     */
    private static LifecycleException wrapLoopFailure(Exception e) {
        return new LifecycleException("Agent execution failed: " + e.getMessage(), e);
    }

    /**
     * Holds the price of a tool call against the tenant's monthly budget (the
     * opt-in hard ceiling). The hold stays with the turn and is given back once the
     * turn is charged, so the price is never unaccounted for between the call and
     * the charge.
     *
     * @return why the call may not run, or {@code null} when it may (also when
     *         reservations are off or the tool is free)
     */
    private String holdToolPrice(IConversationMemory memory, ToolInvocation invocation) {
        if (tenantQuotaService == null || memory == null || !tenantQuotaService.costReservationEnabled()) {
            return null;
        }
        double price = ToolCostTracker.priceOf(invocation);
        if (!(price > 0.0)) {
            return null;
        }
        try {
            QuotaCheckResult held = tenantQuotaService.holdForCall(tenantQuotaService.getDefaultTenantId(), memory.getTenantCostHolds(), price);
            if (held.allowed()) {
                return null;
            }
            LOGGER.warnf("Tenant cost reservation refused tool call '%s': %s", sanitize(invocation.dispatchName()), held.reason());
            return held.reason();
        } catch (RuntimeException e) {
            return LlmSpendGate.tenantCheckFailed(null, memory.getConversationId(), "tool call", e);
        }
    }

    /**
     * The answer of a tool loop stopped by the conversation's cost ceiling. Like
     * {@link #iterationBudgetSpentMessage}, it says that what already ran has taken
     * effect.
     */
    static String costCeilingReachedMessage() {
        return "I stopped before finishing because this conversation reached its cost limit. Everything I already did has "
                + "taken effect — completed calls are not rolled back.";
    }

    /**
     * Runs one tool call through the repeat guard and appends its result. A call
     * the guard refuses is answered with a {@code NOT_EXECUTED} result and traced
     * as {@code tool_repeat_refused}; it never reaches an executor. Otherwise the
     * call runs, its RAW result is classified, and the outcome is recorded. With no
     * guard this is exactly "run it and append the governed result".
     * <p>
     * The one place both live execution sites share, so a rule enforced on the
     * ungated half of a gated batch cannot be missing from the main loop.
     *
     * @return true only when the call really ran: a repeat-guard refusal or a
     *         {@code notExecuted} pipeline outcome returns false, so an observer of
     *         allowed calls never correlates a stale earlier result of the same
     *         tool
     */
    private static boolean executeGuarded(ToolRepeatGuard guard, ToolExecutionRequest request, AiMessage call,
                                          List<ChatMessage> currentMessages, List<Map<String, Object>> trace,
                                          Map<String, String> toolSources, ToolExchangeRecorder exchangeRecorder,
                                          Supplier<ToolCallResult> execution) {
        // A nameless call (the approval gate sends it through) has no identity to track
        // and no name for a trace entry; it stays on the unguarded path and degrades to
        // "tool not found" as before.
        if (guard == null || request.name() == null) {
            ToolCallResult unguarded = execution.get();
            addResult(currentMessages, call, request, unguarded.governed(), exchangeRecorder);
            return !unguarded.notExecuted();
        }
        ToolRepeatGuard.Refusal refusal = guard.check(request.name(), request.arguments());
        if (refusal != null) {
            LOGGER.warnf("Refusing repeated tool call '%s' (%s): %s", sanitize(request.name()), refusal.reasonCode(), refusal.text());
            addResult(currentMessages, call, request, ToolRepeatGuard.refusalEnvelope(refusal), exchangeRecorder);
            trace.add(Map.of("type", "tool_repeat_refused", "tool", request.name(), "lastOutcome", refusal.lastOutcome().name(), "reason",
                    refusal.reasonCode()));
            String source = toolSources == null ? null : toolSources.get(request.name());
            Metrics.globalRegistry.counter("eddi.tool.repeat.refused", "source", source != null ? source : "unknown", "reason",
                    refusal.reasonCode()).increment();
            return false;
        }
        ToolCallResult result = execution.get();
        ToolOutcomeClassifier.Outcome outcome = result.notExecuted()
                ? ToolOutcomeClassifier.Outcome.NOT_EXECUTED
                : ToolOutcomeClassifier.classify(result.raw());
        guard.record(request.name(), request.arguments(), outcome, result.raw());
        addResult(currentMessages, call, request, result.governed(), exchangeRecorder);
        return !result.notExecuted();
    }

    /**
     * The turn's answer when the tool loop hits its iteration cap mid-work. Kept
     * honest and actionable rather than apologetic: what stopped, what state the
     * work is in, and how to go on. The cap itself is configurable per task
     * ({@code maxToolIterations}) — mentioning it here is deliberate, so an agent
     * designer seeing this repeatedly knows which knob exists.
     */
    static String iterationBudgetSpentMessage(int maxIterations) {
        return "I stopped after reaching the limit of " + maxIterations + " tool-calling rounds for a single turn, "
                + "before finishing the task. Everything I already did has taken effect — completed calls are not "
                + "rolled back. Say \"continue\" and I will pick up where I stopped. If this task regularly needs "
                + "more rounds, the agent's maxToolIterations setting can be raised.";
    }

    /**
     * Runs the full per-request pipeline (auto-checkpoint, trace entry,
     * per-conversation budget check, tenant cost budget check, executor dispatch
     * via {@link ToolExecutionService}, response truncation, result trace, LAZY
     * activation) and RETURNS the resulting tool-result string WITHOUT appending it
     * to any message list. The live loop appends the raw result; the resume path
     * needs the string to journal it and to build the amended-args envelope, so it
     * appends its own (possibly-enveloped) message. This is the single shared
     * per-request pipeline for both paths — the auto-checkpoint fires here (and
     * only here) so replayed/outcome-unknown resume calls never re-checkpoint.
     */
    String executeSingleToolCallResult(ToolExecutionRequest toolRequest, IConversationMemory memory,
                                       List<Map<String, Object>> trace,
                                       Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                                       Map<String, String> toolCanonicalNames, Map<String, String> toolSources,
                                       int defaultRateLimit, Double maxBudget, String conversationId,
                                       boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                                       LlmConfiguration.Task task, boolean isLazy,
                                       List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs) {
        return executeToolCall(toolRequest, memory, trace, toolExecutors, toolRateLimits, toolCanonicalNames, toolSources, defaultRateLimit,
                maxBudget, conversationId, enableRateLimiting, enableCaching, enableCostTracking, task, isLazy, builtInSpecs, activeSpecs,
                NO_RUN_SPEND).governed();
    }

    /**
     * One tool call's result in both forms the loop needs.
     *
     * @param governed
     *            what the model is shown: truncated, and wrapped by the tool-result
     *            guardrail
     * @param raw
     *            what the tool returned, before truncation and before the guardrail
     *            envelope — the form {@link ToolOutcomeClassifier} must see,
     *            because the envelope hides the shapes it recognises
     * @param notExecuted
     *            true when the loop itself refused the call (budget, tenant quota,
     *            expired turn deadline): the tool never ran, so the call says
     *            nothing about whether it would succeed
     */
    record ToolCallResult(String governed, String raw, boolean notExecuted) {
        ToolCallResult(String refusal) {
            this(refusal, refusal, true);
        }
    }

    /**
     * The spend side of a tool result's {@code summarize} truncation: admitted
     * while the conversation's cost ceiling and the tenant budget allow another
     * paid call (with this run's unrecorded spend counted), and charged to the
     * conversation as a priced tool call ({@code summarizeToolResponse}) — so it
     * lands in the run's tool cost, {@code audit:cost} and
     * {@code maxBudgetPerConversation} like any other. Priced at the task's own
     * rates: the summarizer runs on the task's provider, and no separate price is
     * configured for it.
     */
    private ToolResponseTruncator.SummarizerSpend summarizerSpend(IConversationMemory memory, LlmConfiguration.Task task,
                                                                  String conversationId, boolean enableCostTracking, DoubleSupplier runSpend) {
        return new ToolResponseTruncator.SummarizerSpend() {
            @Override
            public boolean admit() {
                // Refused (fail closed on a tenant check that cannot answer): the result
                // is truncated.
                return LoopSideCallSpend.refusal(memory, tenantQuotaService, runSpend != null ? runSpend.getAsDouble() : 0.0) == null;
            }

            @Override
            public void charge(Map<String, Object> tokenUsage) {
                LoopSideCallSpend.charge(toolExecutionService, conversationId, enableCostTracking, SUMMARIZER_DISPATCH_NAME,
                        ModelPrices.forToolSummarizer(task).cost(tokenUsage));
            }
        };
    }

    /**
     * The spend checks of a {@code toolCallGuardrails} model call (a judge or a
     * classifier on a tool call's arguments): admitted and charged like the
     * summarizer's ({@link LoopSideCallSpend}), under
     * {@link ToolCallGuardrails#DISPATCH_NAME}.
     */
    PaidCallGate sideCallSpend(IConversationMemory memory, String conversationId, boolean enableCostTracking, DoubleSupplier runSpend) {
        return LoopSideCallSpend.gate(memory, tenantQuotaService, toolExecutionService, conversationId, enableCostTracking, runSpend,
                ToolCallGuardrails.DISPATCH_NAME);
    }

    /**
     * The tool name a {@code summarize} truncation's model call is charged under.
     */
    static final String SUMMARIZER_DISPATCH_NAME = "summarizeToolResponse";

    /**
     * Unsettled spend of a call made outside a running loop (a resume's approved
     * call).
     */
    static final DoubleSupplier NO_RUN_SPEND = () -> 0.0;

    /**
     * {@link #executeSingleToolCallResult} with the raw result kept alongside the
     * governed one, and with the spend of the loop run making the call.
     *
     * @param runSpend
     *            what the calling loop run has spent and not yet recorded — the
     *            tenant budget is checked against it as well, as
     *            {@link LlmSpendGate} checks every other paid call of a turn
     */
    ToolCallResult executeToolCall(ToolExecutionRequest toolRequest, IConversationMemory memory,
                                   List<Map<String, Object>> trace,
                                   Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                                   Map<String, String> toolCanonicalNames, Map<String, String> toolSources,
                                   int defaultRateLimit, Double maxBudget, String conversationId,
                                   boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                                   LlmConfiguration.Task task, boolean isLazy,
                                   List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs, DoubleSupplier runSpend) {
        // Live signals for streaming clients, announced here - the one place every
        // call passes through - so each call is closed exactly once whichever of the
        // many exits of the pipeline below it leaves by. Name, call id, status and
        // duration only: arguments and results may hold user data. The one exception
        // is what the designer allow-listed in toolDisplay.
        ConversationEventSink sink = memory != null ? memory.getEventSink() : null;
        long startedNanos = System.nanoTime();
        String status = TOOL_STATUS_ERROR;
        if (sink != null) {
            announceToolCall(sink, toolRequest, task, toolCanonicalNames);
        }
        try {
            ToolCallResult result = executeToolCallPipeline(toolRequest, memory, trace, toolExecutors, toolRateLimits, toolCanonicalNames,
                    toolSources, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching, enableCostTracking, task,
                    isLazy, builtInSpecs, activeSpecs, runSpend);
            status = toolStatusOf(result);
            return result;
        } finally {
            if (sink != null) {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
                try {
                    sink.onToolResult(toolRequest.name(), toolRequest.id(), status, durationMs);
                } catch (RuntimeException e) {
                    LOGGER.debug("Event sink failed on tool_result; ignoring", e);
                }
            }
        }
    }

    /** {@code tool_result} status of a call that ran and succeeded. */
    public static final String TOOL_STATUS_OK = "ok";
    /** A call that failed, or threw. */
    public static final String TOOL_STATUS_ERROR = "error";
    /** A call refused or skipped before the tool ran. */
    public static final String TOOL_STATUS_NOT_EXECUTED = "not_executed";
    /** A call a human declined. */
    public static final String TOOL_STATUS_REJECTED = "rejected";

    private static String toolStatusOf(ToolCallResult result) {
        if (result.notExecuted()) {
            return TOOL_STATUS_NOT_EXECUTED;
        }
        return ToolOutcomeClassifier.classify(result.raw()).isFailure() ? TOOL_STATUS_ERROR : TOOL_STATUS_OK;
    }

    /** Tells the sink a call is starting; a failing sink never breaks the loop. */
    private static void announceToolCall(ConversationEventSink sink, ToolExecutionRequest toolRequest, LlmConfiguration.Task task,
                                         Map<String, String> toolCanonicalNames) {
        try {
            ToolDisplay display = task != null
                    ? ToolDisplayResolver.resolve(task.getToolDisplay(), toolRequest.name(),
                            ToolNameResolver.canonical(toolRequest.name(), toolCanonicalNames), toolRequest.arguments())
                    : null;
            sink.onToolCall(toolRequest.name(), toolRequest.id(), display);
        } catch (RuntimeException e) {
            LOGGER.debug("Event sink failed on tool_call; ignoring", e);
        }
    }

    /**
     * Records the model's call id on a trace step, so a stored trace keeps call
     * identity.
     */
    private static void withCallId(Map<String, Object> step, ToolExecutionRequest request) {
        if (request != null && request.id() != null) {
            step.put("callId", request.id());
        }
    }

    /**
     * The per-request pipeline itself - see {@link #executeToolCall}, which wraps
     * it in the live tool_call / tool_result events.
     */
    private ToolCallResult executeToolCallPipeline(ToolExecutionRequest toolRequest, IConversationMemory memory,
                                                   List<Map<String, Object>> trace,
                                                   Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                                                   Map<String, String> toolCanonicalNames, Map<String, String> toolSources,
                                                   int defaultRateLimit, Double maxBudget, String conversationId,
                                                   boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                                                   LlmConfiguration.Task task, boolean isLazy,
                                                   List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs,
                                                   DoubleSupplier runSpend) {
        // Name-only observer for plain (non-streamed) turns, e.g. a group member's.
        // An observer must never break the tool loop.
        if (memory != null && memory.getToolCallObserver() != null) {
            try {
                memory.getToolCallObserver().accept(toolRequest.name());
            } catch (RuntimeException e) {
                LOGGER.debug("Tool-call observer failed; ignoring", e);
            }
        }

        // Auto-checkpoint before tool execution (Wave 4)
        if (memorySnapshotService != null) {
            try {
                memorySnapshotService.createCheckpoint(
                        memory, "before_tool:" + toolRequest.name(),
                        AgentOrchestrator.class.getSimpleName());
            } catch (Exception cpEx) {
                LOGGER.warnf("Auto-checkpoint failed before tool '%s': %s",
                        toolRequest.name(), cpEx.getMessage());
            }
        }

        Map<String, Object> callStep = new HashMap<>();
        callStep.put("type", "tool_call");
        callStep.put("tool", toolRequest.name());
        withCallId(callStep, toolRequest);
        // The trace is a DISPLAY/audit record (task summaries, SSE, memory,
        // traceSoFar merge on resume) — never an execution input, so it takes
        // the redacted form. Execution keeps using toolRequest directly; a
        // model that embeds a credential in its arguments must not have it
        // echoed through every surface that renders the trace.
        callStep.put("arguments", SecretRedactionFilter.redact(toolRequest.arguments()));
        trace.add(callStep);

        // Agent test harness: a test conversation's tool call is answered by its
        // suite's mock (or refused) and never reaches the outside world. A no-op
        // prefix check for every other conversation. The call is decided here and
        // recorded once. A refusal, and a mock for any tool whose executor has no use
        // for it, are recorded and return early; a mock for a MockAwareToolExecutor
        // (an httpcall tool), and a call the suite runs live, go through the executor
        // below instead — so the response handling a live call gets, saveResponse and
        // postResponse property instructions, runs on the mock — and are recorded only
        // once the budget, quota and deadline gates have let them through.
        String mockSource = toolSources == null ? null : toolSources.get(toolRequest.name());
        ToolMockRegistry.Decision testDecision = ToolMockRegistry.previewToolCall(memory, toolRequest.name(), toolRequest.arguments(),
                mockSource);
        ToolMockRegistry.MockResult mockDecision = testDecision != null ? testDecision.result() : null;
        MockAwareToolExecutor mockingExecutor = mockDecision != null && mockDecision.outcome() == ToolMockRegistry.Outcome.MOCKED
                && toolExecutors.get(toolRequest.name()) instanceof MockAwareToolExecutor aware ? aware : null;
        if (mockDecision != null && mockingExecutor == null) {
            ToolMockRegistry.recordToolCall(memory, toolRequest.name(), toolRequest.arguments(), mockSource, testDecision.outcome());
            String mockedResult = ToolMockRegistry.toolResultOf(mockDecision, mockSource);
            Map<String, Object> mockedStep = new HashMap<>();
            mockedStep.put("type", "tool_result");
            mockedStep.put("tool", toolRequest.name());
            withCallId(mockedStep, toolRequest);
            mockedStep.put("result", SecretRedactionFilter.redact(mockedResult));
            mockedStep.put("mocked", true);
            trace.add(mockedStep);
            // A mock stands in for the tool's own answer; a refusal means it never ran.
            return new ToolCallResult(mockedResult, mockedResult, ToolMockRegistry.isRefusal(mockedResult));
        }

        // Check per-conversation TOOL budget before executing tool.
        //
        // Enforcement is opt-OUT: enforceBudget on the task wins, otherwise
        // AgentOrchestrator.BUDGET_ENFORCE_DEFAULT (true unless
        // eddi.tools.budget.enforce-by-default=false). A ceiling that has been
        // opted out of is named once in a WARN rather than passing silently. Cost
        // tracking itself is unaffected by the flag.
        AgentOrchestrator.warnAboutUnenforcedBudgets(task);
        boolean enforceBudget = AgentOrchestrator.isBudgetEnforced(task);
        // An enforced ceiling is checked against the tracker, so every charge it
        // counts — the call's and a summarize truncation's — is recorded whatever
        // enableCostTracking says; otherwise turning the reporting off would leave
        // the ceiling counting nothing.
        boolean chargeTracker = enableCostTracking || (enforceBudget && maxBudget != null);
        if (enforceBudget && maxBudget != null && conversationId != null
                && !toolExecutionService.getCostTracker().isWithinBudget(conversationId, maxBudget)) {
            String budgetError = "Budget exceeded for conversation " + conversationId;
            LOGGER.warn(sanitize(budgetError));

            Map<String, Object> budgetStep = new HashMap<>();
            budgetStep.put("type", "tool_error");
            budgetStep.put("tool", toolRequest.name());
            withCallId(budgetStep, toolRequest);
            budgetStep.put("error", budgetError);
            trace.add(budgetStep);

            return new ToolCallResult("Error: " + budgetError);
        }

        // Check tenant-level monthly cost budget (MCP governance), with what this turn
        // has spent and the monthly counter has not been charged yet: the step's
        // unmetered audit:cost (earlier tasks, a pre-pause segment) and this loop
        // run's own rounds and tools. The counter is charged when the turn settles,
        // so without it every tool call of a long loop was compared against the
        // counter as it stood before the turn — the same figure LlmSpendGate.admit
        // passes for every other paid call.
        if (tenantQuotaService != null) {
            QuotaCheckResult costCheck;
            try {
                double unsettled = LlmSpendGate.unmeteredStepCost(memory) + (runSpend != null ? runSpend.getAsDouble() : 0.0);
                costCheck = tenantQuotaService.checkCostBudget(tenantQuotaService.getDefaultTenantId(), Math.max(0.0, unsettled));
            } catch (RuntimeException e) {
                // Same guard as LlmSpendGate: a check that throws is a defect, not an
                // outage (the service answers those) — refuse the tool call (fail
                // closed) under the tenant_check_error reason instead of failing the turn.
                String checkError = LlmSpendGate.tenantCheckFailed(null, conversationId, "tool call", e);
                Map<String, Object> errorStep = new HashMap<>();
                errorStep.put("type", "tool_error");
                errorStep.put("tool", toolRequest.name());
                withCallId(errorStep, toolRequest);
                errorStep.put("error", checkError);
                trace.add(errorStep);
                // The exception class stays in the trace and the log; the model gets
                // generic wording.
                return new ToolCallResult("Error: " + LlmSpendGate.TENANT_CHECK_ERROR_MESSAGE);
            }
            if (!costCheck.allowed()) {
                // The reason string already distinguishes the two, but the log line
                // in front of it did not: an operator grepping for "cost budget
                // exceeded" was told a tenant had spent its allowance when in fact
                // the quota store could not answer.
                if (costCheck.accountingUnavailable()) {
                    LOGGER.warnf("Tenant cost accounting unavailable during tool call, refusing: %s",
                            costCheck.reason());
                } else {
                    LOGGER.warnf("Tenant cost budget exceeded during tool call: %s", costCheck.reason());
                }

                Map<String, Object> quotaStep = new HashMap<>();
                quotaStep.put("type", "tool_error");
                quotaStep.put("tool", toolRequest.name());
                withCallId(quotaStep, toolRequest);
                quotaStep.put("error", costCheck.reason());
                trace.add(quotaStep);

                return new ToolCallResult("Error: " + costCheck.reason());
            }
        }

        // Execute through ToolExecutionService for rate limiting, caching, cost
        // tracking
        ToolExecutor executor = toolExecutors.get(toolRequest.name());
        String toolResult;
        boolean notExecuted = false;
        ToolQuotaGate quotaGate = null;
        if (executor != null && memory != null && memory.getTurnDeadline() != null && memory.getTurnDeadline().isExpired()) {
            Metrics.globalRegistry.counter("eddi.llm.turn.deadline.exceeded", "stage", "tool").increment();
            toolResult = "Error: the turn deadline has passed; tool '" + toolRequest.name() + "' was not run";
            notExecuted = true;
        } else if (executor != null) {
            // A built-in is DISPATCHED under its @Tool method name ("calculate") but
            // CONFIGURED under its whitelist slug ("calculator"). Resolve the slug once,
            // here, and hand both names down: only the price and the cache TTL are looked
            // up by slug, everything that identifies the individual call is not.
            String canonicalName = ToolNameResolver.canonical(toolRequest.name(), toolCanonicalNames);
            int rateLimit = resolveRateLimit(toolRateLimits, toolRequest.name(), canonicalName, defaultRateLimit);
            Double priceOverride = resolveOverride(task.getToolPricing(), toolRequest.name(), canonicalName);
            // Read off the task rather than threaded down as a parameter, like
            // toolPricing on the line above and unlike toolRateLimits — the task is
            // already here, and this method's parameter list is long enough.
            int toolTimeoutMs = resolveToolTimeoutMs(task.getToolTimeoutsMs(), toolRequest.name(), canonicalName,
                    defaultToolTimeoutMs(task));

            // Partition the tool-result cache by identity, so one user's result can never
            // be served back to another. A null tag means no usable identity was
            // available; ToolExecutionService then bypasses the cache entirely. Both
            // names go in: toolCacheScopes shares its key vocabulary with toolRateLimits
            // and toolPricing, so a slug-keyed narrowing override has to bind here too.
            //
            // Narrowed further to the tool's source and agent (M-T3), and HTTP / MCP /
            // A2A tools — which can have side effects — are not cached at all unless the
            // task names them in toolCacheScopes. See ToolCacheService#mayCache.
            String toolSource = toolSources == null ? null : toolSources.get(toolRequest.name());
            //
            // A tool object built for this conversation (attachments, artifacts, memory,
            // dynamic agents) is never cached — derived from the executor's class, see
            // ToolCacheService#isConversationBound.
            // A mocked call is never cached (a mock must not answer a later live call)
            // and costs nothing.
            boolean cacheThisCall = enableCaching && mockingExecutor == null
                    && !ToolCacheService.isConversationBound(executor)
                    && ToolCacheService.mayCache(toolSource, toolRequest.name(), canonicalName, task.getToolCacheScopes());
            String cacheScopeTag = cacheThisCall
                    ? ToolCacheService.namespacedScopeTag(
                            ToolCacheService.resolveScopeTag(toolRequest.name(), canonicalName, task.getToolCacheScopes(),
                                    task.getDefaultToolCacheScope(), memory != null ? memory.getUserId() : null, conversationId),
                            memory != null ? memory.getAgentId() : null, toolSource)
                    : null;

            // Inside a turn deadline a tool may not outlive what is left of the turn.
            TurnDeadline turnDeadline = memory != null ? memory.getTurnDeadline() : null;
            if (turnDeadline != null) {
                toolTimeoutMs = deadlineBoundedToolTimeoutMs(toolTimeoutMs, turnDeadline);
            }

            var invocation = new ToolInvocation(toolRequest.name(), canonicalName, priceOverride);
            // Per-subject quota (toolQuotas). Null when the task has none or no rule names
            // this tool — then no store is read. A mocked call never reaches the outside
            // world and is not counted. The reservation itself is made by
            // ToolExecutionService, right before the tool runs.
            ToolQuotaService quotaService = toolExecutionService.getQuotaService();
            quotaGate = mockingExecutor == null && task.getToolQuotas() != null && quotaService != null && memory != null
                    ? quotaService.gateFor(task.getToolQuotas(), invocation, memory, ToolLoopRunner::indicatesUnfinishedCall)
                    : null;
            // The source and the model's call id label the execute_tool span the
            // service opens; they ride in the OTel context because nothing but the
            // trace depends on them (see ToolCallTrace).
            try (Scope ignored = new ToolCallTrace(toolSource, toolRequest.id()).makeCurrent()) {
                final ToolMockRegistry.MockResult mock = mockingExecutor != null ? mockDecision : null;
                // Every gate passed: the call is made now, so it counts now.
                if (testDecision != null) {
                    ToolMockRegistry.recordToolCall(memory, toolRequest.name(), toolRequest.arguments(), mockSource, testDecision.outcome());
                }
                Supplier<String> execution = mock != null
                        ? () -> mockingExecutor.executeMocked(toolRequest, mock)
                        : () -> executor.execute(toolRequest, null);
                boolean trackCost = chargeTracker && mock == null;
                // Hard tenant ceiling (opt-in): a priced call holds its price against the
                // monthly budget before it runs, and is refused when the budget cannot take it.
                String holdRefusal = trackCost ? holdToolPrice(memory, invocation) : null;
                if (holdRefusal != null) {
                    toolResult = "Error: " + holdRefusal;
                    notExecuted = true;
                } else {
                    // Without a quota gate the call takes exactly the path it always took.
                    toolResult = quotaGate == null
                            ? toolExecutionService.executeToolWrapped(invocation, toolRequest.arguments(), cacheScopeTag, conversationId,
                                    execution, enableRateLimiting, cacheThisCall, trackCost, rateLimit, toolTimeoutMs)
                            : toolExecutionService.executeToolWrapped(invocation, toolRequest.arguments(), cacheScopeTag, conversationId,
                                    execution, enableRateLimiting, cacheThisCall, trackCost, rateLimit, toolTimeoutMs, quotaGate);
                    // The quota refused it: the tool never ran.
                    notExecuted = quotaGate != null && quotaGate.refused();
                }
            }
        } else {
            toolResult = "Error: Tool '" + toolRequest.name() + "' not found";
        }

        // The untruncated result, for the LAZY activation below: discover_tools
        // answers with a JSON catalogue, and truncating it first cut the JSON off
        // mid-array whenever a response limit was configured, so the parse failed and
        // NO discovered tool was ever activated.
        String untruncatedResult = toolResult;

        // Credentials out BEFORE anything leaves for a model. The result below is
        // sent to the task's (possibly hosted) model, and the summarize truncation
        // strategy sends it to a second one first. An administrator's tool reads
        // configuration documents unredacted — that is their right — but the
        // plaintext key in one is not the model provider's: until this line only
        // the display trace was filtered, so the operator showed `<REDACTED>` while
        // the provider received the key. Same scan as the trace, so the model never
        // sees more than the person watching it does. Before truncation, because a
        // cut can leave a key too short for the shape rules to recognise. The raw
        // result stays internal: LAZY activation parses it and the repeat guard
        // classifies it, and neither sends it anywhere.
        toolResult = SecretRedactionFilter.redact(toolResult);

        // Apply response truncation (MCP governance).
        //
        // The provenance envelope is added AFTER this, so the budget handed to the
        // truncator is reduced by the envelope's worst case. Otherwise a configured
        // ceiling is exceeded by every single result — small individually, kilobytes
        // across a long tool loop, and exactly the drift the ceiling exists to stop.
        toolResult = toolResponseTruncator.truncateIfNeeded(conversationId,
                toolRequest.name(), toolResult, reserveEnvelopeBudget(task.getToolResponseLimits(), task),
                task.getType(), task.getParameters(), summarizerSpend(memory, task, conversationId, chargeTracker, runSpend));

        Map<String, Object> resultStep = new HashMap<>();
        resultStep.put("type", "tool_result");
        resultStep.put("tool", toolRequest.name());
        withCallId(resultStep, toolRequest);
        // Redacted for the same reason as the call step's arguments: an API
        // response can echo a credential (its own, or one the request carried).
        // toolResult was redacted above; it is scanned again because truncation
        // (summarize strategy) can produce new text.
        resultStep.put("result", SecretRedactionFilter.redact(toolResult));
        if (mockingExecutor != null) {
            resultStep.put("mocked", true);
        }
        trace.add(resultStep);

        // LAZY mode: after discover_tools returns, activate the matching built-in
        // specs.
        //
        // Deliberately BEFORE the guardrail: discover_tools' output is an
        // EDDI-authored control message this loop parses itself, and wrapping it in
        // a provenance envelope first would make that parse fail. The envelope is
        // for the model's benefit, and the model still gets one.
        if (isLazy && "discover_tools".equals(toolRequest.name())) {
            int before = activeSpecs.size();
            activateDiscoveredTools(untruncatedResult, builtInSpecs, activeSpecs);
            LazyToolCatalog.countActivation(activeSpecs.size() - before);
            LazyToolCatalog.remember(memory, task, builtInSpecs, activeSpecs);
        }

        // Govern what comes back. Until now the comment on the live loop's caller
        // read "append the raw result verbatim", which made every tool a
        // prompt-injection channel: third-party bulk text arrived in the same
        // position as a system instruction with nothing to distinguish it.
        //
        // This is the single shared per-request pipeline, so one call here covers
        // every tool source, both the live loop and the resume path, and — because
        // the MCP resource bridge's executors return ordinary tool results —
        // resource content and listings for free.
        //
        // Applied AFTER the trace entry above on purpose: the trace is a display
        // record of what the TOOL returned, and showing an operator EDDI's own
        // envelope back would obscure that.
        String source = toolSources == null ? null : toolSources.get(toolRequest.name());
        var outcome = toolResultGuardrail.inspect(toolRequest.name(), source, toolResult,
                task.getToolResultGuardrails());
        if (!ToolResultGuardrail.ACTION_ALLOW.equals(outcome.action())) {
            Map<String, Object> guardrailStep = new HashMap<>();
            guardrailStep.put("type", "tool_result_guardrail");
            guardrailStep.put("tool", toolRequest.name());
            guardrailStep.put("action", outcome.action());
            trace.add(guardrailStep);
        }

        return new ToolCallResult(outcome.result(), untruncatedResult, notExecuted);
    }

    /**
     * Whether a tool's raw result says the call did not complete — it failed, or it
     * never ran — so that a {@code toolQuotas} reservation made for it is given
     * back. Reads the result the way the repeat guard does (HTTP status, an
     * {@code error} object, an {@code Error:} string).
     */
    static boolean indicatesUnfinishedCall(String rawResult) {
        ToolOutcomeClassifier.Outcome outcome = ToolOutcomeClassifier.classify(rawResult);
        return outcome.isFailure() || outcome == ToolOutcomeClassifier.Outcome.NOT_EXECUTED;
    }

    /**
     * A copy of the task's response limits with room left for the provenance
     * envelope.
     * <p>
     * A copy, not a mutation: {@code LlmConfiguration.Task} is shared configuration
     * read by every concurrent conversation on this agent, and shrinking its limits
     * in place would shrink them again on the next turn, and the next.
     * <p>
     * Returns the original when governance will not wrap anything, so an agent that
     * turned provenance marking off keeps exactly the ceiling it configured.
     */
    private static LlmConfiguration.ToolResponseLimits reserveEnvelopeBudget(LlmConfiguration.ToolResponseLimits limits,
                                                                             LlmConfiguration.Task task) {
        var guardrails = task.getToolResultGuardrails();
        boolean willWrap = guardrails == null
                || (!Boolean.FALSE.equals(guardrails.getEnabled()) && !Boolean.FALSE.equals(guardrails.getMarkProvenance()));
        if (!willWrap) {
            return limits;
        }
        // No limits configured means the built-in defaults (as in the truncator), and
        // the envelope's room is reserved against those too.
        limits = limits != null ? limits : new LlmConfiguration.ToolResponseLimits();
        var reserved = new LlmConfiguration.ToolResponseLimits();
        reserved.setDefaultMaxChars(reduce(limits.getDefaultMaxChars()));
        reserved.setTruncationStrategy(limits.getTruncationStrategy());
        reserved.setSummarizerModel(limits.getSummarizerModel());
        if (limits.getPerToolLimits() != null) {
            var perTool = new HashMap<String, Integer>();
            // A null entry — legal in the agent's JSON as {"fetch_page": null} — is
            // dropped rather than carried over, so the tool resolves to the default
            // ceiling. Copying it forward only preserved a key whose sole effect is
            // to say "no limit configured".
            limits.getPerToolLimits().forEach((tool, limit) -> {
                if (limit != null) {
                    perTool.put(tool, reduce(limit));
                }
            });
            reserved.setPerToolLimits(perTool);
        }
        return reserved;
    }

    /**
     * Makes room for the envelope inside one configured ceiling.
     * <p>
     * A NON-POSITIVE limit means "no limit" and must pass through untouched:
     * {@code ToolResponseTruncator.truncateIfNeeded} returns early on
     * {@code maxChars <= 0}, and {@code 0 = disabled} is the documented idiom.
     * Subtracting from it produced a negative number, the floor below then clamped
     * it to 256, and an agent that had deliberately turned truncation OFF had every
     * tool result cut to 256 characters. That omission is why this is the first
     * statement and not a footnote.
     * <p>
     * Otherwise never below a floor: a ceiling smaller than the envelope would
     * truncate to nothing.
     */
    private static int reduce(int limit) {
        if (limit <= 0) {
            return limit;
        }
        return Math.max(limit - ToolResultProvenance.MAX_ENVELOPE_CHARS, MINIMUM_TOOL_RESULT_CHARS);
    }

    /**
     * Smallest useful tool result after the envelope is reserved. Below this the
     * result is no longer an answer, so an operator who configured a tiny ceiling
     * gets a tiny answer rather than an empty one.
     */
    private static final int MINIMUM_TOOL_RESULT_CHARS = 256;

    /**
     * Per-tool execution timeout applied when a task sets none: two minutes.
     *
     * <p>
     * Far above every transport timeout the tool sources set for themselves (MCP
     * and A2A default to 30s each), so those keep reporting their own, more
     * specific errors and this only fires on a genuine hang — and far below the
     * "forever" a hung tool used to get.
     * </p>
     *
     * <p>
     * Kept equal to the initializer on {@code LlmConfiguration.Task}; see
     * {@link #defaultToolTimeoutMs} for why both exist.
     * </p>
     */
    static final int DEFAULT_TOOL_TIMEOUT_MS = 120_000;

    /**
     * Resolves the per-minute rate limit for one call: an entry keyed on the
     * dispatch name wins, then the canonical slug, then the task default.
     *
     * <p>
     * Dispatch-name-first keeps a config that pins a single method
     * ({@code {"searchNews": 5}}) more specific than one that covers the whole tool
     * ({@code {"websearch": 30}}), and is what makes the documented slug form bind
     * at all — a built-in's dispatch name never equals its slug.
     * </p>
     *
     * <p>
     * Note that only the LIMIT is slug-resolved; the bucket itself lives in
     * {@code ToolRateLimiter} under the dispatch name. {@code {"websearch": 30}}
     * therefore grants {@code searchWeb}, {@code searchNews} and
     * {@code searchWikipedia} 30 calls/min <em>each</em>, not 30 between them.
     * </p>
     */
    static int resolveRateLimit(Map<String, Integer> toolRateLimits, String dispatchName, String canonicalName,
                                int defaultRateLimit) {
        if (toolRateLimits == null) {
            return defaultRateLimit;
        }
        Integer limit = toolRateLimits.get(dispatchName);
        if (limit == null) {
            limit = toolRateLimits.get(canonicalName);
        }
        return limit != null ? limit : defaultRateLimit;
    }

    /**
     * A tool's timeout shortened to what the turn can still spare: what is left
     * after the reserve, or all of what is left when that is almost nothing. Never
     * longer than the configured timeout (a non-positive, i.e. unbounded, one takes
     * the whole budget), never below 1 ms.
     */
    static int deadlineBoundedToolTimeoutMs(int configuredMs, TurnDeadline deadline) {
        long budget = deadline.remainingAfterReserveMs();
        if (budget < 500L) {
            budget = deadline.remainingMs();
        }
        // A non-positive configured timeout means "no per-tool timeout": the turn's
        // remaining budget is then the only bound, not a 1 ms one.
        long bounded = configuredMs <= 0 ? budget : Math.min((long) configuredMs, budget);
        return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE, bounded));
    }

    /**
     * The task's default per-tool execution timeout in milliseconds, or
     * {@link #DEFAULT_TOOL_TIMEOUT_MS} when the task does not set one.
     *
     * <p>
     * A stored configuration written before the field existed deserializes it as
     * null, so the fallback here — not the field initializer on
     * {@code LlmConfiguration.Task} — is what actually decides the default for
     * every agent already in the database. The two are kept equal on purpose.
     * </p>
     */
    static int defaultToolTimeoutMs(LlmConfiguration.Task task) {
        Integer configured = task.getDefaultToolTimeoutMs();
        return configured != null ? configured : DEFAULT_TOOL_TIMEOUT_MS;
    }

    /**
     * Resolves the execution timeout for one call: an entry keyed on the dispatch
     * name wins, then the canonical slug, then the task default — the same
     * precedence as {@link #resolveRateLimit}, and for the same reason.
     *
     * <p>
     * A non-positive value (the documented {@code -1}, and {@code 0} with it) is
     * passed through unchanged: {@code ToolExecutionService} reads it as "no bound"
     * and runs the tool inline. That is what makes a per-tool entry of {@code -1}
     * an exemption for one deliberately long-running tool rather than a setting
     * that has to be applied to the whole task.
     * </p>
     */
    static int resolveToolTimeoutMs(Map<String, Integer> toolTimeoutsMs, String dispatchName, String canonicalName,
                                    int defaultToolTimeoutMs) {
        if (toolTimeoutsMs == null) {
            return defaultToolTimeoutMs;
        }
        Integer timeout = toolTimeoutsMs.get(dispatchName);
        if (timeout == null) {
            timeout = toolTimeoutsMs.get(canonicalName);
        }
        return timeout != null ? timeout : defaultToolTimeoutMs;
    }

    /**
     * Resolves an operator price override for one call, dispatch name before
     * canonical slug (same precedence as {@link #resolveRateLimit}). Returns
     * {@code null} when no override applies, leaving the default price table in
     * charge.
     */
    static Double resolveOverride(Map<String, Double> toolPricing, String dispatchName, String canonicalName) {
        if (toolPricing == null) {
            return null;
        }
        Double price = toolPricing.get(dispatchName);
        return price != null ? price : toolPricing.get(canonicalName);
    }

    /**
     * Shared, because an ObjectMapper is expensive to build and was built anew for
     * every discover_tools result; reading a tree is thread-safe.
     */
    private static final ObjectMapper DISCOVERY_MAPPER = new ObjectMapper();

    /**
     * Parses the discover_tools JSON result and activates matching built-in tool
     * specs so the LLM can call them on subsequent iterations.
     */
    static void activateDiscoveredTools(String discoverResult,
                                        List<ToolSpecification> builtInSpecs,
                                        List<ToolSpecification> activeSpecs) {
        try {
            JsonNode root = DISCOVERY_MAPPER.readTree(discoverResult);
            JsonNode toolsNode = root.get("tools");
            if (toolsNode == null || !toolsNode.isArray()) {
                return;
            }

            Set<String> discoveredNames = new HashSet<>();
            for (JsonNode tool : toolsNode) {
                if (tool.has("name")) {
                    discoveredNames.add(tool.get("name").asText());
                }
            }

            // Add matching specs (skip discover_tools itself and already-active specs)
            Set<String> activeNames = new HashSet<>();
            for (ToolSpecification spec : activeSpecs) {
                activeNames.add(spec.name());
            }

            int activated = 0;
            for (ToolSpecification spec : builtInSpecs) {
                if (discoveredNames.contains(spec.name()) && !activeNames.contains(spec.name())) {
                    activeSpecs.add(spec);
                    activated++;
                }
            }

            LOGGER.debugf("LAZY activation: %d tools activated from discovery (%s)",
                    activated, discoveredNames);
        } catch (Exception e) {
            LOGGER.warnf("Failed to parse discover_tools result for LAZY activation: %s",
                    e.getMessage());
        }
    }
}
