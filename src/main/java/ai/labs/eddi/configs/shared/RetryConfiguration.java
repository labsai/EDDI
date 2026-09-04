/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

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

    /** Distinct clamped values already reported; see {@link #warnClamped}. */
    private static final Set<String> CLAMP_WARNINGS = ConcurrentHashMap.newKeySet();
    private static final int MAX_CLAMP_WARNINGS = 32;

    private Integer maxAttempts = 3;
    private Long backoffDelayMs = 1000L;
    private Double backoffMultiplier = 2.0;
    private Long maxBackoffDelayMs = 10000L;

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
     * rate limit, HTTP 429/502/503/504).
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
        long totalBackoff = 0L;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;

            try {
                LOGGER.debug(actionDescription + " attempt " + attempt + "/" + maxAttempts);

                T result = action.call();

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
                    if (isRetryableError(e)) {
                        long sleepFor = budgetedSleep(currentBackoff, totalBackoff);
                        if (sleepFor < 0) {
                            LOGGER.warn(actionDescription + " exhausted its " + MAX_TOTAL_BACKOFF_MS
                                    + "ms retry budget after " + attempt + " attempt(s); giving up");
                            break;
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
     * Sleeps for the appropriate backoff duration for the given attempt. Useful for
     * callers that manage their own retry loop (e.g., streaming).
     */
    public static void backoff(int attempt, RetryConfiguration retryConfig) {
        if (retryConfig == null) {
            retryConfig = new RetryConfiguration();
        }
        long baseDelay = retryConfig.getBackoffDelayMs() != null ? retryConfig.getBackoffDelayMs() : 1000L;
        double multiplier = retryConfig.getBackoffMultiplier() != null ? retryConfig.getBackoffMultiplier() : 2.0;
        long maxDelay = effectiveMaxBackoffMs(retryConfig);

        int exponent = Math.max(0, attempt - 1);
        long delay = Math.min((long) (baseDelay * Math.pow(multiplier, exponent)), maxDelay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================== Retryable Error Detection
    // ==========================

    /**
     * Transient-failure signatures in an exception message — the last resort, for
     * providers that surface a failure only as prose.
     * <p>
     * The union of what the two previous implementations matched.
     * {@code CascadingModelExecutor} had its own regex that additionally caught
     * bare status numbers, so the same provider failure was retried on the cascade
     * path and not on the tool-loop path (or vice versa) depending purely on how
     * the message happened to be worded. Word boundaries keep {@code 429} from
     * matching inside an id or a token count.
     */
    private static final Pattern RETRYABLE_MESSAGE = Pattern.compile(
            "timeout|rate limit|too many requests|connection refused|connection reset"
                    + "|service unavailable|bad gateway|gateway timeout|\\b429\\b|\\b50[234]\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);

    /**
     * The single retryable-error verdict for the whole codebase — LLM tool loop,
     * MCP calls and the model cascade all route here.
     *
     * <p>
     * Order matters. langchain4j's own classification is checked FIRST, because it
     * is what {@code chatModel.chat()} actually throws and it is authoritative:
     * {@link RetriableException} (rate limit, provider timeout, 5xx) means retry,
     * {@link NonRetriableException} (bad request, auth, unknown model) means stop —
     * and stopping early matters, since a wrapped auth failure whose message merely
     * mentions a timeout would otherwise be retried until the attempts run out.
     * Untyped transport exceptions come next, then HTTP status codes, and only then
     * the message-text fallback.
     * </p>
     */
    public static boolean isRetryableError(Exception e) {
        Throwable current = e;

        while (current != null) {
            // 1. langchain4j's typed verdict — authoritative, in both directions
            if (current instanceof RetriableException) {
                return true;
            }
            if (current instanceof NonRetriableException) {
                return false;
            }

            // 2. Untyped transport failures
            if (current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException) {
                return true;
            }

            // 3. HTTP status code matching from typed exceptions
            if (current instanceof HttpException httpException && RETRYABLE_STATUS_CODES.contains(httpException.statusCode())) {
                return true;
            }
            if (current instanceof WebApplicationException wae && RETRYABLE_STATUS_CODES.contains(wae.getResponse().getStatus())) {
                return true;
            }

            // 4. String-based fallback for wrapped/untyped exceptions
            String message = current.getMessage();
            if (message != null && RETRYABLE_MESSAGE.matcher(message).find()) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
