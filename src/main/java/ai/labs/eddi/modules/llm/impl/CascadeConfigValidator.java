/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.modules.llm.model.CascadingStrategy;
import ai.labs.eddi.modules.llm.model.EvaluationStrategy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;

/**
 * Deploy-time validation for {@link ModelCascadeConfig} and the task-level
 * pricing fields. Runs from {@code LlmTask.configure()} so misconfigurations
 * surface at agent deployment rather than per-turn mid-conversation.
 * <p>
 * <b>Backward compatibility:</b> conditions that older releases tolerated at
 * load (they still failed/degraded at runtime exactly as before — unknown
 * strategies, out-of-range thresholds, dead non-last steps, judge_model without
 * a judge) are emitted as <b>warnings</b>, not hard errors, so upgrading does
 * not stop a previously-loading agent from deploying. Only the <b>new</b>
 * pricing/ceiling fields (which no stored config predating this release can
 * contain) are hard errors on an invalid value.
 */
final class CascadeConfigValidator {
    private static final Logger LOGGER = Logger.getLogger(CascadeConfigValidator.class);

    /**
     * Recognized evaluationStrategy tokens, derived from the enum for warn
     * messages.
     */
    private static final List<String> VALID_EVALUATION_STRATEGIES = Arrays.stream(EvaluationStrategy.values())
            .map(EvaluationStrategy::configValue).toList();

    private CascadeConfigValidator() {
    }

    /** Validate every task's cascade config. Throws on invalid configuration. */
    static void validate(LlmConfiguration config) throws WorkflowConfigurationException {
        if (config == null || config.tasks() == null) {
            return;
        }
        for (LlmConfiguration.Task task : config.tasks()) {
            validateTask(task);
        }
    }

