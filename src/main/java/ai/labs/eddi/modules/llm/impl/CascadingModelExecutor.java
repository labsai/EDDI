/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.HeuristicConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.JudgeModelConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Executes a multi-model cascade: tries a cheap/fast model first, evaluates
 * confidence, and escalates to a more powerful model only if confidence is
 * below the configured threshold.
 * <p>
 * Emits SSE events via {@link ConversationEventSink} so the frontend can
 * display real-time cascade status, records per-step token usage / cost in the
 * trace, and publishes Micrometer metrics under {@code eddi.llm.cascade.*}.
 * <p>
 * Edge-case handling:
 * <ul>
 * <li>Timeouts → treated as escalation to the next step (best response
 * kept)</li>
 * <li>Retryable errors (rate limits, 503) → retried per task config, then
 * escalates</li>
 * <li>Non-retryable errors (auth failure) → escalates immediately</li>
 * <li>All steps fail → returns best response seen so far, or throws an
 * aggregated {@link LifecycleException} if none produced a result</li>
 * <li>Duration ceiling ({@code maxTotalDurationMs}) or cost ceiling
 * ({@code maxCostPerRun}) reached → stops escalating and returns the best
 * response so far</li>
 * </ul>
 */
class CascadingModelExecutor {
    private static final Logger LOGGER = Logger.getLogger(CascadingModelExecutor.class);
    // Virtual threads — lightweight, no pool sizing, no leak risk, ideal for
    // I/O-bound model calls
    private static final ExecutorService TIMEOUT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Parameter keys that must NOT be run through the template engine
     * (credentials).
     */
    private static final Set<String> TEMPLATE_SKIP_PARAMS = Set.of("apiKey", "signingSecret", "appPassword", "botToken");

    private final ChatModelRegistry registry;
    private final GlobalVariableResolver globalVariableResolver;
    private final ITemplatingEngine templatingEngine;
    private final LegacyChatExecutor legacyChatExecutor;
    private final StreamingLegacyChatExecutor streamingLegacyChatExecutor;
    private final MeterRegistry meterRegistry;

