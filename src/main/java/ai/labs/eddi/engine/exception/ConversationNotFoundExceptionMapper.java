/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.engine.api.IConversationService.ConversationNotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Answers "there is no such conversation" with 404.
 *
 * <p>
 * {@link ConversationNotFoundException} has been thrown since the conversation
 * endpoints were written, and nothing mapped it — so it reached Quarkus's
 * default handler as an unhandled {@link RuntimeException} and every
 * conversation endpoint answered a deleted or mistyped id with
 * {@code 500 Internal Server Error} and an error id, while its own
 * {@code @APIResponse(responseCode = "404")} promised otherwise. A caller could
 * not tell "you asked for something that is not here" from "the server is
 * broken", which are the two things a status code exists to separate.
 * </p>
 */
@Provider
public class ConversationNotFoundExceptionMapper implements ExceptionMapper<ConversationNotFoundException> {
    @Override
    public Response toResponse(ConversationNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN).entity(exception.getLocalizedMessage()).build();
    }
}
