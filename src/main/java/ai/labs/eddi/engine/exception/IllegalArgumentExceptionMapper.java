/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * An {@link IllegalArgumentException} that reaches the REST layer is a request
 * the server cannot act on (a malformed id, an out-of-range value): 400, with
 * the shared JSON error body. The body used to be the bare message under
 * whatever content type the endpoint declared — {@code application/json} for
 * most — so clients received a "JSON" response that did not parse.
 * <p>
 * <b>Only EDDI's own messages reach the client</b> ({@link #isCallerFacing}).
 * An IllegalArgumentException thrown inside a library or the JDK — the MongoDB
 * driver rejecting an id, {@code java.nio} rejecting a path, Jackson, a
 * {@code UUID} parser — carries text written for that library's developers:
 * class names, file paths, fragments of other input (CWE-209). Those are
 * answered with a fixed message and a reference id, and the original message is
 * logged under the same id so an operator can still find it.
 *
 * @author ginccc
 */
@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {

    private static final Logger LOGGER = Logger.getLogger(IllegalArgumentExceptionMapper.class);

    /**
     * Package prefix of the code whose argument checks are written for API callers.
     */
    private static final String EDDI_PACKAGE = "ai.labs.eddi.";

    private static final int MAX_MESSAGE_LENGTH = 500;

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        return ErrorResponses.badRequest(clientMessage(exception), uriInfo);
    }

    /**
     * The response this mapper produces, for endpoints that resume an
     * {@code AsyncResponse} themselves (mappers never see those exceptions).
     */
    public static Response responseOf(IllegalArgumentException exception) {
        return ErrorResponses.badRequest(clientMessage(exception));
    }

    /**
     * What the client is told: EDDI's own message, or a fixed text with a reference
     * id under which the library's message is logged.
     */
    static String clientMessage(IllegalArgumentException exception) {
        String message = exception.getLocalizedMessage();
        if (isCallerFacing(exception) && message != null && !message.isBlank()) {
            return message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) + "…" : message;
        }
        String reference = UUID.randomUUID().toString();
        LOGGER.infof("Rejected a request with an invalid argument [ref %s]: %s: %s", reference,
                exception.getClass().getName(), sanitize(message));
        return "The request contains an invalid value (reference " + reference + ").";
    }

    /**
     * Whether the exception was raised by EDDI code — its message then is an
     * argument check written for the API caller — rather than by a library or the
     * JDK it called. Decided by the frame that threw it, so an EDDI method that
     * merely passes a library's exception through does not make the library's text
     * caller-facing.
     */
    static boolean isCallerFacing(IllegalArgumentException exception) {
        StackTraceElement[] trace = exception.getStackTrace();
        return trace.length > 0 && trace[0].getClassName().startsWith(EDDI_PACKAGE);
    }
}
