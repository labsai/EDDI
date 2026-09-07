/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Maps {@link ProcessingRestrictionUnavailableException} to HTTP 503 Service
 * Unavailable — the honest status for "we could not read the restriction flag",
 * as opposed to the 403 the fail-closed path used to return, which told the
 * user their processing was restricted when nobody had restricted it.
 * <p>
 * This mapper only fires on the synchronous entry points. {@code say} and
 * {@code sayStreaming} are resumed through an {@code AsyncResponse}, which
 * never reaches an {@code ExceptionMapper}, so {@code RestAgentEngine} and
 * {@code RestAgentEngineStreaming} carry explicit branches that reproduce this
 * status, body and header — the same arrangement quota and backpressure already
 * need. Change all three together.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Provider
public class ProcessingRestrictionUnavailableExceptionMapper implements ExceptionMapper<ProcessingRestrictionUnavailableException> {

    @Override
    public Response toResponse(ProcessingRestrictionUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                        "error", "restriction_status_unavailable",
                        // Guarded, like the quota mapper it mirrors: Map.of throws
                        // NullPointerException on a null value, and an exception mapper
                        // that throws produces exactly the opaque 500 this class exists
                        // to replace.
                        "message", messageOf(exception)))
                .type(MediaType.APPLICATION_JSON)
                // Retryable: the flag could not be read, which is a transient
                // availability failure, not a statement about the user.
                .header("Retry-After", "5")
                .build();
    }

    /**
     * The body's {@code message}, never null.
     * <p>
     * Shared with the {@code AsyncResponse} branches in
     * {@code RestAgentEngine.sayInternal} and
     * {@code RestAgentEngineStreaming.buildErrorEvent}, which cannot reach this
     * mapper and so reproduce its body themselves — the class Javadoc's "change all
     * three together" is only enforceable if the three read the same text from one
     * place.
     *
     * @param exception
     *            the failure being reported
     * @return the exception's own message, or a fixed fallback when it has none
     */
    public static String messageOf(ProcessingRestrictionUnavailableException exception) {
        String message = exception.getMessage();
        return message != null ? message : "Processing-restriction status unavailable";
    }
}
