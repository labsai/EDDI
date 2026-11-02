/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;

import java.util.List;
import java.util.Map;

/**
 * The configured per-1M-token prices of the model a phase actually ran on — the
 * one place that decides <em>which</em> prices apply. {@link TokenPricing} does
 * the arithmetic; this decides its inputs.
 * <p>
 * The decision used to be repeated wherever a model phase was priced (the tool
 * loop's mid-loop ceiling, its pause spend, the cascade's steps, its re-asks,
 * the resume path, reprompts), and it had drifted: an agent-mode cascade step
 * was priced at the <em>task's</em> rates inside the tool loop while its final
 * cost used the step's — so with prices set only on the cascade the loop's
 * ceiling ignored token spend and a pause inside a cascade step recorded $0.
 * <ul>
 * <li>a task's own model: the task's {@code inputPricePer1M} /
 * {@code outputPricePer1M};</li>
 * <li>a cascade step: the step's prices, else the cascade's defaults — never
 * the task's (docs/model-cascade.md).</li>
 * </ul>
 *
 * @param inputPer1M
 *            USD per 1M input tokens, or null when unpriced
 * @param outputPer1M
 *            USD per 1M output tokens, or null when unpriced
 */
record ModelPrices(Double inputPer1M, Double outputPer1M) {

    /** No prices: every phase is free. */
    static final ModelPrices UNPRICED = new ModelPrices(null, null);

    /** The task's own model. */
    static ModelPrices ofTask(Task task) {
        return task == null ? UNPRICED : new ModelPrices(task.getInputPricePer1M(), task.getOutputPricePer1M());
    }

    /** A cascade step: its own prices, else the cascade's defaults. */
    static ModelPrices ofCascadeStep(CascadeStep step, ModelCascadeConfig cascade) {
        if (step == null) {
            return cascade == null ? UNPRICED : new ModelPrices(cascade.getInputPricePer1M(), cascade.getOutputPricePer1M());
        }
        Double in = step.getInputPricePer1M() != null ? step.getInputPricePer1M() : cascade != null ? cascade.getInputPricePer1M() : null;
        Double out = step.getOutputPricePer1M() != null ? step.getOutputPricePer1M() : cascade != null ? cascade.getOutputPricePer1M() : null;
        return new ModelPrices(in, out);
    }

    /**
     * The model a run of {@code task} used: cascade step {@code cascadeStepIndex}
     * when the task's cascade is enabled and has that step, otherwise the task's
     * own model — the same resolution {@code LlmTask.pausedCascadeStep} applies
     * when a paused cascade step resumes.
     */
    static ModelPrices forRun(Task task, Integer cascadeStepIndex) {
        if (task == null) {
            return UNPRICED;
        }
        ModelCascadeConfig cascade = task.getModelCascade();
        List<CascadeStep> steps = cascade != null && cascade.isEnabled() ? cascade.getSteps() : null;
        if (cascadeStepIndex == null || steps == null || cascadeStepIndex < 0 || cascadeStepIndex >= steps.size()) {
            return ofTask(task);
        }
        return ofCascadeStep(steps.get(cascadeStepIndex), cascade);
    }

    /**
     * {@code tokenUsage} ({@code inputTokens}/{@code outputTokens}) at these
     * prices.
     */
    double cost(Map<?, ?> tokenUsage) {
        return TokenPricing.cost(inputPer1M, outputPer1M, tokenUsage);
    }
}