    CascadingModelExecutor(ChatModelRegistry registry, GlobalVariableResolver globalVariableResolver, ITemplatingEngine templatingEngine,
            LegacyChatExecutor legacyChatExecutor, StreamingLegacyChatExecutor streamingLegacyChatExecutor, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.globalVariableResolver = globalVariableResolver;
        this.templatingEngine = templatingEngine;
        this.legacyChatExecutor = legacyChatExecutor;
        this.streamingLegacyChatExecutor = streamingLegacyChatExecutor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Result of a cascade execution.
     *
     * @param response
     *            the accepted response text
     * @param confidence
     *            the confidence score of the accepted response
     * @param stepUsed
     *            0-based index of the cascade step that produced the response
     * @param modelType
     *            the model provider type that produced the response
     * @param modelName
     *            the specific model name that produced the response
     * @param tokenUsage
     *            token usage of the accepted step ({@code inputTokens},
     *            {@code outputTokens}, {@code totalTokens}); may be empty
     * @param runCostUsd
     *            aggregate dollar cost across all attempted steps (0 when no
     *            pricing is configured)
     * @param trace
     *            list of per-step trace entries for audit
     * @param agentResult
     *            if agent mode was used, the agent execution result
     * @param streamedLive
     *            true if this response was already streamed to the client
     *            token-by-token (so the caller must not re-emit it)
     */
    record CascadeResult(String response, double confidence, int stepUsed, String modelType, String modelName, Map<String, Object> tokenUsage,
            double runCostUsd, List<Map<String, Object>> trace, AgentOrchestrator.ExecutionResult agentResult, boolean streamedLive) {
    }

    /**
     * Execute the cascade. Tries each step in order until one meets its confidence
     * threshold (or the last step is reached / a ceiling is hit).
     *
     * @param cascade
     *            cascade configuration (steps, strategy, evaluation, ceilings)
     * @param messages
     *            conversation messages (without confidence instruction)
     * @param systemMessage
     *            the original system message
     * @param baseParams
     *            task-level processed parameters
     * @param task
     *            the task configuration (for retry config)
     * @param memory
     *            conversation memory (for event sink + agent mode)
     * @param agentOrchestrator
     *            agent orchestrator (for agent-mode cascade)
     * @param templateDataObjects
     *            template data for resolving step / judge parameters
     * @param jsonMode
     *            whether native JSON response format should be requested
     * @param convertToObject
     *            whether the task expects a raw JSON object as output (affects the
     *            effective confidence strategy)
     * @param allowLiveStreaming
     *            whether the always-accepted final step may be streamed live to the
     *            client (legacy mode, non-wrapper strategies, streaming-capable
     *            provider)
     * @return the cascade result
     * @throws LifecycleException
     *             if all steps fail without producing any usable response
     */
    CascadeResult execute(ModelCascadeConfig cascade, List<ChatMessage> messages, String systemMessage, Map<String, String> baseParams,
                          LlmConfiguration.Task task, IConversationMemory memory, AgentOrchestrator agentOrchestrator,
                          Map<String, Object> templateDataObjects, boolean jsonMode, boolean convertToObject, boolean allowLiveStreaming)
            throws LifecycleException {

        List<CascadeStep> steps = cascade.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw new LifecycleException("Model cascade enabled but no steps configured");
        }

        String strategy = cascade.getStrategy();
        if (strategy != null && !"cascade".equalsIgnoreCase(strategy)) {
            // Only sequential 'cascade' is implemented. 'parallel' is reserved.
            // A deploy-time validation warning is emitted in LlmTask.configure — keep
            // runtime quiet to avoid per-turn log spam.
            LOGGER.debugf("Cascade strategy '%s' is not implemented; running sequentially", strategy);
        }

        boolean useAgentMode = cascade.isEnableInAgentMode() && task.isAgentMode();
        String effectiveStrategy = resolveEffectiveStrategy(cascade.getEvaluationStrategy(), convertToObject, useAgentMode, cascade);
        HeuristicConfig heuristicConfig = cascade.getHeuristic();

        // Build the judge model once if the effective strategy needs it.
        ChatModel judgeModel = null;
        if ("judge_model".equalsIgnoreCase(effectiveStrategy) && cascade.getJudgeModel() != null) {
            judgeModel = buildJudgeModel(cascade.getJudgeModel(), templateDataObjects);
        }

        ConversationEventSink eventSink = memory.getEventSink();
        increment("eddi.llm.cascade.executions", "agentMode", String.valueOf(useAgentMode));

        List<Map<String, Object>> trace = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        CascadeResult bestSoFar = null;

        Long maxTotalDurationMs = cascade.getMaxTotalDurationMs();
        Double maxCostPerRun = cascade.getMaxCostPerRun();
        long cascadeStart = System.currentTimeMillis();
        double runCostUsd = 0.0;

        for (int i = 0; i < steps.size(); i++) {
            CascadeStep step = steps.get(i);
            boolean isLastStep = (i == steps.size() - 1);

            // Ceiling checks before starting a step (step 0 always runs).
            if (i > 0) {
                long elapsed = System.currentTimeMillis() - cascadeStart;
                if (maxTotalDurationMs != null && elapsed >= maxTotalDurationMs) {
                    LOGGER.warnf("Cascade duration ceiling reached (%dms >= %dms) before step %d; returning best so far", elapsed, maxTotalDurationMs,
                            i);
                    increment("eddi.llm.cascade.ceiling.exceeded", "kind", "duration");
                    return finalizeBest(bestSoFar, runCostUsd, trace, errors);
                }
                if (maxCostPerRun != null && runCostUsd >= maxCostPerRun) {
                    LOGGER.warnf("Cascade cost ceiling reached ($%.4f >= $%.4f) before step %d; returning best so far", runCostUsd, maxCostPerRun, i);
                    increment("eddi.llm.cascade.ceiling.exceeded", "kind", "cost");
                    return finalizeBest(bestSoFar, runCostUsd, trace, errors);
                }
            }

            // #8: resolve step type through global variables (parity with the standard
            // path).
            String rawType = step.getType() != null ? step.getType() : task.getType();
            String modelType = globalVariableResolver.resolveValue(rawType);
            // #8: template step param values (parity with task params), then merge.
            Map<String, String> mergedParams = mergeParams(baseParams, templateParams(step.getParameters(), templateDataObjects));
            String modelName = resolveModelName(mergedParams, modelType);

            if (eventSink != null) {
                eventSink.onCascadeStepStart(i, modelType, modelName, steps.size());
            }

            LOGGER.infof("Cascade step %d/%d: trying %s/%s", i, steps.size() - 1, modelType, modelName);
            long stepStart = System.currentTimeMillis();

            Map<String, Object> stepTrace = new LinkedHashMap<>();
            stepTrace.put("step", i);
            stepTrace.put("model", modelName);
            stepTrace.put("modelType", modelType);

            // Cap this step's timeout by the remaining duration budget.
            long stepTimeout = step.getTimeoutMs() != null ? step.getTimeoutMs() : 30000L;
            if (maxTotalDurationMs != null) {
                long remaining = maxTotalDurationMs - (System.currentTimeMillis() - cascadeStart);
                if (remaining <= 0) {
                    increment("eddi.llm.cascade.ceiling.exceeded", "kind", "duration");
                    return finalizeBest(bestSoFar, runCostUsd, trace, errors);
                }
                stepTimeout = Math.min(stepTimeout, remaining);
            }

            try {
                ChatModel chatModel = registry.getOrCreate(modelType, mergedParams);

                // Live-stream the always-accepted final step for non-wrapper strategies —
                // the raw tokens are the actual answer, so nothing needs to be unwrapped.
                // Only when the provider supports streaming (getOrCreateStreaming may return
                // null).
                boolean streamLive = allowLiveStreaming && !useAgentMode && !"structured_output".equalsIgnoreCase(effectiveStrategy)
                        && (isLastStep || ("none".equalsIgnoreCase(effectiveStrategy) && i == 0));
                StreamingChatModel streamingModel = streamLive ? registry.getOrCreateStreaming(modelType, mergedParams) : null;

                StepResult stepResult = executeStepWithTimeout(chatModel, streamingModel, eventSink, messages, systemMessage, effectiveStrategy, task,
                        step, memory, agentOrchestrator, useAgentMode, judgeModel, heuristicConfig, jsonMode, stepTimeout);

                long durationMs = System.currentTimeMillis() - stepStart;
                double stepCost = computeCost(step, cascade, stepResult.tokenUsage);
                runCostUsd += stepCost;

                stepTrace.put("confidence", stepResult.confidence);
                stepTrace.put("durationMs", durationMs);
                if (stepResult.tokenUsage != null) {
                    stepTrace.put("tokenUsage", stepResult.tokenUsage);
                }
                stepTrace.put("costUsd", stepCost);

                recordStepMetrics(modelType, durationMs, stepResult.confidence, stepResult.tokenUsage, stepCost);

                if (bestSoFar == null || stepResult.confidence > bestSoFar.confidence()) {
                    bestSoFar = new CascadeResult(stepResult.response, stepResult.confidence, i, modelType, modelName, stepResult.tokenUsage, 0.0,
                            trace, stepResult.agentResult, stepResult.streamedLive);
                }

                if (isLastStep || step.getConfidenceThreshold() == null || stepResult.confidence >= step.getConfidenceThreshold()) {
                    stepTrace.put("status", "accepted");
                    trace.add(stepTrace);
                    increment("eddi.llm.cascade.accepted.step", "step", String.valueOf(i));

                    LOGGER.infof("Cascade step %d accepted: confidence=%.2f, model=%s, durationMs=%d", i, stepResult.confidence, modelName,
                            durationMs);

                    // returnBestAcrossSteps: an earlier escalated step may have scored higher.
                    // Do NOT swap when the accepted step was already streamed live — the client
                    // has already received its tokens, so returning a different step's text would
                    // mismatch the stream.
                    if (cascade.isReturnBestAcrossSteps() && !stepResult.streamedLive && bestSoFar != null && bestSoFar.stepUsed() != i
                            && bestSoFar.confidence() > stepResult.confidence) {
                        LOGGER.infof("returnBestAcrossSteps: returning step %d (confidence=%.2f) over accepted step %d (confidence=%.2f)",
                                bestSoFar.stepUsed(), bestSoFar.confidence(), i, stepResult.confidence);
                        // Reflect the override in the trace so audit artifacts agree with stepUsed.
                        stepTrace.put("status", "superseded_by_best");
                        return withRun(bestSoFar, runCostUsd, trace);
                    }

                    return new CascadeResult(stepResult.response, stepResult.confidence, i, modelType, modelName, stepResult.tokenUsage, runCostUsd,
                            trace, stepResult.agentResult, stepResult.streamedLive);
                }

                // Escalate.
                stepTrace.put("status", "escalated");
                trace.add(stepTrace);
                increment("eddi.llm.cascade.escalations", "reason", "low_confidence");

                if (eventSink != null) {
                    eventSink.onCascadeEscalation(i, i + 1, stepResult.confidence, step.getConfidenceThreshold(), "low_confidence", durationMs);
                }

                LOGGER.infof("Cascade step %d escalating: confidence=%.2f < threshold=%.2f, model=%s", i, stepResult.confidence,
                        step.getConfidenceThreshold(), modelName);

            } catch (TimeoutException e) {
                long durationMs = System.currentTimeMillis() - stepStart;
                stepTrace.put("status", "timeout");
                stepTrace.put("durationMs", durationMs);
                trace.add(stepTrace);
                errors.add(String.format("Step %d (%s): timeout after %dms", i, modelName, durationMs));
                increment("eddi.llm.cascade.escalations", "reason", "timeout");
                increment("eddi.llm.cascade.step.errors", "provider", modelType, "type", "timeout");

                LOGGER.warnf("Cascade step %d timed out after %dms, escalating", i, durationMs);

                if (!isLastStep && eventSink != null) {
                    eventSink.onCascadeEscalation(i, i + 1, 0.0, step.getConfidenceThreshold() != null ? step.getConfidenceThreshold() : 0.0,
                            "timeout", durationMs);
                }

                if (isLastStep) {
                    if (bestSoFar != null) {
                        LOGGER.warn("All cascade steps exhausted (last timed out), returning best response");
                        return withRun(bestSoFar, runCostUsd, trace);
                    }
                    throw new LifecycleException("Model cascade failed: all steps exhausted. Errors: " + String.join("; ", errors));
                }

            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - stepStart;
                String errorType = isRetryableError(e) ? "retryable_error" : "error";
                stepTrace.put("status", errorType);
                stepTrace.put("error", e.getMessage());
                stepTrace.put("durationMs", durationMs);
                trace.add(stepTrace);
                errors.add(String.format("Step %d (%s): %s", i, modelName, e.getMessage()));
                increment("eddi.llm.cascade.escalations", "reason", errorType);
                increment("eddi.llm.cascade.step.errors", "provider", modelType, "type", errorType);

                LOGGER.warnf("Cascade step %d failed (%s): %s, escalating", i, errorType, e.getMessage());

                if (!isLastStep && eventSink != null) {
                    eventSink.onCascadeEscalation(i, i + 1, 0.0, step.getConfidenceThreshold() != null ? step.getConfidenceThreshold() : 0.0,
                            errorType, durationMs);
                }

                if (isLastStep) {
                    if (bestSoFar != null) {
                        LOGGER.warn("All cascade steps exhausted (last failed), returning best response");
                        return withRun(bestSoFar, runCostUsd, trace);
                    }
                    throw new LifecycleException("Model cascade failed: all steps exhausted. Errors: " + String.join("; ", errors), e);
                }
            }
        }

        // Should be unreachable — last step always accepted or throws.
        return finalizeBest(bestSoFar, runCostUsd, trace, errors);
    }

