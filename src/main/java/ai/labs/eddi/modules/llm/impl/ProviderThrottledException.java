/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import java.time.Duration;

/**
 * The embedding provider is refusing calls for its quota, and waiting it out
 * does not fit: the window is a day, the wait would overrun the caller's
 * deadline, or the provider kept refusing after every retry.
 *
 * <p>
 * Thrown by {@link ThrottledEmbeddingModel} instead of the provider's own
 * exception so a caller can tell "come back later" from "this document failed".
 * The ingestion pipeline stops the run as {@code THROTTLED} on it: no document
 * is counted as failed and nothing is tombstoned, because the quota belongs to
 * the provider's project, not to any document.
 *
 * <p>
 * The message never carries the provider's response body. It names the quota id
 * when there is one, which is the provider's text: sanitise it before logging.
 */
public class ProviderThrottledException extends RuntimeException {

    private final String provider;
    private final Duration retryAfter;
    private final boolean daily;
    private final String quotaId;

    /**
     * @param provider
     *            the embedding provider, as configured ({@code gemini})
     * @param retryAfter
     *            how long the provider asked to wait, or the fallback; null for a
     *            daily exhaustion with no delay given
     * @param daily
     *            the window that ran out does not refill within a run
     * @param quotaId
     *            the violated quota's id, or null
     * @param cause
     *            the provider's exception
     */
    public ProviderThrottledException(String provider, Duration retryAfter, boolean daily, String quotaId,
            Throwable cause) {
        super(describe(provider, retryAfter, daily, quotaId), cause);
        this.provider = provider;
        this.retryAfter = retryAfter;
        this.daily = daily;
        this.quotaId = quotaId;
    }

    private static String describe(String provider, Duration retryAfter, boolean daily, String quotaId) {
        StringBuilder message = new StringBuilder("The embedding provider '").append(provider)
                .append("' is refusing requests for its ").append(daily ? "daily" : "rate").append(" limit");
        if (quotaId != null) {
            message.append(" (quota ").append(quotaId).append(')');
        }
        if (retryAfter != null) {
            message.append("; it asks to retry after ").append(retryAfter.toSeconds()).append(" s");
        }
        return message.toString();
    }

    public String provider() {
        return provider;
    }

    /** The wait the provider asked for, or the fallback; null when unknown. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean daily() {
        return daily;
    }

    public String quotaId() {
        return quotaId;
    }
}
