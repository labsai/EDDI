/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Puts a 4xx exception's message into its response body.
 * <p>
 * {@code throw new BadRequestException("Channel integration name is required.")}
 * produces a 400 with an <em>empty</em> body: JAX-RS builds the response from
 * the status alone unless the exception was constructed with an entity. The
 * validation code across this codebase is written as though the message were
 * delivered — {@code POST /channelstore/channels} alone throws four distinct,
 * well-written messages, none of which ever reached a client. On a live sweep
 * that made channel integrations impossible to create through the API without
 * reading the Java source to find out which rule was being violated, and it
 * produced four false findings whose only cause was a call shape nothing would
 * explain.
 * <p>
 * Deliberately scoped to {@link ClientErrorException}: a 4xx message describes
 * what the <em>caller</em> did wrong and is safe to return. Server-side
 * exceptions are left alone, so no internal detail is copied into a 5xx body.
 * An exception that already carries an entity is passed through untouched.
 */
@Provider
public class ClientErrorExceptionMapper implements ExceptionMapper<ClientErrorException> {

    @Override
    public Response toResponse(ClientErrorException exception) {
        Response original = exception.getResponse();
        if (original.hasEntity()) {
            return original;
        }

        String message = exception.getLocalizedMessage();
        if (message == null || message.isBlank()) {
            return original;
        }

        return Response.fromResponse(original)
                .type(MediaType.TEXT_PLAIN)
                .entity(message)
                .build();
    }
}