    /**
     * Compute the effective confidence strategy. The {@code structured_output}
     * wrapper cannot be used when the task expects a raw JSON object
     * ({@code convertToObject}) or when running in agent mode (the wrapper cannot
     * be injected around the tool-calling loop). In those cases the strategy is
     * downgraded to {@code judge_model} (if a judge is configured) or
     * {@code heuristic}. A single deploy-time warning is emitted in
     * {@code LlmTask.configure}; runtime logs at debug to avoid per-turn spam.
     */
    private String resolveEffectiveStrategy(String configured, boolean convertToObject, boolean useAgentMode, ModelCascadeConfig cascade) {
        String s = configured != null ? configured.toLowerCase() : "structured_output";
        boolean wrapperUnusable = convertToObject || useAgentMode;
        if ("structured_output".equals(s) && wrapperUnusable) {
            String downgraded = cascade.getJudgeModel() != null ? "judge_model" : "heuristic";
            LOGGER.debugf("structured_output confidence not usable with %s; using '%s'", convertToObject ? "convertToObject" : "agent mode",
                    downgraded);
            return downgraded;
        }
        return s;
    }

    /** Build the judge model from its config, resolving type + templated params. */
    private ChatModel buildJudgeModel(JudgeModelConfig judgeConfig, Map<String, Object> templateDataObjects) {
        try {
            if (isBlank(judgeConfig.getType())) {
                LOGGER.warn("judge_model configured without a type; falling back to heuristic");
                return null;
            }
            String type = globalVariableResolver.resolveValue(judgeConfig.getType());
            Map<String, String> params = templateParams(judgeConfig.getParameters(), templateDataObjects);
            return registry.getOrCreate(type, params != null ? params : new HashMap<>());
        } catch (Exception e) {
            LOGGER.warnf("Failed to build judge model: %s; falling back to heuristic", e.getMessage());
            return null;
        }
    }

