/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

/**
 * Thrown when a Slack API call fails with a retryable error (network timeout,
 * HTTP 429/500/502/503/504, or connection refused). Non-retryable failures
 * (e.g., {@code ok:false} with {@code channel_not_found}) do NOT throw this
 * exception.
 *
 * @since 6.0.0
 */
public class SlackDeliveryException extends RuntimeException {

    private final long retryAfterMillis;

    public SlackDeliveryException(String message) {
        super(message);
        this.retryAfterMillis = 0;
    }

    /**
     * @param retryAfterMillis
     *            how long Slack asked callers to wait ({@code Retry-After}), or 0
     *            when it said nothing usable
     */
    public SlackDeliveryException(String message, long retryAfterMillis) {
        super(message);
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public SlackDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.retryAfterMillis = 0;
    }

    /**
     * The wait Slack asked for with {@code Retry-After} on an HTTP 429, in
     * milliseconds; 0 when the failure carried none.
     */
    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
