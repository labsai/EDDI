/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.rest;

import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Maps {@link QuotaAccountingUnavailableException} to HTTP 503 Service
 * Unavailable with the {@code quota_accounting_unavailable} error code.
 * <p>
 * Without it the exception is handled by
 * {@code RejectedExecutionExceptionMapper} — it extends
 * {@link java.util.concurrent.RejectedExecutionException}, so the status is
 * already right — but the body reads {@code "error": "capacity_exceeded"},
 * which is the wrong event: nothing is saturated, the quota store could not
 * answer. That is what
 * {@code POST /agents/&#123;environment&#125;/&#123;agentId&#125;} returned
 * during a quota-store outage, because
 * {@code RestAgentEngine.startConversationWithContext} catches only the three
 * conditions it reports itself and lets everything else reach a mapper — while
 * {@code docs/metrics.md} and the dashboard both promise the honest code on
 * that path.
 * <p>
 * JAX-RS selects the mapper whose exception type is nearest the thrown class,
 * so this one wins over the {@code RejectedExecutionException} mapper wherever
 * both apply; the {@code AsyncResponse}-based {@code say} endpoints reach
 * neither and keep their explicit branch in {@code RestAgentEngine}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Provider
public class QuotaAccountingUnavailableExceptionMapper implements ExceptionMapper<QuotaAccountingUnavailableException> {

    @Override
    public Response toResponse(QuotaAccountingUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                        "error", "quota_accounting_unavailable",
                        "message", exception.getMessage() != null ? exception.getMessage() : "Quota accounting unavailable"))
                .type(MediaType.APPLICATION_JSON)
                .header("Retry-After", "5")
                .build();
    }
}
