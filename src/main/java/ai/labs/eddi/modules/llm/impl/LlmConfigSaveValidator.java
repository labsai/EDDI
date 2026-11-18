/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.LenientConfigWrite;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.modules.llm.bootstrap.LlmModule;
import ai.labs.eddi.modules.llm.impl.builder.ModelParameterCatalog;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProviders;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Save-time structural check of an {@link LlmConfiguration}: a value that can
 * never work is refused when it is written instead of failing every turn of the
 * agent that uses it.
 * <p>
 * <b>Only what fails at run time is refused.</b> Each rule names the runtime
 * code that fails on the refused value; a value the runtime tolerates (falls
 * back, ignores, clamps) is not checked here at all:
 * <ul>
 * <li>a task's {@code type} that is missing or not a provider the engine has a
 * builder for, when no cascade is configured — the registry lookup is exact and
 * there is no default ({@code ChatModelRegistry.getOrCreate},
 * {@code UnsupportedLlmTaskException}, reached from {@code LlmTask.execute} on
 * every turn). With a configured cascade the task model is only built on turns
 * the cascade is skipped (tools present), so it is not refused;</li>
 * <li>a cascade step whose effective type (its own, or the task's when it has
 * none — {@code CascadingModelExecutor.resolveStepModel}) is blank or unknown:
 * the step fails on every call and the cascade escalates past it;</li>
 * <li>a cascade step {@code timeoutMs <= 0}: it times out at once on every call
 * ({@code CascadingModelExecutor.executeStepWithTimeout});</li>
 * <li>a numeric parameter the provider catalog ({@link ModelParameterCatalog})
 * declares a range for, outside that range, or NaN/infinite: the builders send
 * it unchanged ({@code ModelParameterValues.doubleValue}) and the provider's
 * API refuses the request;</li>
 * <li>{@code retry.maxAttempts < 1}:
 * {@code RetryConfiguration.executeWithRetry} makes no attempt at all on the
 * non-streaming paths;</li>
 * <li>the deploy-time hard errors of {@link CascadeConfigValidator} and
 * {@link OpenAiCompatibleConfigValidator}, which refuse the deployment.</li>
 * </ul>
 * <b>Deliberately not checked</b>, because the runtime copes: a blank model key
 * (treated as the provider default by every builder), a parameter that is not a
 * number or a fraction for an integer parameter (ignored with a warning by
 * {@code ModelParameterValues}), negative backoff delays or a multiplier below
 * 1 (clamped to no delay), the judge model (falls back to the heuristic), a
 * value that still holds a template or variable ({@code {...}},
 * {@code ${...}}), and whether a vault reference exists.
 * <p>
 * An interactive save (REST, MCP, the Manager) is refused with an
 * {@link IllegalArgumentException}, which the API answers with a 400. The write
 * of a configuration that exists already — import, sync, upgrade, duplicate,
 * setup, API-agent sync, rollback — runs inside {@link LenientConfigWrite} and
 * only logs; the caller that reports warnings collects them with
 * {@link #problems(LlmConfiguration)}.
 */
public final class LlmConfigSaveValidator {

    private static final Logger LOGGER = Logger.getLogger(LlmConfigSaveValidator.class);

    private static final String WHAT = "LLM configuration";

    private LlmConfigSaveValidator() {
    }

    /**
     * Refuses an interactive save that cannot work; inside
     * {@link LenientConfigWrite} only logs.
     */
    public static void validate(LlmConfiguration config) {
        List<String> problems = problems(config);
        if (problems.isEmpty()) {
            return;
        }
        if (!LenientConfigWrite.isActive()) {
            throw new IllegalArgumentException("Invalid " + WHAT + ": " + String.join("; ", problems));
        }
        problems.forEach(problem -> LOGGER.warn("LLM configuration written by import, sync, copy or setup: " + LogSanitizer.sanitize(problem)));
    }

    /**
     * Every problem found, one line each naming the field, prefixed with the kind
     * of document; empty when none.
     */
    public static List<String> problems(LlmConfiguration config) {
        List<String> problems = new ArrayList<>();
        for (String problem : findProblems(config)) {
            problems.add(WHAT + ": " + problem);
        }
        return problems;
    }

    /** {@link #problems} without the prefix. */
    public static List<String> findProblems(LlmConfiguration config) {
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
            if (!hasConfiguredCascade(task)) {
                checkProvider(path + ".type", task.getType(), problems);
            }
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

    /** The same test {@code LlmTask} applies before it lets the cascade run. */
    private static boolean hasConfiguredCascade(LlmConfiguration.Task task) {
        var cascade = task.getModelCascade();
        return cascade != null && cascade.isEnabled() && cascade.getSteps() != null && !cascade.getSteps().isEmpty();
    }

    private static void checkCascade(String path, LlmConfiguration.Task task, List<String> problems) {
        LlmConfiguration.ModelCascadeConfig cascade = task.getModelCascade();
        if (cascade == null || cascade.getSteps() == null) {
            return;
        }
        for (int s = 0; s < cascade.getSteps().size(); s++) {
            LlmConfiguration.CascadeStep step = cascade.getSteps().get(s);
            String stepPath = path + ".modelCascade.steps[" + s + "]";
            if (step == null) {
                problems.add(stepPath + " is null");
                continue;
            }
            // A step without a type inherits the task's (resolveStepModel); a blank one
            // does not, and is looked up as "".
            String effectiveType = step.getType() != null ? step.getType() : task.getType();
            String field = step.getType() != null ? stepPath + ".type" : stepPath + ".type (inherited from " + path + ".type)";
            checkProvider(field, effectiveType, problems);
            checkParameters(stepPath + ".parameters", effectiveType, step.getParameters(), problems);
            if (step.getTimeoutMs() != null && step.getTimeoutMs() <= 0) {
                problems.add(stepPath + ".timeoutMs must be positive, got " + step.getTimeoutMs());
            }
        }
    }

    private static void checkProvider(String field, String type, List<String> problems) {
        if (type == null || type.isBlank()) {
            problems.add(field + " is required: name an LLM provider such as 'openai' or 'anthropic'");
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
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank() || isTemplated(value)) {
                continue;
            }
            catalog.find(type, entry.getKey()).ifPresent(param -> checkRange(path + "." + entry.getKey(), param, value, problems));
        }
    }

    private static void checkRange(String field, ModelParameterCatalog.Param param, String value, List<String> problems) {
        if (!"int".equals(param.type()) && !"double".equals(param.type())) {
            return;
        }
        double number;
        try {
            // Parsed exactly as ModelParameterValues parses it: a value that does not
            // parse there is ignored with a warning (provider default), so it is not
            // refused here either — that includes "1.5" and "100.0" for an int.
            number = "int".equals(param.type()) ? Integer.parseInt(value.trim()) : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            // Double.valueOf accepts these, and the request then carries them.
            problems.add(field + " must be a finite number, got '" + value + "'");
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
        if (retry != null && retry.getMaxAttempts() != null && retry.getMaxAttempts() < 1) {
            problems.add(path + ".maxAttempts must be at least 1, got " + retry.getMaxAttempts());
        }
    }

    private static boolean isTemplated(String value) {
        return value.contains("{") || value.contains("${");
    }
}
