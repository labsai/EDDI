/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Metrics;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.jboss.logging.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared retry configuration and execution utility.
 * <p>
 * Used by all subsystems (LLM, MCP, etc.) to define per-call retry policies and
 * execute actions with exponential backoff.
 *
 * @since 6.0.0
 */
public class RetryConfiguration {
    private static final Logger LOGGER = Logger.getLogger(RetryConfiguration.class);

    /**
     * Engine-level ceilings on what an agent's {@code retry} block can ask a
     * pipeline thread to do. Config chooses a policy within these; it does not get
     * to exceed them.
     */
    public static final int MAX_ATTEMPTS_CEILING = 10;
    static final long MAX_BACKOFF_CEILING_MS = 30_000L;
    static final long MAX_TOTAL_BACKOFF_MS = 60_000L;

    /**
     * Runs deadline-bounded attempts, so one that overruns can be abandoned (and
     * its provider call cancelled) instead of waited for.
     */
    private static final ExecutorService ATTEMPT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * A first attempt is still made when the turn deadline leaves less than this
     * after the reserve — it is then given whatever time is left.
     */
    private static final long MIN_FIRST_ATTEMPT_BUDGET_MS = 500L;

    /** Distinct clamped values already reported; see {@link #warnClamped}. */
    private static final Set<String> CLAMP_WARNINGS = ConcurrentHashMap.newKeySet();
    private static final int MAX_CLAMP_WARNINGS = 32;

    private Integer maxAttempts = 3;
    private Long backoffDelayMs = 1000L;
    private Double backoffMultiplier = 2.0;
    private Long maxBackoffDelayMs = 10000L;

    /**
     * When the provider says how long to wait (Gemini {@code RetryInfo.retryDelay},
     * "try again in 6s"), sleep that long instead of the configured backoff.
     * Default {@code true}: it only changes the length of a sleep that would happen
     * anyway, and retrying a rate limit sooner than the provider asked just burns
     * an attempt.
     */
    private Boolean honorRetryAfter = true;

    /**
     * Longest provider-requested wait this call will sit through. A longer request
     * ends the retry loop at once — no sleep — so the caller (the model cascade,
     * the turn deadline) can escalate instead of parking a pipeline thread. Capped
     * by the engine at {@value #MAX_BACKOFF_CEILING_MS} ms.
     */
    private Long maxRetryAfterMs = DEFAULT_MAX_RETRY_AFTER_MS;

    static final long DEFAULT_MAX_RETRY_AFTER_MS = 10_000L;

    public Boolean getHonorRetryAfter() {
        return honorRetryAfter;
    }

    public void setHonorRetryAfter(Boolean honorRetryAfter) {
        this.honorRetryAfter = honorRetryAfter;
    }

    public Long getMaxRetryAfterMs() {
        return maxRetryAfterMs;
    }

