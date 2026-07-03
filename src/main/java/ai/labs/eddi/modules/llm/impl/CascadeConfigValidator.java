/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deploy-time validation for {@link ModelCascadeConfig}. Runs from
 * {@code LlmTask.configure()} so misconfigurations fail fast at agent
 * deployment rather than surfacing mid-conversation. Emits one-time warnings
 * (not per-turn) for legal-but-degraded combinations.
 */
final class CascadeConfigValidator {
    private static final Logger LOGGER = Logger.getLogger(CascadeConfigValidator.class);

    private static final Set<String> VALID_EVALUATION_STRATEGIES = Set.of("structured_output", "heuristic", "judge_model", "none");
    private static final Set<String> KNOWN_STRATEGIES = Set.of("cascade", "parallel");

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
            throw fail(taskId, "modelCascade is enabled but has no steps");
        }

        // strategy
        String strategy = cascade.getStrategy();
        if (strategy != null && !KNOWN_STRATEGIES.contains(strategy.toLowerCase())) {
            throw fail(taskId, "unknown cascade strategy '" + strategy + "' (expected 'cascade' or 'parallel')");
        }
        if (strategy != null && "parallel".equalsIgnoreCase(strategy)) {
            LOGGER.warnf("LLM task '%s': cascade strategy 'parallel' is not implemented yet — steps will run sequentially.", taskId);
        }

        // evaluationStrategy
        String evalStrategy = cascade.getEvaluationStrategy();
        if (evalStrategy != null && !VALID_EVALUATION_STRATEGIES.contains(evalStrategy.toLowerCase())) {
            throw fail(taskId, "unknown evaluationStrategy '" + evalStrategy + "' (expected one of " + VALID_EVALUATION_STRATEGIES + ")");
        }

        boolean isJudgeStrategy = "judge_model".equalsIgnoreCase(evalStrategy);
        if (isJudgeStrategy) {
            var judge = cascade.getJudgeModel();
            if (judge == null || isBlank(judge.getType())) {
                throw fail(taskId, "evaluationStrategy 'judge_model' requires a judgeModel config with a 'type'");
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
        if (convertToObject && (evalStrategy == null || "structured_output".equalsIgnoreCase(evalStrategy))) {
            LOGGER.warnf("LLM task '%s': convertToObject=true is incompatible with the structured_output confidence wrapper — "
                    + "the cascade will use %s for confidence evaluation instead.", taskId,
                    cascade.getJudgeModel() != null ? "judge_model" : "heuristic");
        }

        // ceilings + pricing
        if (cascade.getMaxTotalDurationMs() != null && cascade.getMaxTotalDurationMs() <= 0) {
            throw fail(taskId, "maxTotalDurationMs must be > 0");
        }
        if (cascade.getMaxCostPerRun() != null && cascade.getMaxCostPerRun() < 0) {
            throw fail(taskId, "maxCostPerRun must be >= 0");
        }
        checkPrice(taskId, "cascade inputPricePer1M", cascade.getInputPricePer1M());
        checkPrice(taskId, "cascade outputPricePer1M", cascade.getOutputPricePer1M());

        // per-step
        for (int i = 0; i < steps.size(); i++) {
            CascadeStep step = steps.get(i);
            boolean isLast = i == steps.size() - 1;
            Double threshold = step.getConfidenceThreshold();

            if (threshold != null && (threshold < 0.0 || threshold > 1.0)) {
                throw fail(taskId, "step " + i + " confidenceThreshold must be within [0.0, 1.0] (was " + threshold
                        + "); values > 1.0 are unreachable because confidence is clamped to 1.0");
            }
            if (!isLast && threshold == null) {
                throw fail(taskId, "step " + i + " is not the last step and must define a confidenceThreshold "
                        + "(a null threshold on a non-last step is always accepted, making later steps unreachable)");
            }
            if (step.getTimeoutMs() != null && step.getTimeoutMs() <= 0) {
                throw fail(taskId, "step " + i + " timeoutMs must be > 0");
            }
            checkPrice(taskId, "step " + i + " inputPricePer1M", step.getInputPricePer1M());
            checkPrice(taskId, "step " + i + " outputPricePer1M", step.getOutputPricePer1M());
        }
    }

    private static void checkPrice(String taskId, String what, Double price) throws WorkflowConfigurationException {
        if (price != null && price < 0) {
            throw fail(taskId, what + " must be >= 0");
        }
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
