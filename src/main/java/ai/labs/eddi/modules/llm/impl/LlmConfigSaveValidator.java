/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.validation.ConfigValidationPolicy;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.modules.llm.bootstrap.LlmModule;
import ai.labs.eddi.modules.llm.impl.builder.ModelParameterCatalog;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProviders;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Save-time structural check of an {@link LlmConfiguration}: what can never
 * work is refused when it is written instead of failing every turn of the agent
 * that uses it.
 * <p>
 * Checked, per task and per cascade step and judge: the provider {@code type}
 * is one the engine has a builder for; a model parameter that is present is not
 * blank; every numeric parameter the provider catalog declares a range for
 * ({@link ModelParameterCatalog}) is a number inside it; the self-hosted
 * endpoint's required fields ({@link OpenAiCompatibleConfigValidator}); the
 * cascade's hard errors ({@link CascadeConfigValidator}); the retry settings.
 * <p>
 * <b>Not checked</b>: a value that still holds a template or variable
 * ({@code {...}}, {@code ${...}}) — it is resolved at run time and checked
 * there; and that a vault reference exists, which depends on the deployment.
 * Ranges are only those the catalog declares: none are invented here.
 * <p>
 * How a finding is handled (refusal or warning) is decided by
 * {@link ConfigValidationPolicy}, not here.
 */
public final class LlmConfigSaveValidator {

    private static final String WHAT = "LLM configuration";

    private LlmConfigSaveValidator() {
    }

    /** Checks the document and reports through {@link ConfigValidationPolicy}. */
    public static void validate(LlmConfiguration config) {
        ConfigValidationPolicy.report(WHAT, problems(config));
    }

    /** Every problem found, one line each naming the field; empty when none. */
    static List<String> problems(LlmConfiguration config) {
        List<String> problems = new ArrayList<>();
        if (config == null || config.tasks() == null) {
            return problems;
        }
        for (int i = 0; i < config.tasks().size(); i++) {
            LlmConfiguration.Task task = config.tasks().get(i);
            if (task == null) {
                problems.add("tasks[" + i + "] is null");
                continue;
            }
            String path = "tasks[" + i + "]";
            checkProvider(path + ".type", task.getType(), true, problems);
            checkParameters(path + ".parameters", task.getType(), task.getParameters(), problems);
            checkRetry(path + ".retry", task.getRetry(), problems);
            checkCascade(path, task, problems);
        }
        try {
            CascadeConfigValidator.validate(config);
            OpenAiCompatibleConfigValidator.validate(config);
        } catch (WorkflowConfigurationException e) {
            problems.add(e.getMessage());
        } catch (RuntimeException e) {
            // A malformed nested block is reported by the checks above; never let the
            // deploy-time validators' own assumptions turn a save into a 500.
            problems.add("the configuration could not be checked: " + e.getClass().getSimpleName());
        }
        return problems;
    }

    private static void checkCascade(String path, LlmConfiguration.Task task, List<String> problems) {
        LlmConfiguration.ModelCascadeConfig cascade = task.getModelCascade();
        if (cascade == null) {
            return;
        }
        if (cascade.getSteps() != null) {
            for (int s = 0; s < cascade.getSteps().size(); s++) {
                LlmConfiguration.CascadeStep step = cascade.getSteps().get(s);
                String stepPath = path + ".modelCascade.steps[" + s + "]";
                if (step == null) {
                    problems.add(stepPath + " is null");
                    continue;
                }
                checkProvider(stepPath + ".type", step.getType(), true, problems);
                checkParameters(stepPath + ".parameters", step.getType(), step.getParameters(), problems);
                if (step.getTimeoutMs() != null && step.getTimeoutMs() <= 0) {
                    problems.add(stepPath + ".timeoutMs must be positive, got " + step.getTimeoutMs());
                }
            }
        }
        var judge = cascade.getJudgeModel();
        if (judge != null) {
            String judgePath = path + ".modelCascade.judgeModel";
            checkProvider(judgePath + ".type", judge.getType(), false, problems);
            checkParameters(judgePath + ".parameters", judge.getType(), judge.getParameters(), problems);
        }
    }

