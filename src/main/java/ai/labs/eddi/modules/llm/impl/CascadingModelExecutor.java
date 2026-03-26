package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * Executes a multi-model cascade: tries a cheap/fast model first, evaluates
 * confidence, and escalates to a more powerful model only if confidence is
 * below the configured threshold.
 * <p>
 * Emits SSE events via {@link ConversationEventSink} so the frontend can
 * display real-time cascade status.
 * <p>
 * Edge-case handling:
 * <ul>
 * <li>Timeouts → treated as confidence 0.0, escalates to next step</li>
 * <li>Retryable errors (rate limits, 503) → retried per task config, then
 * escalates</li>
 * <li>Non-retryable errors (auth failure) → escalates immediately</li>
 * <li>All steps fail → throws aggregated {@link LifecycleException}</li>
 * <li>Budget exhausted → returns best response seen so far</li>
 * </ul>
 */
class CascadingModelExecutor {
    private static final Logger LOGGER = Logger.getLogger(CascadingModelExecutor.class);
    private static final ExecutorService TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "eddi-cascade-timeout");
        t.setDaemon(true);
        return t;
    });

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
     * @param trace
     *            list of per-step trace entries for audit
     * @param agentResult
     *            if agent mode was used, the agent execution result
     */
    record CascadeResult(String response, double confidence, int stepUsed, String modelType, List<Map<String, Object>> trace,
            AgentOrchestrator.ExecutionResult agentResult) {
    }

    private CascadingModelExecutor() {
        // utility class — all methods are static
    }

    /**
     * Execute the cascade. Tries each step in order until one meets its confidence
     * threshold (or the last step is reached).
     *
     * @param registry
     *            model registry for creating chat models per step
     * @param cascade
     *            cascade configuration (steps, strategy, evaluation)
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
     * @return the cascade result
     * @throws LifecycleException
     *             if all steps fail
     */
    static CascadeResult execute(ChatModelRegistry registry, ModelCascadeConfig cascade, List<ChatMessage> messages, String systemMessage,
            Map<String, String> baseParams, LlmConfiguration.Task task, IConversationMemory memory, AgentOrchestrator agentOrchestrator)
            throws LifecycleException {

        List<CascadeStep> steps = cascade.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw new LifecycleException("Model cascade enabled but no steps configured");
        }

        String evaluationStrategy = cascade.getEvaluationStrategy();
        boolean useAgentMode = cascade.isEnableInAgentMode() && task.isAgentMode();
        ConversationEventSink eventSink = memory.getEventSink();

        List<Map<String, Object>> trace = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        CascadeResult bestSoFar = null;

        for (int i = 0; i < steps.size(); i++) {
            CascadeStep step = steps.get(i);
            boolean isLastStep = (i == steps.size() - 1);

            // Resolve model type and merge params
            String modelType = step.getType() != null ? step.getType() : task.getType();
            Map<String, String> mergedParams = mergeParams(baseParams, step.getParameters());
            String modelName = mergedParams.getOrDefault("model", modelType);

            // Emit SSE: cascade step start
            if (eventSink != null) {
                eventSink.onCascadeStepStart(i, modelType, modelName, steps.size());
            }

            LOGGER.infof("Cascade step %d/%d: trying %s/%s", i, steps.size() - 1, modelType, modelName);
            long stepStart = System.currentTimeMillis();

            Map<String, Object> stepTrace = new LinkedHashMap<>();
            stepTrace.put("step", i);
            stepTrace.put("model", modelName);
            stepTrace.put("modelType", modelType);

            try {
                ChatModel chatModel = registry.getOrCreate(modelType, mergedParams);

                // Execute the model call with per-step timeout
                StepResult stepResult = executeStepWithTimeout(chatModel, messages, systemMessage, evaluationStrategy, mergedParams, task, step,
                        memory, agentOrchestrator, useAgentMode);

                long durationMs = System.currentTimeMillis() - stepStart;
                stepTrace.put("confidence", stepResult.confidence);
                stepTrace.put("durationMs", durationMs);

                // Track best response for budget-exhaustion fallback
                if (bestSoFar == null || stepResult.confidence > bestSoFar.confidence()) {
                    bestSoFar = new CascadeResult(stepResult.response, stepResult.confidence, i, modelType, trace, stepResult.agentResult);
                }

                // Check if this step meets the threshold
                if (isLastStep || step.getConfidenceThreshold() == null || stepResult.confidence >= step.getConfidenceThreshold()) {

                    stepTrace.put("status", "accepted");
                    trace.add(stepTrace);

                    LOGGER.infof("Cascade step %d accepted: confidence=%.2f, model=%s, durationMs=%d", i, stepResult.confidence, modelName,
                            durationMs);

                    return new CascadeResult(stepResult.response, stepResult.confidence, i, modelType, trace, stepResult.agentResult);
                }

                // Escalate
                stepTrace.put("status", "escalated");
                trace.add(stepTrace);

                // Emit SSE: cascade escalation
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

                LOGGER.warnf("Cascade step %d timed out after %dms, escalating", i, durationMs);

                if (!isLastStep && eventSink != null) {
                    eventSink.onCascadeEscalation(i, i + 1, 0.0, step.getConfidenceThreshold() != null ? step.getConfidenceThreshold() : 0.0,
                            "timeout", durationMs);
                }

                if (isLastStep) {
                    // Last step timed out — return best so far if available
                    if (bestSoFar != null) {
                        LOGGER.warn("All cascade steps exhausted (last timed out), returning best response");
                        return bestSoFar;
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

                LOGGER.warnf("Cascade step %d failed (%s): %s, escalating", i, errorType, e.getMessage());

                if (!isLastStep && eventSink != null) {
                    eventSink.onCascadeEscalation(i, i + 1, 0.0, step.getConfidenceThreshold() != null ? step.getConfidenceThreshold() : 0.0,
                            errorType, durationMs);
                }

                if (isLastStep) {
                    if (bestSoFar != null) {
                        LOGGER.warn("All cascade steps exhausted (last failed), returning best response");
                        return bestSoFar;
                    }
                    throw new LifecycleException("Model cascade failed: all steps exhausted. Errors: " + String.join("; ", errors), e);
                }
            }
        }

        // Should be unreachable — last step always accepted or throws
        if (bestSoFar != null) {
            return bestSoFar;
        }
        throw new LifecycleException("Model cascade failed: no steps produced a result");
    }

    /**
     * Execute a single cascade step with timeout.
     */
    private static StepResult executeStepWithTimeout(ChatModel chatModel, List<ChatMessage> messages, String systemMessage, String evaluationStrategy,
            Map<String, String> mergedParams, LlmConfiguration.Task task, CascadeStep step, IConversationMemory memory,
            AgentOrchestrator agentOrchestrator, boolean useAgentMode) throws Exception {

        long timeoutMs = step.getTimeoutMs() != null ? step.getTimeoutMs() : 30000L;

        Future<StepResult> future = TIMEOUT_EXECUTOR.submit(() -> {
            if (useAgentMode) {
                return executeAgentModeStep(chatModel, messages, systemMessage, evaluationStrategy, task, memory, agentOrchestrator);
            } else {
                return executeLegacyModeStep(chatModel, messages, systemMessage, evaluationStrategy, task);
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
    private static StepResult executeLegacyModeStep(ChatModel chatModel, List<ChatMessage> originalMessages, String systemMessage,
            String evaluationStrategy, LlmConfiguration.Task task) throws LifecycleException {

        // If using structured_output strategy, augment the system message
        List<ChatMessage> messages;
        if ("structured_output".equals(evaluationStrategy)) {
            messages = augmentMessagesForStructuredOutput(originalMessages, systemMessage);
        } else {
            messages = new ArrayList<>(originalMessages);
        }

        var chatResponse = AgentExecutionHelper.executeChatWithRetry(chatModel, messages, task);
        String responseText = chatResponse.aiMessage() != null ? chatResponse.aiMessage().text() : "";

        var evalResult = ConfidenceEvaluator.evaluate(evaluationStrategy, responseText, null);
        return new StepResult(evalResult.response(), evalResult.confidence(), null);
    }

    /**
     * Execute a step in agent mode (with tool-calling loop).
     */
    private static StepResult executeAgentModeStep(ChatModel chatModel, List<ChatMessage> originalMessages, String systemMessage,
            String evaluationStrategy, LlmConfiguration.Task task, IConversationMemory memory, AgentOrchestrator agentOrchestrator)
            throws LifecycleException {

        // For agent mode, filter out system messages (orchestrator adds its own)
        List<ChatMessage> chatMessagesWithoutSystem = originalMessages.stream().filter(m -> !(m instanceof SystemMessage))
                .collect(java.util.stream.Collectors.toList());

        var agentResult = agentOrchestrator.executeIfToolsEnabled(chatModel, systemMessage, chatMessagesWithoutSystem, task, memory);

        if (agentResult != null) {
            String responseText = agentResult.response();
            var evalResult = ConfidenceEvaluator.evaluate(evaluationStrategy, responseText, null);
            return new StepResult(evalResult.response(), evalResult.confidence(), agentResult);
        }

        // Agent mode returned null (no tools enabled) — fall back to legacy
        return executeLegacyModeStep(chatModel, originalMessages, systemMessage, evaluationStrategy, task);
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
                // Augment existing system message
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
     * Check if an error is retryable (same logic as AgentExecutionHelper).
     */
    private static boolean isRetryableError(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.ConnectException || current instanceof java.net.UnknownHostException) {
                return true;
            }

            String message = current.getMessage() != null ? current.getMessage().toLowerCase() : "";
            if (message.contains("timeout") || message.contains("rate limit") || message.contains("too many requests") || message.contains("503")
                    || message.contains("502") || message.contains("504") || message.contains("429")) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }

    /**
     * Internal result of a single cascade step execution.
     */
    private record StepResult(String response, double confidence, AgentOrchestrator.ExecutionResult agentResult) {
    }
}
