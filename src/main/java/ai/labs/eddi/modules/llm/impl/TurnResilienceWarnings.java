/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.RetryApiCallInstruction;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Deploy-time, <b>warning-only</b> checks that a task's timeout and retry
 * settings cannot honour each other or the turn's deadline.
 * <p>
 * Nothing here blocks a deployment: a stored config that predates these checks
 * must keep loading. Each method returns the warnings instead of logging them
 * so the rules can be tested; the callers log them once, when the agent is
 * deployed.
 * <p>
 * The numbers are <em>static worst cases</em> — every attempt of every retry
 * layer running to its timeout. A real turn is nearly always far shorter; the
 * point is that a config whose worst case exceeds the budget has no guarantee
 * at all.
 */
final class TurnResilienceWarnings {

    /** The provider client's own default when a model sets no {@code timeout}. */
    static final long DEFAULT_MODEL_TIMEOUT_MS = 60_000L;

    /** Per-call default of {@code eddi.httpcalls.default-timeout-millis}. */
    static final long DEFAULT_HTTP_TIMEOUT_MS = 30_000L;

    private static final long MAX_BACKOFF_CEILING_MS = 30_000L;
    private static final long MAX_TOTAL_BACKOFF_MS = 60_000L;

    private TurnResilienceWarnings() {
    }

    /** Per-task findings that need no agent context. */
    static List<String> taskWarnings(LlmConfiguration.Task task) {
        List<String> warnings = new ArrayList<>();
        ModelCascadeConfig cascade = task.getModelCascade();
        if (cascade != null && cascade.isEnabled() && cascade.getSteps() != null) {
            int index = 0;
            for (CascadeStep step : cascade.getSteps()) {
                Long modelTimeout = parseMs(CascadingModelExecutor.mergeParams(task.getParameters(), step.getParameters()).get("timeout"));
                Long stepTimeout = step.getTimeoutMs();
                if (modelTimeout != null && stepTimeout != null && modelTimeout > stepTimeout) {
                    warnings.add("cascade step " + index + ": the model's timeout (" + modelTimeout + "ms) is longer than the step's timeoutMs ("
                            + stepTimeout + "ms); the engine clamps the provider request to the step, but the config reads as if the model "
                            + "could run longer — set them equal");
                }
                index++;
            }
        }
        if (isTrue(task.getParameters() == null ? null : task.getParameters().get("convertToObject")) && !hasFallbackPath(task)) {
            warnings.add("convertToObject is on but there is no fallback path: enable responseValidation (onEmpty/onTruncation/... = fallback) "
                    + "or an onError fallback, and give the output template a default (e.g. {properties.x ?: 'Sorry, ...'}), or a failed "
                    + "turn renders an empty answer or an HTTP 500");
        }
        return warnings;
    }

    /**
     * Worst-case milliseconds one LLM task can spend: every in-step attempt to its
     * timeout, summed over cascade steps, bounded by the cascade's own
     * {@code maxTotalDurationMs}.
     */
    static long worstCaseLlmMs(LlmConfiguration.Task task) {
        RetryConfiguration retry = task.getRetry();
        ModelCascadeConfig cascade = task.getModelCascade();
        if (cascade != null && cascade.isEnabled() && cascade.getSteps() != null && !cascade.getSteps().isEmpty()) {
            long sum = 0;
            for (CascadeStep step : cascade.getSteps()) {
                Long modelTimeout = parseMs(CascadingModelExecutor.mergeParams(task.getParameters(), step.getParameters()).get("timeout"));
                long perModel = modelWorstCaseMs(retry, modelTimeout);
                long stepBudget = step.getTimeoutMs() != null ? step.getTimeoutMs() : 30_000L;
                sum += Math.min(perModel, stepBudget);
            }
            Long ceiling = cascade.getMaxTotalDurationMs();
            return ceiling != null ? Math.min(sum, ceiling) : sum;
        }
        return modelWorstCaseMs(retry, task.getParameters() == null ? null : parseMs(task.getParameters().get("timeout")));
    }

