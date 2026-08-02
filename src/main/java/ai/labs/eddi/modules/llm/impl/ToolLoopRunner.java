/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.TokenCountEstimator;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRules;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolNameResolver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    ToolLoopRunner(ToolExecutionService toolExecutionService, ToolResponseTruncator toolResponseTruncator,
            TenantQuotaService tenantQuotaService, MemorySnapshotService memorySnapshotService,
            ToolApprovalGate toolApprovalGate, ToolApprovalGateSupport gateSupport,
            ToolContextBudget toolContextBudgetGuard) {
        this.toolExecutionService = toolExecutionService;
        this.toolResponseTruncator = toolResponseTruncator;
        this.tenantQuotaService = tenantQuotaService;
        this.memorySnapshotService = memorySnapshotService;
        this.toolApprovalGate = toolApprovalGate;
        this.gateSupport = gateSupport;
        this.toolContextBudgetGuard = toolContextBudgetGuard;
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
        LOGGER.infof("LAZY mode: presenting %d specs initially (discover_tools + %d external)",
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

        // The setup's executors / sources / built-in specs are deliberately NOT
        // unpacked here: this method hands the whole ToolSetup to runToolCallLoop,
        // which reads them itself. Three locals used to sit here reading exactly
        // those three components and nothing ever used them — dead since before the
        // extraction, and only visible now that the method has a file of its own.
        boolean isLazy = task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY;

        // Active specs: what the LLM currently sees (LAZY starts narrow).
        List<ToolSpecification> activeSpecs = computeInitialActiveSpecs(setup, isLazy);

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
        String response = runToolCallLoop(chatModel, messages, activeSpecs, trace, 0,
                setup, isLazy, task, memory, effectiveToolApprovals, llmTaskIndex, Set.of(), transcriptMaxBytes, tokenHolder, jsonPolicy);

        Map<String, Object> responseMetadata = new HashMap<>();
        if (tokenHolder[0] != null) {
            responseMetadata.put("tokenUsage", ToolContextBudget.tokenUsageMap(tokenHolder[0]));
        }
        responseMetadata.put("toolCostUsd", toolCostDelta(conversationId, toolCostBefore));
        return new AgentOrchestrator.ExecutionResult(response, trace, responseMetadata);
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
     * snapshots of the conversation total. Clamped at zero because
     * {@link ToolCostTracker#resetConversation} can fire between the snapshots and
     * would otherwise yield a negative "cost".
     */
    double toolCostDelta(String conversationId, double costBefore) {
        return Math.max(0.0, conversationToolCost(conversationId) - costBefore);
    }

    /**
     * The single shared tool-calling loop, wrapped in
     * {@link AgentExecutionHelper#executeWithRetry}. Used by the live path (start
     * iteration 0, empty {@code clearedCallIds}) and by {@link #resumeToolLoop}
     * (start iteration {@code batch.getIterationIndex()+1}, {@code clearedCallIds}
     * = the human-approved ids so they are never re-gated). A fresh gated batch
     * throws {@link ToolApprovalRequiredException} to re-pause — the retry guard
     * lets it escape unchanged.
     *
     * @param initialMessages
     *            the message list the loop starts from (defensively copied inside);
     *            for resume this already carries the replayed transcript + the
     *            verdict-applied tool results
     * @param startIteration
     *            first loop index — carries budget continuity across a pause
     * @param clearedCallIds
     *            call ids a human already approved this pause; never re-gated
     * @param transcriptMaxBytes
     *            the configured cap (bytes) for freezing the transcript into a
     *            {@link PendingToolCallBatch} if this iteration re-pauses
     * @param jsonPolicy
     *            decides, per request, whether {@code ResponseFormat.JSON} is set;
     *            resolved against whether THAT request carries tool specifications
     */
    String runToolCallLoop(ChatModel chatModel, List<ChatMessage> initialMessages, List<ToolSpecification> activeSpecs,
                           List<Map<String, Object>> trace, int startIteration, AgentOrchestrator.ToolSetup setup, boolean isLazy,
                           LlmConfiguration.Task task, IConversationMemory memory, ToolApprovalsConfig effectiveToolApprovals,
                           int llmTaskIndex, Set<String> clearedCallIds, int transcriptMaxBytes, TokenUsage[] tokenHolder,
                           JsonResponseFormatPolicy jsonPolicy)
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

        return AgentExecutionHelper.executeWithRetry(() -> {
            // A retry REPLAYS this whole lambda, so anything the previous attempt
            // accumulated must be discarded — otherwise a turn that retried once
            // reports (and bills) roughly double the tokens it actually used.
            tokenHolder[0] = null;
            List<ChatMessage> currentMessages = new ArrayList<>(initialMessages);
            // Per-message token memo, local to this attempt: messages are immutable and
            // re-counted on every iteration, so without it a 10-iteration loop tokenizes
            // the first tool result ten times. Lives on the call stack — the orchestrator
            // is a shared singleton and must stay stateless.
            Map<ChatMessage, Integer> toolContextTokenMemo = new IdentityHashMap<>();
            int maxIterations = task.getMaxToolIterations() != null ? task.getMaxToolIterations() : 10;

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

            for (int i = startIteration; i < maxIterations; i++) {
                // Cooperative cancellation: if this step was interrupted (e.g. a cascade
                // per-step timeout called future.cancel(true)), stop before issuing another
                // model call — avoids launching further side-effectful tools on a step whose
                // result will be discarded. Thread.interrupted() also CLEARS the flag so it
                // cannot leak to any later work on this thread.
                if (Thread.interrupted()) {
                    throw new LifecycleException("Agent execution cancelled (interrupted)");
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
                if (jsonPolicy != null) {
                    var responseFormat = jsonPolicy.resolve(toolsInRequest);
                    if (responseFormat != null) {
                        requestBuilder.responseFormat(responseFormat);
                    }
                }

                ChatRequest chatRequest = requestBuilder.build();

                ChatResponse chatResponse = chatModel.chat(chatRequest);
                AiMessage aiMessage = ToolApprovalGateSupport.normalizeToolCallIds(chatResponse.aiMessage(), effectiveToolApprovals);
                currentMessages.add(aiMessage);

                // Accumulate token usage for cost/observability reporting.
                if (chatResponse.metadata() != null && chatResponse.metadata().tokenUsage() != null) {
                    tokenHolder[0] = ToolContextBudget.sumTokens(tokenHolder[0], chatResponse.metadata().tokenUsage());
                }

                if (aiMessage.hasToolExecutionRequests()) {
                    // === Tool-approval gate (tool-level HITL) ===
                    // Split the batch into gated (require human approval) and allowed
                    // calls. clearedCallIds carries the human-approved ids on resume so
                    // they are never re-gated. Inert when effectiveToolApprovals is
                    // null/empty — byte-identical to the pre-HITL path.
                    var gateResult = toolApprovalGate.classify(aiMessage.toolExecutionRequests(), toolSources, setup.toolEndpoints(),
                            effectiveToolApprovals, clearedCallIds);

                    if (!gateResult.gated().isEmpty()) {
                        int pausesSoFar = ToolApprovalGateSupport.readToolPauseCount(memory);
                        if (pausesSoFar >= ToolApprovalGateSupport.maxPausesPerTurn(effectiveToolApprovals)) {
                            // Fail-closed: the pause budget for this turn is spent —
                            // do not pause and do not execute the gated calls; tell
                            // the model to stop asking so the loop can still finish
                            // with the ungated results.
                            for (ToolExecutionRequest gatedReq : gateResult.gated()) {
                                currentMessages.add(ToolExecutionResultMessage.from(gatedReq,
                                        "{\"status\":\"DENIED\",\"reason\":\"approval-pause limit for this turn reached; do not retry\"}"));
                                Map<String, Object> capStep = new HashMap<>();
                                capStep.put("type", "tool_error");
                                capStep.put("tool", gatedReq.name());
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
                                executeSingleToolCall(allowedReq, memory, currentMessages, trace, toolExecutors,
                                        toolRateLimits, toolCanonicalNames, defaultRateLimit, maxBudget, conversationId,
                                        enableRateLimiting, enableCaching, enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
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
                            // entirely, and on the live path AgentExecutionHelper.executeWithRetry
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
                            // 3) snapshot + persist the pending batch, then abort the loop
                            PendingToolCallBatch batch = gateSupport.buildPendingBatch(currentMessages, gateResult, task, memory,
                                    i, ToolApprovalGateSupport.activatedToolNames(isLazy, activeSpecs), trace, pausesSoFar + 1, llmTaskIndex,
                                    toolSources, effectiveToolApprovals, transcriptMaxBytes, ruleByCallId, governingRule);
                            memory.setHitlPendingToolCalls(batch);
                            ToolApprovalGateSupport.incrementToolPauseCount(memory, pausesSoFar);
                            throw new ToolApprovalRequiredException(
                                    ToolApprovalGateSupport.buildPauseReason(effectiveToolApprovals, gateResult, governingRule), batch);
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

                        executeSingleToolCall(toolRequest, memory, currentMessages, trace, toolExecutors,
                                toolRateLimits, toolCanonicalNames, defaultRateLimit, maxBudget, conversationId,
                                enableRateLimiting, enableCaching, enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
                    }
                } else {
                    return aiMessage.text();
                }
            }

            // Loop exhausted its iteration budget. The last message is usually the
            // model's final AiMessage; on resume with a spent budget it may instead be
            // a verdict-applied tool result — guard the cast either way.
            ChatMessage last = currentMessages.get(currentMessages.size() - 1);
            if (last instanceof AiMessage aiLast) {
                return aiLast.text() != null ? aiLast.text() : "Max tool iterations reached";
            }
            return "Max tool iterations reached";
        }, task, "Agent execution");
    }

    /**
     * Executes a single tool call through the full per-request pipeline:
     * auto-checkpoint, trace entry, per-conversation budget check, tenant cost
     * budget check, executor dispatch (via {@link ToolExecutionService} for rate
     * limiting/caching/cost tracking), response truncation, result trace, and LAZY
     * activation. Extracted verbatim from the live loop so the live path and Task
     * 9's resume path share ONE copy.
     * <p>
     * Package-private for direct unit testing of the gate.
     */
    void executeSingleToolCall(ToolExecutionRequest toolRequest, IConversationMemory memory,
                               List<ChatMessage> currentMessages, List<Map<String, Object>> trace,
                               Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                               Map<String, String> toolCanonicalNames,
                               int defaultRateLimit, Double maxBudget, String conversationId,
                               boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                               LlmConfiguration.Task task, boolean isLazy,
                               List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs) {
        // Live path: run the full pipeline, then append the raw result verbatim.
        String toolResult = executeSingleToolCallResult(toolRequest, memory, trace, toolExecutors, toolRateLimits,
                toolCanonicalNames, defaultRateLimit, maxBudget, conversationId, enableRateLimiting, enableCaching,
                enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
        currentMessages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
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
                                       Map<String, String> toolCanonicalNames,
                                       int defaultRateLimit, Double maxBudget, String conversationId,
                                       boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                                       LlmConfiguration.Task task, boolean isLazy,
                                       List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs) {
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
        callStep.put("arguments", toolRequest.arguments());
        trace.add(callStep);

        // Check per-conversation TOOL budget before executing tool.
        //
        // Enforcement is opt-in: enforceBudget on the task wins, otherwise
        // AgentOrchestrator.BUDGET_ENFORCE_DEFAULT (false unless
        // eddi.tools.budget.enforce-by-default
        // says so). Built-in tools priced at $0.00 until this release, so enforcing
        // by default would make those ceilings bind for the first time on upgrade.
        // A ceiling left unenforced is named once in a startup-style WARN rather
        // than passing silently. Cost tracking itself is unaffected by the flag.
        AgentOrchestrator.warnAboutUnenforcedBudgets(task);
        boolean enforceBudget = task.getEnforceBudget() != null ? task.getEnforceBudget() : AgentOrchestrator.BUDGET_ENFORCE_DEFAULT;
        if (enforceBudget && maxBudget != null && conversationId != null
                && !toolExecutionService.getCostTracker().isWithinBudget(conversationId, maxBudget)) {
            String budgetError = "Budget exceeded for conversation " + conversationId;
            LOGGER.warn(sanitize(budgetError));

            Map<String, Object> budgetStep = new HashMap<>();
            budgetStep.put("type", "tool_error");
            budgetStep.put("tool", toolRequest.name());
            budgetStep.put("error", budgetError);
            trace.add(budgetStep);

            return "Error: " + budgetError;
        }

        // Check tenant-level monthly cost budget (MCP governance)
        if (tenantQuotaService != null) {
            var costCheck = tenantQuotaService.checkCostBudget(tenantQuotaService.getDefaultTenantId());
            if (!costCheck.allowed()) {
                LOGGER.warnf("Tenant cost budget exceeded during tool call: %s", costCheck.reason());

                Map<String, Object> quotaStep = new HashMap<>();
                quotaStep.put("type", "tool_error");
                quotaStep.put("tool", toolRequest.name());
                quotaStep.put("error", costCheck.reason());
                trace.add(quotaStep);

                return "Error: " + costCheck.reason();
            }
        }

        // Execute through ToolExecutionService for rate limiting, caching, cost
        // tracking
        ToolExecutor executor = toolExecutors.get(toolRequest.name());
        String toolResult;
        if (executor != null) {
            // A built-in is DISPATCHED under its @Tool method name ("calculate") but
            // CONFIGURED under its whitelist slug ("calculator"). Resolve the slug once,
            // here, and hand both names down: only the price and the cache TTL are looked
            // up by slug, everything that identifies the individual call is not.
            String canonicalName = ToolNameResolver.canonical(toolRequest.name(), toolCanonicalNames);
            int rateLimit = resolveRateLimit(toolRateLimits, toolRequest.name(), canonicalName, defaultRateLimit);
            Double priceOverride = resolveOverride(task.getToolPricing(), toolRequest.name(), canonicalName);

            // Partition the tool-result cache by identity, so one user's result can never
            // be served back to another. A null tag means no usable identity was
            // available; ToolExecutionService then bypasses the cache entirely. Both
            // names go in: toolCacheScopes shares its key vocabulary with toolRateLimits
            // and toolPricing, so a slug-keyed narrowing override has to bind here too.
            String cacheScopeTag = ToolCacheService.resolveScopeTag(toolRequest.name(), canonicalName, task.getToolCacheScopes(),
                    task.getDefaultToolCacheScope(), memory != null ? memory.getUserId() : null, conversationId);

            var invocation = new ToolInvocation(toolRequest.name(), canonicalName, priceOverride);
            toolResult = toolExecutionService.executeToolWrapped(invocation, toolRequest.arguments(), cacheScopeTag, conversationId,
                    () -> executor.execute(toolRequest, null), enableRateLimiting, enableCaching, enableCostTracking, rateLimit);
        } else {
            toolResult = "Error: Tool '" + toolRequest.name() + "' not found";
        }

        // Apply response truncation (MCP governance)
        toolResult = toolResponseTruncator.truncateIfNeeded(
                toolRequest.name(), toolResult, task.getToolResponseLimits(),
                task.getType(), task.getParameters());

        Map<String, Object> resultStep = new HashMap<>();
        resultStep.put("type", "tool_result");
        resultStep.put("tool", toolRequest.name());
        resultStep.put("result", toolResult);
        trace.add(resultStep);

        // LAZY mode: after discover_tools returns, activate the matching built-in specs
        if (isLazy && "discover_tools".equals(toolRequest.name())) {
            activateDiscoveredTools(toolResult, builtInSpecs, activeSpecs);
        }

        return toolResult;
    }

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
     * Parses the discover_tools JSON result and activates matching built-in tool
     * specs so the LLM can call them on subsequent iterations.
     */
    void activateDiscoveredTools(String discoverResult,
                                 List<ToolSpecification> builtInSpecs,
                                 List<ToolSpecification> activeSpecs) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(discoverResult);
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

            LOGGER.infof("LAZY activation: %d tools activated from discovery (%s)",
                    activated, discoveredNames);
        } catch (Exception e) {
            LOGGER.warnf("Failed to parse discover_tools result for LAZY activation: %s",
                    e.getMessage());
        }
    }
}
