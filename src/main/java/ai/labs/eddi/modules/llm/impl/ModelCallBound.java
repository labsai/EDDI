/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import ai.labs.eddi.modules.llm.pricing.MeteredTokenUsage;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import dev.langchain4j.data.message.ChatMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The most one LLM task can cost, for the opt-in tenant cost reservation
 * ({@code eddi.tenant.quota.cost-reservation.enabled}).
 * <p>
 * A model call costs at most {@code prompt tokens x input price + max output
 * tokens x output price}. A task is a single call, or - an agent-mode task - up
 * to {@code 1 + maxToolIterations} rounds, each with a prompt that can hold, at
 * most, the opening prompt plus the in-turn tool context cap
 * ({@code maxToolContextTokens}). A cascade is every step, one after the other.
 * <p>
 * The prompt is sized from the messages that will be sent, at a deliberately
 * low {@value #CHARS_PER_TOKEN} characters per token so that an estimate errs
 * high. Only <em>priced</em> calls count, like metering itself: a model with no
 * price in the configuration or the catalog bounds to {@code 0}, and the
 * ordinary gate is then the only check.
 */
final class ModelCallBound {

    /** Characters per token assumed when sizing a prompt; low on purpose. */
    static final int CHARS_PER_TOKEN = 3;

    private ModelCallBound() {
    }

    /**
     * The most the task's model phase can cost.
     *
     * @param messages
     *            what the first request carries (system, history, user)
     * @param params
     *            the task's processed model parameters, for its output-token cap
     * @param modelType
     *            the provider id, for the provider's default output cap
     * @param modelName
     *            the resolved model name, for a catalog price
     * @param defaultMaxOutputTokens
     *            the cap assumed for a model that sets none
     */
    static double phaseMaxUsd(Task task, boolean cascadeActive, List<ChatMessage> messages, Map<String, String> params, String modelType,
                              String modelName, int defaultMaxOutputTokens) {
        long promptTokens = promptTokens(messages);
        int rounds = 1;
        long toolTokens = 0;
        if (task.isAgentMode()) {
            rounds += ToolLoopRunner.effectiveMaxToolIterations(task);
            int toolBudget = task.getMaxToolContextTokens() != null
                    ? task.getMaxToolContextTokens()
                    : ToolContextBudget.DEFAULT_MAX_TOOL_CONTEXT_TOKENS;
            toolTokens = Math.max(0, toolBudget);
        }
        if (cascadeActive && task.getModelCascade() != null && task.getModelCascade().getSteps() != null) {
            double total = 0.0;
            for (CascadeStep step : task.getModelCascade().getSteps()) {
                ModelPrices prices = ModelPrices.ofCascadeStep(step, task.getModelCascade());
                int maxOut = outputCap(step.getParameters(), step.getType(), defaultMaxOutputTokens);
                total += usd(prices, null, rounds, promptTokens, toolTokens, maxOut);
            }
            return total;
        }
        return usd(ModelPrices.ofTask(task), modelName, rounds, promptTokens, toolTokens, outputCap(params, modelType, defaultMaxOutputTokens));
    }

    /**
     * The most the rest of a paused tool loop can cost once it is resumed: the
     * rounds the loop has left, each with the frozen transcript (sized from its
     * serialized JSON, which errs high) plus the tool context cap.
     */
    static double resumeMaxUsd(Task task, PendingToolCallBatch batch, Map<String, String> params, String modelType, String modelName,
                               int defaultMaxOutputTokens) {
        int rounds = Math.max(1, ToolLoopRunner.effectiveMaxToolIterations(task) - batch.getIterationIndex() + 1);
        long transcriptTokens = batch.getChatTranscriptJson() == null ? 0 : batch.getChatTranscriptJson().length() / CHARS_PER_TOKEN + 1;
        long toolTokens = Math.max(0, toolContextTokens(task));
        boolean cascadeStep = batch.getCascadeStepIndex() != null;
        ModelPrices prices = ModelPrices.forRun(task, batch.getCascadeStepIndex());
        return usd(prices, cascadeStep ? null : modelName, rounds, transcriptTokens + toolTokens, toolTokens,
                outputCap(params, modelType, defaultMaxOutputTokens));
    }

    /**
     * {@code rounds} calls, each with the opening prompt plus the tool context cap.
     */
    static double usd(ModelPrices prices, String modelName, int rounds, long promptTokens, long toolTokens, int maxOutputTokens) {
        double total = 0.0;
        for (int round = 0; round < rounds; round++) {
            long in = promptTokens + (round == 0 ? 0 : toolTokens);
            Map<String, Object> usage = new HashMap<>();
            usage.put("inputTokens", in);
            usage.put("outputTokens", (long) maxOutputTokens);
            if (modelName != null) {
                usage.put(MeteredTokenUsage.PRICING_MODEL, modelName);
            }
            total += prices.cost(usage);
        }
        return Double.isFinite(total) ? total : 0.0;
    }

    static long promptTokens(List<ChatMessage> messages) {
        long chars = 0;
        if (messages != null) {
            for (ChatMessage message : messages) {
                chars += String.valueOf(message).length();
            }
        }
        return chars / CHARS_PER_TOKEN + 1;
    }

    /**
     * The model's own output cap ({@code maxTokens} / {@code maxOutputTokens}, or
     * the provider's documented default), else {@code defaultMaxOutputTokens}.
     */
    static int outputCap(Map<String, String> params, String modelType, int defaultMaxOutputTokens) {
        Integer configured = FormatRetryRunner.resolveBaseMaxOutputTokens(params, modelType);
        return configured != null && configured > 0 ? configured : Math.max(1, defaultMaxOutputTokens);
    }

    /** The tool-context cap the loop runs with, for tests. */
    static int toolContextTokens(LlmConfiguration.Task task) {
        return task.getMaxToolContextTokens() != null ? task.getMaxToolContextTokens() : ToolContextBudget.DEFAULT_MAX_TOOL_CONTEXT_TOKENS;
    }
}
