/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

/**
 * The embedding provider refuses calls because the account's balance or billing
 * limit is spent — OpenAI's {@code insufficient_quota} or
 * {@code billing_hard_limit_reached}.
 *
 * <p>
 * Not a throttle: no wait refills it, so a run that met it is not "paused until
 * later" but failed until someone fixes the account. Thrown by
 * {@link ThrottledEmbeddingModel} instead of the provider's
 * {@code RateLimitException}, which would otherwise read as a rate limit; the
 * ingestion pipeline stops the run as {@code FAILED} with this message, and
 * counts no document as failed, because no document is at fault.
 *
 * <p>
 * The message is EDDI's own and never carries the provider's response body.
 */
public class ProviderBillingException extends RuntimeException {

    private final String provider;

    public ProviderBillingException(String provider, Throwable cause) {
        super("The embedding provider '" + provider + "' refuses requests because the account's quota or billing "
                + "limit is spent (insufficient_quota). Waiting does not help: check the provider account's plan "
                + "and billing, then run the source again", cause);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }
}
