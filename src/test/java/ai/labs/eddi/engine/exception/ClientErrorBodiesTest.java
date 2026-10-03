/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.InputData;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Client errors carry a JSON body that says what was wrong — never an empty
 * 400, never a 404 for a typo in a parameter, never a Java class name.
 */
class ClientErrorBodiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static String message(Response response) {
        assertEquals(400, response.getStatus());
        assertEquals("application/json", response.getMediaType().toString());
        var body = (Map<String, String>) response.getEntity();
        assertEquals("bad_request", body.get("error"));
        String message = body.get("message");
        assertFalse(message.contains("ai.labs"), "no internal class names: " + message);
        assertFalse(message.contains("java."), "no JDK class names: " + message);
        return message;
    }

    /**
     * A 404 shaped exactly like the one the framework's parameter handler raises.
     */
    private static NotFoundException parameterConversion404(Throwable cause) {
        var exception = new NotFoundException(cause);
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.jboss.resteasy.reactive.server.handlers.ParameterHandler", "handleResult",
                        "ParameterHandler.java", 120)});
        return exception;
    }

    @Nested
    @DisplayName("ParameterConversionExceptionMapper")
    class ParameterConversion {

        private final ParameterConversionExceptionMapper mapper = new ParameterConversionExceptionMapper();

        @Test
        @DisplayName("invalid enum value → 400 naming the value and the legal ones")
        void invalidEnum() {
            Throwable cause;
            try {
                ConversationState.valueOf("BOGUS");
                fail("expected IllegalArgumentException");
                return;
            } catch (IllegalArgumentException e) {
                cause = e;
            }
            String message = message(mapper.toResponse(parameterConversion404(cause)));
            assertTrue(message.contains("'BOGUS'"), message);
            assertTrue(message.contains("READY") && message.contains("ENDED"), message);
        }

        @Test
        @DisplayName("invalid number → 400 naming the value")
        void invalidNumber() {
            Throwable cause;
            try {
                Integer.valueOf("abc");
                fail("expected NumberFormatException");
                return;
            } catch (NumberFormatException e) {
                cause = e;
            }
            String message = message(mapper.toResponse(parameterConversion404(cause)));
            assertTrue(message.contains("'abc'") && message.contains("number"), message);
        }

        @Test
        @DisplayName("any other 404 is left alone")
        void ordinaryNotFoundUnchanged() {
            Response response = mapper.toResponse(new NotFoundException("Conversation not found"));
            assertEquals(404, response.getStatus());
            assertEquals("Conversation not found", response.getEntity());

            // A 404 wrapping a cause but not raised by the parameter handler.
            Response withCause = mapper.toResponse(new NotFoundException("gone", new IllegalStateException("x")));
            assertEquals(404, withCause.getStatus());
        }
    }

    @Nested
    @DisplayName("unreadable JSON bodies")
    class JsonBodies {

        @Test
        @DisplayName("malformed JSON → 400 with line and column instead of an empty body")
        void malformedJson() {
            JsonProcessingException cause = assertThrows(JsonProcessingException.class,
                    () -> MAPPER.readTree("{\"behaviorGroups\": [}"));
            Response response = new UnreadableBodyExceptionMapper()
                    .toResponse(new WebApplicationException(cause, Response.Status.BAD_REQUEST));
            String message = message(response);
            assertTrue(message.startsWith("The request body is not valid JSON at line 1"), message);
            assertFalse(message.contains("Source") || message.contains("StreamReadFeature"), message);
        }

        @Test
        @DisplayName("a parser failure wrapped by the databind layer is explained by its cause")
        void wrappedParserFailure() {
            JsonProcessingException parse = assertThrows(JsonProcessingException.class,
                    () -> MAPPER.readTree("{\"behaviorGroups\": [}"));
            var wrapped = JsonMappingException.from((JsonParser) null, "wrapped", parse);
            String message = message(new UnreadableBodyExceptionMapper()
                    .toResponse(new WebApplicationException(wrapped, Response.Status.BAD_REQUEST)));
            assertTrue(message.startsWith("The request body is not valid JSON at line 1"), message);
        }

        @Test
        @DisplayName("nesting past the parser's limit → 400 that says so")
        void tooDeep() {
            var shallow = new ObjectMapper();
            shallow.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).build());
            JsonProcessingException cause = assertThrows(JsonProcessingException.class,
                    () -> shallow.readTree("[[[[[[[[[[1]]]]]]]]]]"));
            String message = message(new UnreadableBodyExceptionMapper()
                    .toResponse(new WebApplicationException(cause, Response.Status.BAD_REQUEST)));
            assertTrue(message.contains("nesting depth"), message);
        }

        @Test
        @DisplayName("a value of the wrong shape → 400 naming the JSON path")
        void wrongShape() {
            MismatchedInputException cause = assertThrows(MismatchedInputException.class,
                    () -> MAPPER.readValue("{\"input\":\"hi\",\"context\":{\"x\":\"notobj\"}}", InputData.class));
            String message = message(new MismatchedJsonInputExceptionMapper().toResponse(cause));
            assertTrue(message.contains("context.x"), message);
        }

        @Test
        @DisplayName("other WebApplicationExceptions pass through unchanged")
        void othersUnchanged() {
            var mapper = new UnreadableBodyExceptionMapper();
            assertEquals(500, mapper.toResponse(new InternalServerErrorException("boom")).getStatus());

            Response withEntity = Response.status(400).entity("explained").build();
            assertSame(withEntity, mapper.toResponse(new WebApplicationException(new JsonProcessingExceptionStub(), withEntity)));

            Response noJsonCause = mapper.toResponse(new WebApplicationException(new IllegalStateException(), Response.Status.BAD_REQUEST));
            assertFalse(noJsonCause.hasEntity());
        }
    }

    private static final class JsonProcessingExceptionStub extends JsonProcessingException {
        JsonProcessingExceptionStub() {
            super("stub");
        }
    }
}
