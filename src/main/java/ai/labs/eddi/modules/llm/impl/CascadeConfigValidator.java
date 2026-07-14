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
import java.util.Map;

/**
 * Deploy-time validation for {@link ModelCascadeConfig}. Runs from
 * {@code LlmTask.configure()} so misconfigurations surface at agent deployment
 * rather than per-turn mid-conversation.
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
        ModelCascadeConfig cascade = task.getModelCascade();
        if (cascade == null || !cascade.isEnabled()) {
            return;
        }

        String taskId = task.getId() != null ? task.getId() : "<unnamed>";

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

        // Cross-provider credential check. Step/judge parameters are merged OVER the
        // task parameters (step wins), so a step targeting a DIFFERENT provider than
        // the task silently inherits the task's apiKey — the wrong key for that
        // provider (fails at runtime as a 401 that looks like an escalation). Warn at
        // deploy time so the step/judge is given its own credentials. Not a hard error
        // because some providers (e.g. Ollama, Bedrock) don't use apiKey.
        boolean baseHasApiKey = task.getParameters() != null && task.getParameters().containsKey("apiKey");
        for (int i = 0; i < steps.size(); i++) {
            CascadeStep s = steps.get(i);
            warnIfCrossProviderMissingCredentials(taskId, "step " + i, task.getType(), s.getType(), s.getParameters(), baseHasApiKey);
        }
        if (cascade.getJudgeModel() != null) {
            warnIfCrossProviderMissingCredentials(taskId, "judgeModel", task.getType(), cascade.getJudgeModel().getType(),
                    cascade.getJudgeModel().getParameters(), baseHasApiKey);
        }

        // convertToObject + structured_output → auto-downgraded at runtime (warn once).
        boolean convertToObject = task.getParameters() != null && Boolean.parseBoolean(task.getParameters().get("convertToObject"));
        if (convertToObject && (evalStrategy == null || EvaluationStrategy.fromConfig(evalStrategy) == EvaluationStrategy.STRUCTURED_OUTPUT)) {
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

    private static void warn(String taskId, String message) {
        LOGGER.warnf("LLM task '%s': modelCascade — %s.", taskId, message);
    }

    /**
     * Warn when a step / judge uses a different provider than the task but does not
     * define its own {@code apiKey}, so it would inherit the task's (wrong) key.
     */
    private static void warnIfCrossProviderMissingCredentials(String taskId, String where, String taskType, String stepType,
                                                              Map<String, String> stepParams, boolean baseHasApiKey) {
        if (!baseHasApiKey || stepType == null || stepType.equalsIgnoreCase(taskType)) {
            return; // no key to inherit, or same provider — inheriting is correct
        }
        boolean stepHasApiKey = stepParams != null && stepParams.containsKey("apiKey");
        if (!stepHasApiKey) {
            LOGGER.warnf("LLM task '%s': %s uses provider '%s' (different from the task provider '%s') but does not define its own 'apiKey' — "
                    + "it would inherit the task's apiKey, which is likely incorrect for a different provider. "
                    + "Give the step/judge its own credentials (apiKey, baseUrl, etc.).", taskId, where, stepType, taskType);
        }
    }

    private static WorkflowConfigurationException fail(String taskId, String message) {
        return new WorkflowConfigurationException("Invalid modelCascade in LLM task '" + taskId + "': " + message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