    private static void checkProvider(String field, String type, boolean required, List<String> problems) {
        if (type == null || type.isBlank()) {
            if (required) {
                problems.add(field + " is required: name an LLM provider such as 'openai' or 'anthropic'");
            }
            return;
        }
        if (isTemplated(type) || isKnownProvider(type)) {
            return;
        }
        problems.add(field + " '" + type + "' is not a known LLM provider (known: " + String.join(", ", knownProviders()) + ")");
    }

    /** Whether the engine has a model builder for this provider type. */
    static boolean isKnownProvider(String type) {
        // Exact match, like the builder map the model registry looks the type up in.
        return LlmModule.BUILT_IN_TYPES.contains(type)
                || OpenAiCompatibleProviders.all().stream().anyMatch(provider -> provider.id().equals(type));
    }

    private static List<String> knownProviders() {
        List<String> known = new ArrayList<>(LlmModule.BUILT_IN_TYPES);
        OpenAiCompatibleProviders.all().forEach(provider -> known.add(provider.id()));
        known.sort(String::compareTo);
        return known;
    }

    private static void checkParameters(String path, String type, Map<String, String> parameters, List<String> problems) {
        if (parameters == null || type == null || isTemplated(type)) {
            return;
        }
        ModelParameterCatalog catalog = ModelParameterCatalog.defaultCatalog();
        for (String key : ModelParameterCatalog.MODEL_KEYS) {
            if (parameters.containsKey(key) && parameters.get(key) != null && parameters.get(key).isBlank()) {
                problems.add(path + "." + key + " is blank: name a model or leave the key out");
            }
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank() || isTemplated(value)) {
                continue;
            }
            catalog.find(type, entry.getKey()).ifPresent(param -> checkRange(path + "." + entry.getKey(), param, value, problems));
        }
    }

    private static void checkRange(String field, ModelParameterCatalog.Param param, String value, List<String> problems) {
        boolean integer = "int".equals(param.type());
        if (!integer && !"double".equals(param.type())) {
            return;
        }
        double number;
        try {
            number = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            problems.add(field + " must be a number, got '" + value + "'");
            return;
        }
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            problems.add(field + " must be a finite number, got '" + value + "'");
            return;
        }
        if (integer && number != Math.rint(number)) {
            problems.add(field + " must be a whole number, got '" + value + "'");
            return;
        }
        if (param.min() != null && number < param.min() || param.max() != null && number > param.max()) {
            problems.add(field + " must be between " + bound(param.min(), "-infinity") + " and " + bound(param.max(), "infinity")
                    + " for this provider, got " + value);
        }
    }

    private static String bound(Double bound, String unbounded) {
        if (bound == null) {
            return unbounded;
        }
        return bound == Math.rint(bound) ? String.valueOf(bound.longValue()) : String.valueOf(bound);
    }

    private static void checkRetry(String path, RetryConfiguration retry, List<String> problems) {
        if (retry == null) {
            return;
        }
        if (retry.getMaxAttempts() != null && retry.getMaxAttempts() < 1) {
            problems.add(path + ".maxAttempts must be at least 1, got " + retry.getMaxAttempts());
        }
        if (retry.getBackoffDelayMs() != null && retry.getBackoffDelayMs() < 0) {
            problems.add(path + ".backoffDelayMs must not be negative, got " + retry.getBackoffDelayMs());
        }
        if (retry.getMaxBackoffDelayMs() != null && retry.getMaxBackoffDelayMs() < 0) {
            problems.add(path + ".maxBackoffDelayMs must not be negative, got " + retry.getMaxBackoffDelayMs());
        }
        if (retry.getBackoffMultiplier() != null && (retry.getBackoffMultiplier().isNaN() || retry.getBackoffMultiplier() < 1.0)) {
            problems.add(path + ".backoffMultiplier must be at least 1.0, got " + retry.getBackoffMultiplier());
        }
    }

    private static boolean isTemplated(String value) {
        return value.contains("{") || value.contains("${");
    }
}
