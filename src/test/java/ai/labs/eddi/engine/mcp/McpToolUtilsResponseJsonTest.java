/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link McpToolUtils#responseJson}: a resource's Response as a tool result.
 */
class McpToolUtilsResponseJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSerialization json = new JsonSerialization(mapper);

    private JsonNode result(Response response) throws Exception {
        return mapper.readTree(McpToolUtils.responseJson(json, response));
    }

    @Test
    @DisplayName("2xx: the entity, or the status when there is none")
    void success() throws Exception {
        assertEquals("v", result(Response.ok(Map.of("k", "v")).build()).get("k").asText());
        assertEquals(204, result(Response.noContent().build()).get("status").asInt());
    }

    @Test
    @DisplayName("a sentence body keeps the sentence; the code follows the status")
    void sentenceBody() throws Exception {
        JsonNode r = result(Response.status(409).entity(Map.of("error", "The backlog is full.")).build());
        assertEquals("CONFLICT", r.get("errorCode").asText());
        assertEquals("The backlog is full.", r.get("error").asText());
        assertEquals("NOT_FOUND", result(Response.status(404).entity(Map.of("error", "Group not found.")).build()).get("errorCode").asText());
        assertEquals("UNAVAILABLE", result(Response.status(503).entity(Map.of("error", "down")).build()).get("errorCode").asText());
        assertEquals("BAD_REQUEST", result(Response.status(400).entity(Map.of("error", "bad")).build()).get("errorCode").asText());
        assertEquals("FORBIDDEN", result(Response.status(403).entity(Map.of("error", "no")).build()).get("errorCode").asText());
    }

    @Test
    @DisplayName("a coded body keeps its code and message")
    void codedBody() throws Exception {
        JsonNode r = result(Response.status(409).entity(Map.of("error", "EVIDENCE_CHANGED", "message", "Re-check.")).build());
        assertEquals("EVIDENCE_CHANGED", r.get("errorCode").asText());
        assertEquals("Re-check.", r.get("error").asText());
    }

    @Test
    @DisplayName("a 5xx without a body, or no response at all, is INTERNAL without detail")
    void serverError() throws Exception {
        JsonNode r = result(Response.serverError().build());
        assertEquals("INTERNAL", r.get("errorCode").asText());
        assertEquals("The request failed (HTTP 500)", r.get("error").asText());
        assertEquals("INTERNAL", result(null).get("errorCode").asText());
    }
}
