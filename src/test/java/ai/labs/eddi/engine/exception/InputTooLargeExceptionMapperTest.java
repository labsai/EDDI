/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.engine.api.IConversationService.InputTooLargeException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InputTooLargeExceptionMapper")
class InputTooLargeExceptionMapperTest {

    @Test
    @DisplayName("answers 413 with the error code, the message and the limit")
    void mapsTo413() {
        InputTooLargeException exception = new InputTooLargeException(5_000_000, 200_000);

        Response response = new InputTooLargeExceptionMapper().toResponse(exception);

        assertEquals(413, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(InputTooLargeExceptionMapper.ERROR_CODE, body.get("error"));
        assertEquals(200_000, body.get("limit"));
        assertTrue(String.valueOf(body.get("message")).contains("5000000"));
        assertTrue(String.valueOf(body.get("message")).contains("eddi.conversations.max-input-chars"));
    }

    @Test
    @DisplayName("the async path builds the identical response")
    void asyncPathMirrorsMapper() {
        InputTooLargeException exception = new InputTooLargeException(11, 10);

        Response viaMapper = new InputTooLargeExceptionMapper().toResponse(exception);
        Response viaHelper = InputTooLargeExceptionMapper.responseOf(exception);

        assertEquals(viaMapper.getStatus(), viaHelper.getStatus());
        assertEquals(viaMapper.getEntity(), viaHelper.getEntity());
    }
}
