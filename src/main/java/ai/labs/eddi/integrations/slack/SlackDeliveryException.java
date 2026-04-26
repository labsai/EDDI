/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

/**
 * Thrown when a Slack API call fails with a retryable error (network timeout,
 * HTTP 429/500/503, or connection refused). Non-retryable failures (e.g.,
 * {@code ok:false} with {@code channel_not_found}) do NOT throw this exception.
 *
 * @since 6.0.0
 */
public class SlackDeliveryException extends RuntimeException {

    public SlackDeliveryException(String message) {
        super(message);
    }

    public SlackDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
