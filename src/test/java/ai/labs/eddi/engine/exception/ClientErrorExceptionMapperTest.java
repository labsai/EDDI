/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 4xx has to say why.
 * <p>
 * {@code throw new BadRequestException("…")} builds its response from the
 * status alone, so the message never leaves the JVM. Validation across this
 * codebase is written as though it does — {@code POST /channelstore/channels}
 * throws four distinct, carefully worded messages and delivered an empty body
 * for every one of them. On a live sweep that made channel integrations
 * impossible to create through the API without reading the Java source to work
 * out which rule was being violated, and it produced four false findings whose
 * only cause was a call shape nothing would explain.
 */
@DisplayName("4xx messages reach the client")
class ClientErrorExceptionMapperTest {

    private final ClientErrorExceptionMapper mapper = new ClientErrorExceptionMapper();

    @Test
    @DisplayName("a BadRequestException's message becomes the response body")
    void badRequestMessageBecomesTheBody() {
        Response response = mapper.toResponse(new BadRequestException("Channel integration name is required."));

        assertEquals(400, response.getStatus());
        assertEquals("Channel integration name is required.", response.getEntity());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getMediaType());
    }

    @Test
    @DisplayName("the status is preserved, whatever the 4xx")
    void statusIsPreserved() {
        assertEquals(404, mapper.toResponse(new NotFoundException("No such agent.")).getStatus());
        assertEquals(403, mapper.toResponse(new ForbiddenException("Not your conversation.")).getStatus());
    }

    @Test
    @DisplayName("an exception that already carries an entity is left alone")
    void existingEntityIsNotOverwritten() {
        Response existing = Response.status(400).entity("{\"error\":\"structured\"}")
                .type(MediaType.APPLICATION_JSON).build();

        Response response = mapper.toResponse(new BadRequestException(existing));

        assertEquals("{\"error\":\"structured\"}", response.getEntity());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
    }

    @Test
    @DisplayName("no message — the response stays as it was, no empty body invented")
    void noMessageLeavesTheResponseAlone() {
        Response response = mapper.toResponse(new BadRequestException());

        assertEquals(400, response.getStatus());
        // JAX-RS supplies a default reason for a message-less exception; whatever it
        // is, the mapper must not turn it into something misleading.
        assertTrue(response.getEntity() == null || !response.getEntity().toString().isBlank());
    }
}
