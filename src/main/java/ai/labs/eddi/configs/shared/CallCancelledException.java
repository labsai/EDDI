/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

/**
 * A model or tool call was stopped because its turn was cancelled or its thread
 * interrupted. Never a provider failure:
 * {@link RetryConfiguration#executeWithRetry} turns it into a
 * {@code LifecycleInterruptedException} wherever it sits in the cause chain, so
 * no caller retries it, falls back to another request or counts it against a
 * model.
 *
 * @since 6.7.0
 */
public class CallCancelledException extends RuntimeException {

    public CallCancelledException(String message) {
        super(message);
    }

    public CallCancelledException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Whether {@code failure} is a cancellation: a {@link CallCancelledException}
     * or an {@link InterruptedException} anywhere in its cause chain.
     */
    public static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof CallCancelledException || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
