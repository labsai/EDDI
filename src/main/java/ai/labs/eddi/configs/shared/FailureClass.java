/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

/**
 * What kind of failure an LLM (or MCP) call ended in, as far as the engine can
 * tell. The class, not the exception type, is what retry, cascade escalation
 * and metrics decide on.
 *
 * <p>
 * Only {@link #TRANSIENT}, {@link #RATE_LIMITED} and {@link #TIMEOUT} are worth
 * repeating on the same model: everything else will fail the same way again, so
 * it is escalated or surfaced immediately. In particular
 * {@link #QUOTA_EXHAUSTED} is a 429 that must <em>not</em> be retried — a daily
 * quota does not refill in the next second.
 * </p>
 *
 * @see LlmFailureClassifier
 */
public enum FailureClass {
    /** Provider-side hiccup: 500/502/503/529, overloaded, connect failure. */
    TRANSIENT(true),
    /** A rate (per-minute) limit; retry after the provider's suggested delay. */
    RATE_LIMITED(true),
    /** A spent quota or billing limit (e.g. a per-day quota). Retrying is waste. */
    QUOTA_EXHAUSTED(false),
    /** Missing, invalid or unauthorised credentials (401/403). */
    AUTH(false),
    /** The request itself is invalid (400). */
    BAD_REQUEST(false),
    /** The prompt exceeds the model's context window. */
    CONTEXT_TOO_LONG(false),
    /** Unknown or retired model id (404). */
    MODEL_NOT_FOUND(false),
    /** A client- or gateway-side timeout (read timeout, 408, 504). */
    TIMEOUT(true),
    /** Nothing recognisable; treated as permanent. */
    UNKNOWN(false);

    private final boolean retryable;

    FailureClass(boolean retryable) {
        this.retryable = retryable;
    }

    /** Whether repeating the identical call against the same model can help. */
    public boolean isRetryable() {
        return retryable;
    }
}