    public void setMaxRetryAfterMs(Long maxRetryAfterMs) {
        this.maxRetryAfterMs = maxRetryAfterMs;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Long getBackoffDelayMs() {
        return backoffDelayMs;
    }

    public void setBackoffDelayMs(Long backoffDelayMs) {
        this.backoffDelayMs = backoffDelayMs;
    }

    public Double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(Double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public Long getMaxBackoffDelayMs() {
        return maxBackoffDelayMs;
    }

    public void setMaxBackoffDelayMs(Long maxBackoffDelayMs) {
        this.maxBackoffDelayMs = maxBackoffDelayMs;
    }

    // ========================== Static Retry Utility ==========================

    /**
     * Executes a generic action with retry logic based on configuration.
     * <p>
     * Uses exponential backoff: {@code delay * multiplier^(attempt-1)}, capped at
     * {@code maxBackoffDelayMs}. Retryable errors are identified by walking the
     * exception cause chain for known transient error types (timeout, connection,
     * rate limit, HTTP 429/500/502/503/504).
     * <p>
     * Config alone cannot hold a pipeline thread indefinitely: attempts are clamped
     * to {@value #MAX_ATTEMPTS_CEILING}, one backoff to
     * {@value #MAX_BACKOFF_CEILING_MS} ms, and the summed backoff of a single call
     * to {@value #MAX_TOTAL_BACKOFF_MS} ms. Without these, {@code {"maxAttempts":
     * 50, "maxBackoffDelayMs": 60000}} in one agent's {@code retry} block parked a
     * worker for the best part of an hour per turn, against AGENTS.md §4.1 rule 5
     * ("must not block for extended periods").
     *
     * @param action
     *            the action to execute
     * @param retryConfig
     *            retry settings (null = defaults: 3 attempts, 1s backoff, 2.0x
     *            multiplier, 10s cap)
     * @param actionDescription
     *            human-readable description for logging
     * @param <T>
     *            return type
     * @return the action's result on success
     * @throws LifecycleException
     *             if all attempts fail or a non-retryable error occurs
     */
    public static <T> T executeWithRetry(Callable<T> action, RetryConfiguration retryConfig,
                                         String actionDescription)
            throws LifecycleException {
        return executeWithRetry(action, retryConfig, actionDescription, new long[1]);
    }

    /**
     * Retries a call that has no pipeline retry policy of its own — the judge
     * model, the summarisers — with the default {@link RetryConfiguration}, and
     * throws what the action threw. The provider clients are built without library
     * retries, so every model call needs exactly one retry owner; this gives the
     * helper callers that do not carry a task {@code retry} block the same default
     * policy.
     *
     * @return the action's result
     * @throws RuntimeException
     *             the action's own runtime failure once it is not retryable or the
     *             attempts are spent (unwrapped, so callers' handling is unchanged)
     */
    public static <T> T executeWithDefaultRetry(Callable<T> action, String actionDescription) {
        return executeWithRetryUnwrapped(action, null, actionDescription);
    }

    /**
     * As {@link #executeWithDefaultRetry}, with an explicit (typically tighter)
     * policy for callers that run inside a time budget of their own.
     */
    public static <T> T executeWithRetryUnwrapped(Callable<T> action, RetryConfiguration retryConfig, String actionDescription) {
        try {
            return executeWithRetry(action, retryConfig, actionDescription);
        } catch (LifecycleException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * As {@link #executeWithRetry(Callable, RetryConfiguration, String)}, but
     * drawing the backoff from a budget shared across several calls.
     * <p>
     * The tool loop retries each model request on its own (so a retry can never
     * re-execute a tool that already ran), and a turn makes many requests. With a
     * fresh {@value #MAX_TOTAL_BACKOFF_MS} ms budget per request, a ten-iteration
     * turn could sleep ten minutes. Passing one holder for the whole loop keeps the
     * documented ceiling: at most {@value #MAX_TOTAL_BACKOFF_MS} ms of backoff per
     * turn.
     *
     * @param sharedBackoffMs
     *            a one-element holder of the backoff already spent by earlier
     *            calls; updated with what this call sleeps
     */
    public static <T> T executeWithRetry(Callable<T> action, RetryConfiguration retryConfig, String actionDescription,
                                         long[] sharedBackoffMs)
            throws LifecycleException {
        return executeWithRetry(action, retryConfig, actionDescription, sharedBackoffMs, null);
    }

    /**
     * As {@link #executeWithRetry(Callable, RetryConfiguration, String, long[])},
     * inside a turn deadline.
     * <p>
     * With a non-null {@code deadline} the loop spends from the turn's budget
     * rather than from its own settings alone:
     * <ul>
     * <li>the first attempt always runs while any time is left; a <em>retry</em> is
     * only started when at least {@value TurnDeadline#MIN_ATTEMPT_MS} ms plus the
     * deadline's reserve remain;</li>
     * <li>each attempt is bounded by what is left after the reserve (the call runs
     * on its own thread and is cancelled on overrun, because a cached model carries
     * a fixed provider timeout that cannot be shortened per call);</li>
     * <li>a sleep — the configured backoff or a provider {@code Retry-After} — that
     * would leave no room for the next attempt is not taken: the loop stops and
     * rethrows, so the caller (the cascade, the fallback path) acts while there is
     * still time to.</li>
     * </ul>
     * A {@code null} deadline is exactly the previous behaviour.
     *
     * @param deadline
     *            the turn's deadline, or {@code null} for none
     */
    public static <T> T executeWithRetry(Callable<T> action, RetryConfiguration retryConfig, String actionDescription,
                                         long[] sharedBackoffMs, TurnDeadline deadline)
            throws LifecycleException {

        if (retryConfig == null) {
            retryConfig = new RetryConfiguration();
        }

        int maxAttempts = effectiveMaxAttempts(retryConfig);
        long backoffDelay = retryConfig.getBackoffDelayMs() != null ? retryConfig.getBackoffDelayMs() : 1000L;
        double backoffMultiplier = retryConfig.getBackoffMultiplier() != null ? retryConfig.getBackoffMultiplier() : 2.0;
        long maxBackoffDelay = effectiveMaxBackoffMs(retryConfig);

        int attempt = 0;
        // Never negative: a negative backoffDelayMs would otherwise reach
        // Thread.sleep() and throw IllegalArgumentException.
        long currentBackoff = Math.max(0L, Math.min(backoffDelay, maxBackoffDelay));
        long totalBackoff = sharedBackoffMs[0];
        Exception lastException = null;

        while (attempt < maxAttempts) {
            long attemptBudgetMs = -1L;
            if (deadline != null) {
                attemptBudgetMs = deadline.remainingAfterReserveMs();
                if (attempt == 0) {
                    if (deadline.isExpired()) {
                        deadlineExceeded("attempt");
                        throw new LifecycleException(actionDescription + " not started: the turn deadline has already passed");
                    }
                    if (attemptBudgetMs < MIN_FIRST_ATTEMPT_BUDGET_MS) {
                        attemptBudgetMs = deadline.remainingMs();
                    }
                } else if (attemptBudgetMs < TurnDeadline.MIN_ATTEMPT_MS) {
                    deadlineExceeded("attempt");
                    LOGGER.warn(actionDescription + " not retried: " + deadline.remainingMs() + "ms left in the turn, below the "
                            + TurnDeadline.MIN_ATTEMPT_MS + "ms minimum attempt plus the " + deadline.reserveMs() + "ms reserve");
                    throw new LifecycleException(actionDescription + " failed after " + attempt + " attempts: turn deadline reached",
                            lastException);
                }
            }
            attempt++;

            try {
                LOGGER.debug(actionDescription + " attempt " + attempt + "/" + maxAttempts);

                T result = deadline == null ? action.call() : callWithin(action, attemptBudgetMs, actionDescription);

                // INFO only when a retry actually rescued the call — at INFO for every
                // success this fired once per LLM and MCP call in production.
                if (attempt > 1) {
                    LOGGER.info(actionDescription + " succeeded on attempt " + attempt);
                } else {
                    LOGGER.debug(actionDescription + " succeeded on attempt " + attempt);
                }
                return result;

            } catch (Exception e) {
                // HITL tool pause: a gated tool call must abort the retry loop and
                // travel up UNCHANGED — it is not a retryable failure. Rethrow before
                // any retry/backoff or LifecycleException wrapping so the pause signal
                // reaches LifecycleManager intact. Applies to LLM and MCP callers.
                if (e instanceof ToolApprovalRequiredException tare) {
                    throw tare;
                }
                lastException = e;

                if (attempt < maxAttempts) {
                    LlmFailure failure = LlmFailureClassifier.classify(e);
                    if (failure.isRetryable()) {
                        long wanted = currentBackoff;
                        Long retryAfter = failure.retryAfterMs();
                        if (retryAfter != null && honorRetryAfter(retryConfig)) {
                            long cap = effectiveMaxRetryAfterMs(retryConfig);
                            if (retryAfter > cap) {
                                // Do not sleep: stop here so the cascade escalates now.
                                LOGGER.warn(actionDescription + " rate limited; provider asked to wait " + retryAfter
                                        + "ms which exceeds maxRetryAfterMs=" + cap + "; giving up after " + attempt + " attempt(s)");
                                throw new LifecycleException(actionDescription + " failed: provider requested a " + retryAfter
                                        + "ms wait (> maxRetryAfterMs " + cap + "ms) [" + failure.cls() + "]", e);
                            }
                            wanted = Math.max(currentBackoff, retryAfter);
                        }
                        long sleepFor = budgetedSleep(wanted, totalBackoff);
                        if (sleepFor < 0) {
                            LOGGER.warn(actionDescription + " exhausted its " + MAX_TOTAL_BACKOFF_MS
                                    + "ms retry budget after " + attempt + " attempt(s); giving up");
                            break;
                        }
                        if (deadline != null && deadline.remainingAfterReserveMs() < sleepFor + TurnDeadline.MIN_ATTEMPT_MS) {
                            // Sleeping would leave no room for the attempt it is waiting for.
                            deadlineExceeded("sleep");
                            LOGGER.warn(actionDescription + " not retried: a " + sleepFor + "ms wait plus a " + TurnDeadline.MIN_ATTEMPT_MS
                                    + "ms attempt does not fit in the " + deadline.remainingMs() + "ms left in the turn");
                            throw new LifecycleException(actionDescription + " failed: turn deadline leaves no room to wait " + sleepFor
                                    + "ms and retry [" + failure.cls() + "]", e);
                        }

                        LOGGER.warn(actionDescription + " failed (attempt " + attempt + "/" + maxAttempts
                                + "), retrying after " + sleepFor + "ms: " + e.getMessage());

                        try {
                            Thread.sleep(sleepFor);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new LifecycleException("Retry interrupted", ie);
                        }
                        totalBackoff += sleepFor;
                        sharedBackoffMs[0] = totalBackoff;

                        currentBackoff = Math.min((long) (currentBackoff * backoffMultiplier), maxBackoffDelay);
                    } else {
                        LOGGER.error(actionDescription + " failed with non-retryable error: " + e.getMessage());
                        throw new LifecycleException(actionDescription + " failed: " + e.getMessage(), e);
                    }
                } else {
                    LOGGER.error(actionDescription + " failed after " + maxAttempts + " attempts");
                }
            }
        }

        // The attempts actually made, not the attempts configured: breaking out on a
        // spent budget reported "failed after 10 attempts" for a call that made two.
        throw new LifecycleException(actionDescription + " failed after " + attempt + " attempts", lastException);
    }

    /**
     * Runs one attempt on its own thread and abandons it when the budget is spent.
     * The thrown exception is the attempt's own, so classification and the HITL
     * pause signal behave exactly as without a deadline.
     */
    private static <T> T callWithin(Callable<T> action, long budgetMs, String actionDescription) throws Exception {
        Future<T> future = ATTEMPT_EXECUTOR.submit(carryingCallerContext(action));
        try {
            return future.get(Math.max(1L, budgetMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            Metrics.globalRegistry.counter("eddi.llm.cancelled", "scope", "attempt").increment();
            deadlineExceeded("attempt_timeout");
            throw new TimeoutException(actionDescription + " timed out after " + budgetMs + "ms (turn deadline)");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw ee;
        } catch (InterruptedException ie) {
            future.cancel(true);
            throw ie;
        }
    }

    /**
     * Carries what the calling thread has bound across the hop to the attempt's
     * thread: the OpenTelemetry context (so the per-task span stays the parent of
     * the model call's spans) and the logging MDC (conversation and agent ids on
     * the attempt's log lines). Caller identity needs no handling here - a model
     * call reads none.
     */
    static <T> Callable<T> carryingCallerContext(Callable<T> action) {
        Context otel = Context.current();
        Map<String, Object> mdc = MDC.getMap();
        Map<String, Object> mdcCopy = mdc == null ? Map.of() : new HashMap<>(mdc);
        return () -> {
            try (Scope ignored = otel.makeCurrent()) {
                MDC.clear();
                mdcCopy.forEach(MDC::put);
                try {
                    return action.call();
                } finally {
                    MDC.clear();
                }
            }
        };
    }

    private static void deadlineExceeded(String stage) {
        Metrics.globalRegistry.counter("eddi.llm.turn.deadline.exceeded", "stage", stage).increment();
    }

    /**
     * How long the next retry may sleep, or {@code -1} when the total backoff
     * budget for this call is spent.
     *
     * <p>
     * The budget is what bounds a whole {@code executeWithRetry} call, and only the
     * budget: a zero (or negative) {@code backoffDelayMs} is a legitimate "retry
     * immediately" policy, not an exhausted budget. Deciding both with one
     * {@code sleepFor <= 0} test conflated them, so {@code {"maxAttempts": 5,
     * "backoffDelayMs": 0}} broke out of the loop after its FIRST retryable failure
     * — retries silently off, with a warning blaming a 60s budget that had not been
     * touched.
     * </p>
     *
     * @param currentBackoff
     *            the backoff this attempt would like, already clamped to
     *            {@code maxBackoffDelayMs}
     * @param totalBackoff
     *            what this call has already slept
     * @see #MAX_TOTAL_BACKOFF_MS
     */
    static long budgetedSleep(long currentBackoff, long totalBackoff) {
        long remainingBudget = MAX_TOTAL_BACKOFF_MS - totalBackoff;
        if (remainingBudget <= 0) {
            return -1L;
        }
        return Math.max(0L, Math.min(currentBackoff, remainingBudget));
    }

    /**
     * How many attempts config actually gets, after the engine ceiling.
     *
     * @see #MAX_ATTEMPTS_CEILING
     */
    static int effectiveMaxAttempts(RetryConfiguration retryConfig) {
        int configured = retryConfig != null && retryConfig.getMaxAttempts() != null ? retryConfig.getMaxAttempts() : 3;
        return clampAttempts(configured);
    }

    /**
     * The engine ceiling on an attempt count, for retry loops that live outside
     * {@link #executeWithRetry} — the streaming path runs its own.
     *
     * <p>
     * Public so that ceiling is uniform: while the streaming executor read
     * {@code getMaxAttempts()} directly, {@code {"maxAttempts": 50}} was clamped on
     * the tool-loop path and unbounded on the streaming one, for the same agent and
     * the same config block.
     * </p>
     *
     * @see #MAX_ATTEMPTS_CEILING
     */
    public static int clampAttempts(int configured) {
        if (configured <= MAX_ATTEMPTS_CEILING) {
            return configured;
        }
        warnClamped("maxAttempts", configured, MAX_ATTEMPTS_CEILING);
        return MAX_ATTEMPTS_CEILING;
    }

    /**
     * How long a single backoff may last, after the engine ceiling.
     *
     * @see #MAX_BACKOFF_CEILING_MS
     */
    static long effectiveMaxBackoffMs(RetryConfiguration retryConfig) {
        long configured = retryConfig != null && retryConfig.getMaxBackoffDelayMs() != null ? retryConfig.getMaxBackoffDelayMs() : 10000L;
        if (configured <= MAX_BACKOFF_CEILING_MS) {
            return configured;
        }
        warnClamped("maxBackoffDelayMs", configured, MAX_BACKOFF_CEILING_MS);
        return MAX_BACKOFF_CEILING_MS;
    }

    /** {@code honorRetryAfter}, defaulting to {@code true} when unset. */
    static boolean honorRetryAfter(RetryConfiguration retryConfig) {
        return retryConfig == null || retryConfig.getHonorRetryAfter() == null || retryConfig.getHonorRetryAfter();
    }

    /**
     * The longest provider-requested wait this call accepts, after the engine
     * ceiling.
     *
     * @see #MAX_BACKOFF_CEILING_MS
     */
    static long effectiveMaxRetryAfterMs(RetryConfiguration retryConfig) {
        long configured = retryConfig != null && retryConfig.getMaxRetryAfterMs() != null
                ? retryConfig.getMaxRetryAfterMs()
                : DEFAULT_MAX_RETRY_AFTER_MS;
        configured = Math.max(0L, configured);
        if (configured <= MAX_BACKOFF_CEILING_MS) {
            return configured;
        }
        warnClamped("maxRetryAfterMs", configured, MAX_BACKOFF_CEILING_MS);
        return MAX_BACKOFF_CEILING_MS;
    }

    /**
     * Says once, per distinct clamped value, that a configured retry setting is not
     * being honoured as written.
     *
     * <p>
     * The ceilings exist to stop one agent's {@code retry} block from parking a
     * pipeline thread (AGENTS.md §4.1 rule 5), but silently overriding a number an
     * author deliberately typed is its own trap — an agent set to 20 attempts that
     * stops at 10 looks like a bug in the engine. Deduplicated because this is on
     * the path of every LLM and MCP call, and bounded because a log-key set that
     * grows with config values is a leak of its own.
     * </p>
     */
    private static void warnClamped(String setting, long configured, long ceiling) {
        String key = setting + "=" + configured;
        if (CLAMP_WARNINGS.size() < MAX_CLAMP_WARNINGS && CLAMP_WARNINGS.add(key)) {
            LOGGER.warn("retry." + setting + " is configured as " + configured + " but the engine caps it at " + ceiling
                    + "; the extra is ignored. Lower the configured value to make the effective policy explicit.");
        }
    }

    /**
     * Sleeps one backoff for the given attempt, with no accumulated budget to
     * spend. Equivalent to {@code backoff(attempt, retryConfig, 0L)}.
     */
    public static void backoff(int attempt, RetryConfiguration retryConfig) {
        backoff(attempt, retryConfig, 0L);
    }

    /**
     * Sleeps the backoff for {@code attempt}, trimmed to what is left of this
     * execution's total-backoff budget. For callers that manage their own retry
     * loop (the streaming executor) rather than going through
     * {@link #executeWithRetry}.
     *
     * <p>
     * The per-sleep ceiling alone does not bound such a loop, and this method used
     * to enforce only that: at {@link #MAX_ATTEMPTS_CEILING} attempts with a
     * configured 30-second delay the streaming path could sleep nine times — 270
     * seconds of a blocked pipeline thread, against the very
     * {@value #MAX_TOTAL_BACKOFF_MS} ms budget {@code executeWithRetry} applies to
     * every other retry loop in the engine. Passing the running total back in is
     * what makes the two paths agree.
     * </p>
     *
     * @param totalBackoffMs
     *            what this execution has already slept
     * @return how long this call actually slept, to be added to
     *         {@code totalBackoffMs}, or {@code -1} when the budget is spent and
     *         the caller must stop retrying (nothing was slept)
     * @see #budgetedSleep(long, long)
     */
    public static long backoff(int attempt, RetryConfiguration retryConfig, long totalBackoffMs) {
        if (retryConfig == null) {
            retryConfig = new RetryConfiguration();
        }
        long baseDelay = retryConfig.getBackoffDelayMs() != null ? retryConfig.getBackoffDelayMs() : 1000L;
        double multiplier = retryConfig.getBackoffMultiplier() != null ? retryConfig.getBackoffMultiplier() : 2.0;
        long maxDelay = effectiveMaxBackoffMs(retryConfig);

        int exponent = Math.max(0, attempt - 1);
        long delay = Math.min((long) (baseDelay * Math.pow(multiplier, exponent)), maxDelay);
        long sleepFor = budgetedSleep(delay, totalBackoffMs);
        if (sleepFor < 0) {
            return -1L;
        }
        try {
            Thread.sleep(sleepFor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sleepFor;
    }

    // ========================== Retryable Error Detection
    // ==========================

    /**
     * The single retryable-error verdict for the whole codebase — LLM tool loop,
     * MCP calls and the model cascade all route here.
     *
     * <p>
     * A thin wrapper over {@link LlmFailureClassifier}: retryable means the failure
     * classifies as {@link FailureClass#TRANSIENT},
     * {@link FailureClass#RATE_LIMITED} or {@link FailureClass#TIMEOUT}. Quota
     * exhaustion is deliberately <em>not</em> retryable even though it is an HTTP
     * 429. See the classifier for the order of evidence (status and body, typed
     * langchain4j exceptions over the whole cause chain, transport exceptions, and
     * only then message wording).
     * </p>
     */
    public static boolean isRetryableError(Exception e) {
        return LlmFailureClassifier.classify(e).isRetryable();
    }
}
