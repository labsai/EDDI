/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import org.jboss.logging.Logger;

import java.util.function.Supplier;

/**
 * One bounded retry for a model call that is <em>not</em> run through a task's
 * {@link RetryConfiguration}: the cascade's confidence judge, the tool-response
 * summarizer and the shared {@code SummarizationService}.
 * <p>
 * The provider SDKs' own retries are switched off (see
 * {@code ModelParameterValues#PROVIDER_MAX_RETRIES}) because the tool loop and
 * the chat path wrap every request in the task's retry policy and the two used
 * to multiply. These callers have no task policy to draw on, and they used to
 * get their only retry from the SDK, so a single 429 or 503 now failed a
 * summary that two attempts would have produced. A retry here is deliberately
 * small — one more attempt after a short pause, only for what
 * {@link RetryConfiguration#isRetryableError} calls transient — so a failing
 * provider still costs at most two requests.
 */
final class TransientFailureRetry {

    private static final Logger LOGGER = Logger.getLogger(TransientFailureRetry.class);

    /** Pause before the single retry. */
    static final long BACKOFF_MS = 500L;

    private TransientFailureRetry() {
    }

    /**
     * Runs {@code call}; when it fails with a transient error, runs it once more
     * after {@link #BACKOFF_MS}. Any other failure, and the second failure, reach
     * the caller unchanged.
     */
    static <T> T call(Supplier<T> call, String description) {
        try {
            return call.get();
        } catch (RuntimeException first) {
            if (!RetryConfiguration.isRetryableError(first)) {
                throw first;
            }
            LOGGER.warnf("%s failed with a transient error, retrying once after %dms: %s", description, BACKOFF_MS, first.getMessage());
            try {
                Thread.sleep(BACKOFF_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return call.get();
        }
    }
}
