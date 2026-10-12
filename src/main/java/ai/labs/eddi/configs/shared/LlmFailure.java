/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

/**
 * The verdict of {@link LlmFailureClassifier} for one failure.
 *
 * @param cls
 *            what kind of failure it was
 * @param retryAfterMs
 *            how long the provider asked callers to wait before retrying, or
 *            {@code null} when it did not say
 * @param reason
 *            a short, response-body-free description of why this class was
 *            chosen, for logs and traces
 */
public record LlmFailure(FailureClass cls, Long retryAfterMs, String reason) {
    public boolean isRetryable() {
        return cls.isRetryable();
    }
}
