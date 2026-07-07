/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import org.jboss.logging.Logger;

import java.util.concurrent.Callable;

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

        int maxAttempts = retryConfig.getMaxAttempts() != null ? retryConfig.getMaxAttempts() : 3;
        long backoffDelay = retryConfig.getBackoffDelayMs() != null ? retryConfig.getBackoffDelayMs() : 1000L;
        double backoffMultiplier = retryConfig.getBackoffMultiplier() != null ? retryConfig.getBackoffMultiplier() : 2.0;
        long maxBackoffDelay = retryConfig.getMaxBackoffDelayMs() != null ? retryConfig.getMaxBackoffDelayMs() : 10000L;

        int attempt = 0;
        long currentBackoff = backoffDelay;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;

            try {
                LOGGER.debug(actionDescription + " attempt " + attempt + "/" + maxAttempts);

                T result = action.call();

                LOGGER.info(actionDescription + " succeeded on attempt " + attempt);
                return result;

            } catch (Exception e) {
                lastException = e;

                if (attempt < maxAttempts) {
                    if (isRetryableError(e)) {
                        LOGGER.warn(actionDescription + " failed (attempt " + attempt + "/" + maxAttempts
                                + "), retrying after " + currentBackoff + "ms: " + e.getMessage());

                        try {
                            Thread.sleep(currentBackoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new LifecycleException("Retry interrupted", ie);
                        }

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

        throw new LifecycleException(actionDescription + " failed after " + maxAttempts + " attempts", lastException);
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
        long maxDelay = retryConfig.getMaxBackoffDelayMs() != null ? retryConfig.getMaxBackoffDelayMs() : 10000L;

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
     * Determines if an error is retryable by checking:
     * <ol>
     * <li>Known exception types (timeout, connection)</li>
     * <li>HTTP status codes from HttpException or WebApplicationException (429,
     * 502, 503, 504)</li>
     * <li>Message string patterns (fallback for wrapped/untyped exceptions)</li>
     * </ol>
     */
    public static boolean isRetryableError(Exception e) {
        Throwable current = e;

        while (current != null) {
            // 1. Typed exception matching
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException) {
                return true;
            }

            // 2. HTTP status code matching from typed exceptions
            if (current instanceof jakarta.ws.rs.WebApplicationException wae) {
                int status = wae.getResponse().getStatus();
                if (status == 429 || status == 502 || status == 503 || status == 504) {
                    return true;
                }
            }

            // 3. String-based fallback for wrapped exceptions (conservative matching)
            String message = current.getMessage() != null ? current.getMessage().toLowerCase() : "";

            if (message.contains("timeout") || message.contains("rate limit") || message.contains("too many requests")
                    || message.contains("connection refused") || message.contains("connection reset")
                    || message.contains("service unavailable") || message.contains("bad gateway")
                    || message.contains("gateway timeout")) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