    private static long modelWorstCaseMs(RetryConfiguration retry, Long timeoutMs) {
        long timeout = timeoutMs != null ? timeoutMs : DEFAULT_MODEL_TIMEOUT_MS;
        int attempts = retry != null && retry.getMaxAttempts() != null ? RetryConfiguration.clampAttempts(retry.getMaxAttempts()) : 3;
        attempts = Math.max(1, attempts);
        long delay = retry != null && retry.getBackoffDelayMs() != null ? Math.max(0, retry.getBackoffDelayMs()) : 1000L;
        double multiplier = retry != null && retry.getBackoffMultiplier() != null ? Math.max(1.0, retry.getBackoffMultiplier()) : 2.0;
        long maxDelay = Math.min(MAX_BACKOFF_CEILING_MS,
                retry != null && retry.getMaxBackoffDelayMs() != null ? retry.getMaxBackoffDelayMs() : 10_000L);
        long backoff = 0;
        long current = Math.min(delay, maxDelay);
        for (int i = 1; i < attempts; i++) {
            backoff += current;
            current = Math.min((long) (current * multiplier), maxDelay);
        }
        return attempts * timeout + Math.min(backoff, MAX_TOTAL_BACKOFF_MS);
    }

    /**
     * The longest any single call of an httpcalls config can take: its timeout
     * times its attempts, plus the retry backoffs. Fire-and-forget calls add
     * nothing to the turn.
     */
    static long worstCaseApiCallMs(ApiCallsConfiguration config) {
        long worst = 0;
        if (config == null || config.getHttpCalls() == null) {
            return 0;
        }
        for (ApiCall call : config.getHttpCalls()) {
            if (Boolean.TRUE.equals(call.getFireAndForget())) {
                continue;
            }
            long timeout = call.getTimeoutInMillis() != null && call.getTimeoutInMillis() > 0 ? call.getTimeoutInMillis() : DEFAULT_HTTP_TIMEOUT_MS;
            long total = timeout;
            var retry = call.getPostResponse() == null ? null : call.getPostResponse().getRetryApiCallInstruction();
            if (retry != null && retry.getMaxRetries() != null && retry.getMaxRetries() >= 1) {
                total += (long) retry.getMaxRetries() * timeout + retryBackoffMs(retry);
            }
            worst = Math.max(worst, total);
        }
        return worst;
    }

    private static long retryBackoffMs(RetryApiCallInstruction retry) {
        long base = retry.getExponentialBackoffDelayInMillis() != null ? Math.max(0, retry.getExponentialBackoffDelayInMillis()) : 0;
        long cap = retry.getMaxBackoffDelayInMillis() != null && retry.getMaxBackoffDelayInMillis() > 0
                ? Math.min(retry.getMaxBackoffDelayInMillis(), MAX_BACKOFF_CEILING_MS)
                : MAX_BACKOFF_CEILING_MS;
        long sum = 0;
        for (int i = 0; i < retry.getMaxRetries(); i++) {
            sum += Math.min(base << Math.min(i, 20), cap);
        }
        return sum;
    }

    /**
     * The warning for an agent whose static worst case exceeds its turn budget, or
     * {@code null} when it fits.
     */
    static String budgetWarning(long turnDeadlineMs, long reserveMs, long llmMs, long httpMs) {
        long worst = llmMs + httpMs;
        long usable = turnDeadlineMs - reserveMs;
        if (worst <= usable) {
            return null;
        }
        return "static worst case " + worst + "ms (LLM tasks " + llmMs + "ms + the slowest httpcall of each httpcalls step " + httpMs
                + "ms, every retry running to its timeout) exceeds turnDeadlineMs " + turnDeadlineMs + "ms minus the " + reserveMs
                + "ms reserve; the deadline will cut retries and cascade steps short on a bad turn — shorten timeouts/retries or raise "
                + "turnDeadlineMs";
    }

    /**
     * Whether a failed model phase can still end in a user-presentable answer:
     * validation that falls back, or a task-level {@code onError} fallback. The
     * latter is read reflectively — it ships separately from this check and the
     * config class may not carry it yet.
     */
    static boolean hasFallbackPath(LlmConfiguration.Task task) {
        var validation = task.getResponseValidation();
        if (validation != null && validation.isEnabled() && (isFallback(validation.getOnEmpty()) || isFallback(validation.getOnTruncation())
                || isFallback(validation.getOnContentFilter()))) {
            return true;
        }
        return onErrorFallback(task);
    }

    private static boolean onErrorFallback(LlmConfiguration.Task task) {
        try {
            Method getOnError = task.getClass().getMethod("getOnError");
            Object onError = getOnError.invoke(task);
            if (onError == null) {
                return false;
            }
            Method getAction = onError.getClass().getMethod("getAction");
            Object action = getAction.invoke(onError);
            return action != null && "fallback".equalsIgnoreCase(action.toString());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isFallback(String action) {
        return "fallback".equalsIgnoreCase(action);
    }

    private static boolean isTrue(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    /**
     * A plain positive millisecond count, or {@code null} (unset, a template,
     * junk).
     */
    static Long parseMs(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
