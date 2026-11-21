/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.modules.llm.impl.ProviderRetryHints;
import ai.labs.eddi.modules.llm.impl.ProviderThrottledException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Maps {@link ProviderThrottledException} — an embedding provider refusing
 * calls for its quota, on a request that had to embed something — to HTTP 429
 * Too Many Requests with a {@code Retry-After} header.
 *
 * <p>
 * Without it the exception reached the client as a 500, which says the server
 * is broken when the provider has only asked to wait. The body is a fixed
 * sentence: the exception's message names the provider's quota id, which is the
 * provider's text and not the caller's business.
 */
@Provider
public class ProviderThrottledExceptionMapper implements ExceptionMapper<ProviderThrottledException> {

    static final int TOO_MANY_REQUESTS = 429;

    /** The wait suggested for a daily quota when the provider named no time. */
    static final long DAILY_FALLBACK_SECONDS = 3600;

    @Override
    public Response toResponse(ProviderThrottledException exception) {
        long fallback = exception.daily() ? DAILY_FALLBACK_SECONDS : ProviderRetryHints.FALLBACK_DELAY.toSeconds();
        long retryAfter = exception.retryAfterSeconds(fallback);
        String message = exception.daily()
                ? "The embedding provider's daily quota is spent. Try again later."
                : "The embedding provider is limiting requests right now. Try again in " + retryAfter + " s.";
        return Response.status(TOO_MANY_REQUESTS)
                .entity(Map.of("error", "provider_throttled", "message", message,
                        "retryAfterSeconds", retryAfter))
                .type(MediaType.APPLICATION_JSON)
                .header("Retry-After", String.valueOf(retryAfter))
                .build();
    }
}