    private static void validateTask(LlmConfiguration.Task task) throws WorkflowConfigurationException {
        String taskId = task.getId() != null ? task.getId() : "<unnamed>";

        // Task-level pricing is NEW (same rationale as the cascade pricing below:
        // no stored config predating it can carry the field) — hard error on a
        // negative value, cascade or not.
        requireNonNegativeTaskPrice(taskId, "inputPricePer1M", task.getInputPricePer1M());
        requireNonNegativeTaskPrice(taskId, "outputPricePer1M", task.getOutputPricePer1M());
        ModelPricingValidator.validate(taskId, task);

        // Warnings only, never blocking: timeouts that contradict each other and a
        // JSON agent with no fallback path (TurnResilienceWarnings).
        for (String warning : TurnResilienceWarnings.taskWarnings(task)) {
            LOGGER.warnf("LLM task '%s': %s.", taskId, warning);
        }

        ModelCascadeConfig cascade = task.getModelCascade();
        if (cascade == null || !cascade.isEnabled()) {
            return;
        }

        List<CascadeStep> steps = cascade.getSteps();
        if (steps == null || steps.isEmpty()) {
            // The executor throws at runtime for empty steps (as before) — warn, don't
            // block the agent from loading.
            warn(taskId, "modelCascade is enabled but has no steps — the cascade will fail when it runs");
            return;
        }

        // strategy (legacy field — warn only)
        String strategy = cascade.getStrategy();
        if (strategy != null && CascadingStrategy.fromConfig(strategy) == null) {
            warn(taskId, "unknown cascade strategy '" + strategy + "' (expected 'cascade' or 'parallel') — running sequentially");
        } else if (CascadingStrategy.fromConfig(strategy) == CascadingStrategy.PARALLEL) {
            warn(taskId, "cascade strategy 'parallel' is not implemented yet — steps will run sequentially");
        }

        // evaluationStrategy (legacy field — warn only)
        String evalStrategy = cascade.getEvaluationStrategy();
        if (evalStrategy != null && EvaluationStrategy.fromConfig(evalStrategy) == null) {
            warn(taskId, "unknown evaluationStrategy '" + evalStrategy + "' (expected one of " + VALID_EVALUATION_STRATEGIES
                    + ") — defaulting to structured_output");
        }

        if (EvaluationStrategy.fromConfig(evalStrategy) == EvaluationStrategy.JUDGE_MODEL) {
            var judge = cascade.getJudgeModel();
            if (judge == null || isBlank(judge.getType())) {
                warn(taskId, "evaluationStrategy 'judge_model' has no judgeModel with a 'type' — confidence will fall back to heuristic");
            }
        }

        // Only the judge reads the acceptance criterion: without one it is dead config.
        if (!isBlank(cascade.getAcceptanceCriterion()) && (cascade.getJudgeModel() == null || isBlank(cascade.getJudgeModel().getType()))) {
            warn(taskId, "acceptanceCriterion is set but there is no judgeModel with a 'type' — only the judge reads it, so it has no effect");
        }

        // Cross-provider credentials: a step of another provider type never inherits
        // the task's apiKey, endpoint or headers (CascadeCredentialPolicy), so one
        // that would have relied on it must bring its own. A hard error — the old
        // behaviour released one vendor's secret to another vendor's host. The judge
        // never inherited the task's parameters.
        List<String> credentialProblems = CascadeCredentialPolicy.problems(new LlmConfiguration(List.of(task)));
        if (!credentialProblems.isEmpty()) {
            throw new WorkflowConfigurationException("Invalid modelCascade: " + String.join("; ", credentialProblems));
        }

        // convertToObject + structured_output → auto-downgraded at runtime (warn once).
        // Use fromConfigOrDefault so an unknown/blank token — which
        // resolveEffectiveStrategy
        // also resolves to structured_output and then downgrades — warns too (validator
        // ↔
        // runtime parity).
        boolean convertToObject = task.getParameters() != null && Boolean.parseBoolean(task.getParameters().get("convertToObject"));
        if (convertToObject && EvaluationStrategy.fromConfigOrDefault(evalStrategy) == EvaluationStrategy.STRUCTURED_OUTPUT) {
            LOGGER.warnf("LLM task '%s': convertToObject=true is incompatible with the structured_output confidence wrapper — "
                    + "the cascade will use %s for confidence evaluation instead.", taskId,
                    cascade.getJudgeModel() != null ? "judge_model" : "heuristic");
        }

        // Ceilings + pricing are NEW fields — no stored config predating this release
        // can contain them, so an invalid value is a fresh typo worth failing fast on.
        if (cascade.getMaxTotalDurationMs() != null && cascade.getMaxTotalDurationMs() <= 0) {
            throw fail(taskId, "maxTotalDurationMs must be > 0");
        }
        if (cascade.getMaxCostPerRun() != null && cascade.getMaxCostPerRun() < 0) {
            throw fail(taskId, "maxCostPerRun must be >= 0");
        }
        requireNonNegativePrice(taskId, "cascade inputPricePer1M", cascade.getInputPricePer1M());
        requireNonNegativePrice(taskId, "cascade outputPricePer1M", cascade.getOutputPricePer1M());
        // New fields, so a hard error breaks no stored config (same rule as above).
        if (cascade.getJudgeModel() != null) {
            requireNonNegativePrice(taskId, "judgeModel inputPricePer1M", cascade.getJudgeModel().getInputPricePer1M());
            requireNonNegativePrice(taskId, "judgeModel outputPricePer1M", cascade.getJudgeModel().getOutputPricePer1M());
        }

        // per-step
        for (int i = 0; i < steps.size(); i++) {
            CascadeStep step = steps.get(i);
            boolean isLast = i == steps.size() - 1;
            Double threshold = step.getConfidenceThreshold();

            // Legacy fields (threshold, timeoutMs) — warn only.
            if (threshold != null && (threshold < 0.0 || threshold > 1.0)) {
                warn(taskId, "step " + i + " confidenceThreshold " + threshold + " is outside [0.0, 1.0]; confidence is clamped to 1.0 so this step "
                        + (threshold > 1.0 ? "always escalates" : "always accepts"));
            }
            if (!isLast && threshold == null) {
                warn(taskId, "step " + i + " is not the last step and has no confidenceThreshold — it is always accepted, "
                        + "making later steps unreachable");
            }
            if (step.getTimeoutMs() != null && step.getTimeoutMs() <= 0) {
                warn(taskId, "step " + i + " timeoutMs " + step.getTimeoutMs() + " is not positive — the step will time out immediately");
            }
            // Pricing is NEW — hard error on a negative value.
            requireNonNegativePrice(taskId, "step " + i + " inputPricePer1M", step.getInputPricePer1M());
            requireNonNegativePrice(taskId, "step " + i + " outputPricePer1M", step.getOutputPricePer1M());
        }
    }

    private static void requireNonNegativePrice(String taskId, String what, Double price) throws WorkflowConfigurationException {
        if (price != null && price < 0) {
            throw fail(taskId, what + " must be >= 0");
        }
    }

    private static void requireNonNegativeTaskPrice(String taskId, String what, Double price) throws WorkflowConfigurationException {
        if (price != null && price < 0) {
            throw new WorkflowConfigurationException("Invalid LLM task '" + taskId + "': " + what + " must be >= 0");
        }
    }

    private static void warn(String taskId, String message) {
        LOGGER.warnf("LLM task '%s': modelCascade — %s.", taskId, message);
    }

    private static WorkflowConfigurationException fail(String taskId, String message) {
        return new WorkflowConfigurationException("Invalid modelCascade in LLM task '" + taskId + "': " + message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
