/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The OpenAI-compatible surface ({@code /v1}) keeps the OpenAI error envelope
 * when the global client-error mappers answer a request there: SDKs read
 * {@code error.message}, which a flat EDDI body does not have.
 */
class OpenAiPathErrorBodiesTest {

    private static UriInfo path(String path) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        return uriInfo;
    }

    private static OpenAiErrorResponse.OpenAiError openAiError(Response response) {
        assertEquals(400, response.getStatus());
        assertEquals("application/json", response.getMediaType().toString());
        var envelope = assertInstanceOf(OpenAiErrorResponse.class, response.getEntity());
        assertEquals(OpenAiErrorResponse.TYPE_INVALID_REQUEST, envelope.error().type());
        assertFalse(envelope.error().message().isBlank());
        return envelope.error();
    }

    @Test
    @DisplayName("unreadable body under /v1 is an OpenAI envelope")
    void unreadableBody() {
        var mapper = new UnreadableBodyExceptionMapper();
        mapper.uriInfo = path("/v1/chat/completions");
        var syntax = new WebApplicationException(
                new JsonParseException(null, "Unexpected close marker '}'"), 400);
        var error = openAiError(mapper.toResponse(syntax));
        assertEquals(OpenAiErrorResponse.CODE_INVALID_REQUEST_BODY, error.code());
    }

    @Test
    @DisplayName("mismatched body under /v1 (no leading slash) is an OpenAI envelope")
    void mismatchedBody() {
        var mapper = new MismatchedJsonInputExceptionMapper();
        mapper.uriInfo = path("v1/chat/completions");
        MismatchedInputException mismatch = MismatchedInputException.from((JsonParser) null,
                String.class, "boom");
        openAiError(mapper.toResponse(mismatch));
    }

    @Test
    @DisplayName("IllegalArgumentException under /v1 is an OpenAI envelope")
    void illegalArgument() {
        var mapper = new IllegalArgumentExceptionMapper();
        mapper.uriInfo = path("/v1/models");
        assertEquals("bad id", openAiError(mapper.toResponse(new IllegalArgumentException("bad id"))).message());
    }

    @Test
    @DisplayName("every other path, and an unknown path, keeps the EDDI body")
    @SuppressWarnings("unchecked")
    void otherPathsKeepEddiBody() {
        for (UriInfo uriInfo : new UriInfo[]{path("/agents/x"), path("/v10/x"), path("/v1beta"), path(null), null}) {
            var mapper = new IllegalArgumentExceptionMapper();
            mapper.uriInfo = uriInfo;
            Response response = mapper.toResponse(new IllegalArgumentException("bad id"));
            var body = assertInstanceOf(Map.class, response.getEntity());
            assertEquals("bad_request", body.get("error"));
            assertEquals("bad id", body.get("message"));
        }
    }
}
