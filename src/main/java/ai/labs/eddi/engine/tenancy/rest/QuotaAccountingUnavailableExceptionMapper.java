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
                        "message", messageOf(exception)))
                .type(MediaType.APPLICATION_JSON)
                .header("Retry-After", "5")
                .build();
    }

    /**
     * The body's {@code message}, never null.
     * <p>
     * Shared with the {@code AsyncResponse} branches in
     * {@code RestAgentEngine.sayInternal} and
     * {@code RestAgentEngineStreaming.buildKnownConditionOrOpaqueErrorEvent}, which
     * cannot reach this mapper and so reproduce its body themselves. The streaming
     * branch echoed {@code getMessage()} raw, so a thrower that supplies no message
     * emitted {@code "message":""} there while the other two surfaces said "Quota
     * accounting unavailable" — one outage described two ways to clients keying on
     * the error contract. Mirrors
     * {@code ProcessingRestrictionUnavailableExceptionMapper.messageOf}, which
     * exists for the same reason.
     *
     * @param exception
     *            the failure being reported
     * @return the exception's own message, or a fixed fallback when it has none
     */
    public static String messageOf(QuotaAccountingUnavailableException exception) {
        String message = exception.getMessage();
        return message != null ? message : "Quota accounting unavailable";
    }
}