    /**
     * Execute a single cascade step with timeout.
     */
    private StepResult executeStepWithTimeout(ChatModel chatModel, StreamingChatModel streamingModel, ConversationEventSink eventSink,
                                              List<ChatMessage> messages, String systemMessage, String evaluationStrategy,
                                              LlmConfiguration.Task task, CascadeStep step, IConversationMemory memory,
                                              AgentOrchestrator agentOrchestrator, boolean useAgentMode, ChatModel judgeModel,
                                              HeuristicConfig heuristicConfig, boolean jsonMode, long timeoutMs)
            throws Exception {

        Future<StepResult> future = TIMEOUT_EXECUTOR.submit(() -> {
            if (useAgentMode) {
                return executeAgentModeStep(chatModel, messages, systemMessage, evaluationStrategy, task, memory, agentOrchestrator, judgeModel,
                        heuristicConfig, jsonMode);
            } else {
                return executeLegacyModeStep(chatModel, streamingModel, eventSink, messages, systemMessage, evaluationStrategy, task, judgeModel,
                        heuristicConfig, jsonMode);
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LifecycleException le) {
                throw le;
            }
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new LifecycleException("Cascade step execution failed", cause instanceof Exception ex ? ex : new RuntimeException(cause));
        }
    }

    /**
     * Execute a step in legacy (non-agent) mode.
     */
    private StepResult executeLegacyModeStep(ChatModel chatModel, StreamingChatModel streamingModel, ConversationEventSink eventSink,
                                             List<ChatMessage> originalMessages, String systemMessage, String evaluationStrategy,
                                             LlmConfiguration.Task task, ChatModel judgeModel, HeuristicConfig heuristicConfig, boolean jsonMode)
            throws LifecycleException {

        // The structured_output wrapper only applies in legacy mode; it never co-occurs
        // with jsonMode (resolveEffectiveStrategy downgrades it away in that case).
        List<ChatMessage> messages;
        if ("structured_output".equals(evaluationStrategy)) {
            messages = augmentMessagesForStructuredOutput(originalMessages, systemMessage);
        } else {
            messages = new ArrayList<>(originalMessages);
        }

        String responseText;
        Map<String, Object> tokenUsage;
        boolean streamedLive = false;
        if (streamingModel != null && eventSink != null) {
            // Stream the always-accepted final step live — tokens emitted via the sink.
            var streamResult = streamingLegacyChatExecutor.executeCapturing(streamingModel, messages, eventSink);
            responseText = streamResult.response() != null ? streamResult.response() : "";
            tokenUsage = extractTokenUsage(streamResult.responseMetadata());
            streamedLive = true;
        } else {
            var chatResult = legacyChatExecutor.execute(chatModel, messages, task, jsonMode);
            responseText = chatResult.response() != null ? chatResult.response() : "";
            tokenUsage = extractTokenUsage(chatResult.responseMetadata());
        }

        var evalResult = ConfidenceEvaluator.evaluate(evaluationStrategy, responseText, judgeModel, heuristicConfig);
        return new StepResult(evalResult.response(), evalResult.confidence(), null, tokenUsage, streamedLive);
    }

    /**
     * Execute a step in agent mode (with tool-calling loop). The structured_output
     * wrapper is never used here — {@code evaluationStrategy} has already been
     * downgraded to judge/heuristic by {@link #resolveEffectiveStrategy}.
     */
    private StepResult executeAgentModeStep(ChatModel chatModel, List<ChatMessage> originalMessages, String systemMessage, String evaluationStrategy,
                                            LlmConfiguration.Task task, IConversationMemory memory, AgentOrchestrator agentOrchestrator,
                                            ChatModel judgeModel, HeuristicConfig heuristicConfig, boolean jsonMode)
            throws LifecycleException {

        List<ChatMessage> chatMessagesWithoutSystem = originalMessages.stream().filter(m -> !(m instanceof SystemMessage))
                .collect(java.util.stream.Collectors.toList());

        var agentResult = agentOrchestrator.executeIfToolsEnabled(chatModel, systemMessage, chatMessagesWithoutSystem, task, memory);

        if (agentResult != null) {
            String responseText = agentResult.response();
            Map<String, Object> tokenUsage = extractTokenUsage(agentResult.responseMetadata());
            var evalResult = ConfidenceEvaluator.evaluate(evaluationStrategy, responseText, judgeModel, heuristicConfig);
            return new StepResult(evalResult.response(), evalResult.confidence(), agentResult, tokenUsage, false);
        }

        // Agent mode returned null (no tools enabled) — fall back to legacy (no live
        // stream).
        return executeLegacyModeStep(chatModel, null, null, originalMessages, systemMessage, evaluationStrategy, task, judgeModel, heuristicConfig,
                jsonMode);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractTokenUsage(Map<String, Object> responseMetadata) {
        if (responseMetadata == null) {
            return null;
        }
        Object tu = responseMetadata.get("tokenUsage");
        return tu instanceof Map ? (Map<String, Object>) tu : null;
    }

    /**
     * Compute the dollar cost of a step from its token usage and configured pricing
     * (step price overrides cascade-level default). Returns 0 when no price is set.
     */
    static double computeCost(CascadeStep step, ModelCascadeConfig cascade, Map<String, Object> tokenUsage) {
        Double inPrice = step.getInputPricePer1M() != null ? step.getInputPricePer1M() : cascade.getInputPricePer1M();
        Double outPrice = step.getOutputPricePer1M() != null ? step.getOutputPricePer1M() : cascade.getOutputPricePer1M();
        if (tokenUsage == null || (inPrice == null && outPrice == null)) {
            return 0.0;
        }
        long inputTokens = asLong(tokenUsage.get("inputTokens"));
        long outputTokens = asLong(tokenUsage.get("outputTokens"));
        double cost = 0.0;
        if (inPrice != null) {
            cost += inputTokens / 1_000_000.0 * inPrice;
        }
        if (outPrice != null) {
            cost += outputTokens / 1_000_000.0 * outPrice;
        }
        return cost;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * Augment messages with confidence instruction for structured_output strategy.
     * Appends the instruction to the system message, or adds a new system message
     * if none exists.
     */
    private static List<ChatMessage> augmentMessagesForStructuredOutput(List<ChatMessage> originalMessages, String systemMessage) {

        List<ChatMessage> messages = new ArrayList<>();
        boolean systemFound = false;

        for (ChatMessage msg : originalMessages) {
            if (msg instanceof SystemMessage sm && !systemFound) {
                messages.add(SystemMessage.from(sm.text() + ConfidenceEvaluator.buildConfidenceInstruction()));
                systemFound = true;
            } else {
                messages.add(msg);
            }
        }

        if (!systemFound && systemMessage != null) {
            messages.add(0, SystemMessage.from(systemMessage + ConfidenceEvaluator.buildConfidenceInstruction()));
        }

        return messages;
    }

    /**
     * Merge step parameters with task-level base params. Step params win.
     */
    static Map<String, String> mergeParams(Map<String, String> baseParams, Map<String, String> stepParams) {
        Map<String, String> merged = new HashMap<>();
        if (baseParams != null) {
            merged.putAll(baseParams);
        }
        if (stepParams != null) {
            merged.putAll(stepParams);
        }
        return merged;
    }

    /**
     * Run the template engine over parameter values (parity with task params).
     * Credential keys are skipped; template failures fall back to the raw value.
     */
    private Map<String, String> templateParams(Map<String, String> params, Map<String, Object> templateDataObjects) {
        if (params == null || params.isEmpty() || templatingEngine == null || templateDataObjects == null) {
            return params;
        }
        Map<String, String> result = new HashMap<>(params);
        result.replaceAll((key, value) -> {
            if (isBlank(value) || TEMPLATE_SKIP_PARAMS.contains(key)) {
                return value;
            }
            try {
                return templatingEngine.processTemplate(value, templateDataObjects);
            } catch (ITemplatingEngine.TemplateEngineException e) {
                LOGGER.errorf(e, "Template processing failed for cascade step parameter '%s': %s", key, e.getLocalizedMessage());
                return value;
            }
        });
        return result;
    }

    /**
     * Resolve the specific model name from provider-specific parameter keys. Falls
     * back to the provider type when no explicit model key is present.
     */
    private static String resolveModelName(Map<String, String> params, String fallbackType) {
        for (String key : List.of("modelName", "model", "modelId", "deploymentName")) {
            String v = params.get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return fallbackType;
    }

    /** Rebuild the best-so-far result with the final aggregate run cost + trace. */
    private static CascadeResult withRun(CascadeResult best, double runCostUsd, List<Map<String, Object>> trace) {
        return new CascadeResult(best.response(), best.confidence(), best.stepUsed(), best.modelType(), best.modelName(), best.tokenUsage(),
                runCostUsd, trace, best.agentResult(), best.streamedLive());
    }

    private static CascadeResult finalizeBest(CascadeResult bestSoFar, double runCostUsd, List<Map<String, Object>> trace, List<String> errors)
            throws LifecycleException {
        if (bestSoFar != null) {
            return withRun(bestSoFar, runCostUsd, trace);
        }
        throw new LifecycleException("Model cascade failed: no steps produced a result. Errors: " + String.join("; ", errors));
    }

    private void recordStepMetrics(String modelType, long durationMs, double confidence, Map<String, Object> tokenUsage, double stepCost) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer("eddi.llm.cascade.step.latency", "provider", modelType).record(Duration.ofMillis(durationMs));
        meterRegistry.summary("eddi.llm.cascade.confidence").record(confidence);
        if (tokenUsage != null) {
            meterRegistry.counter("eddi.llm.cascade.tokens", "provider", modelType).increment(asLong(tokenUsage.get("totalTokens")));
        }
        if (stepCost > 0) {
            meterRegistry.counter("eddi.llm.cascade.cost", "provider", modelType).increment(stepCost);
        }
    }

    private void increment(String metric, String... tags) {
        if (meterRegistry != null) {
            meterRegistry.counter(metric, tags).increment();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // Retryable transient-failure signatures in an exception message.
    private static final Pattern RETRYABLE_MESSAGE = Pattern.compile("timeout|rate limit|too many requests|429|50[234]", Pattern.CASE_INSENSITIVE);

    /**
     * Check if an error is retryable (transient network / throttling errors). Same
     * intent as {@code AgentExecutionHelper}.
     */
    private static boolean isRetryableError(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.ConnectException || current instanceof java.net.UnknownHostException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && RETRYABLE_MESSAGE.matcher(message).find()) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Internal result of a single cascade step execution.
     */
    private record StepResult(String response, double confidence, AgentOrchestrator.ExecutionResult agentResult, Map<String, Object> tokenUsage,
            boolean streamedLive) {
    }
}
