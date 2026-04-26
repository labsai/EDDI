/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class A2AModelsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- AgentCard ---

    @Test
    void agentCard_construction() {
        var card = new A2AModels.AgentCard(
                "TestAgent", "A test agent", "http://localhost:7070/a2a/agents/test",
                "EDDI", "6.0.0",
                new A2AModels.AgentCapabilities(false, false, true),
                List.of(new A2AModels.AgentSkill("chat", "Chat", "General chat", List.of("ai"), null)),
                null);

        assertEquals("TestAgent", card.name());
        assertEquals("EDDI", card.provider());
        assertTrue(card.capabilities().stateTransitionHistory());
        assertEquals(1, card.skills().size());
        assertNull(card.authentication());
    }

    @Test
    void agentCard_jacksonRoundTrip() throws Exception {
        var card = new A2AModels.AgentCard("Agent", "desc", "http://url", "EDDI", "6.0.0",
                new A2AModels.AgentCapabilities(true, false, true),
                List.of(), null);

        String json = mapper.writeValueAsString(card);
        assertFalse(json.contains("authentication")); // null excluded
        assertTrue(json.contains("\"name\":\"Agent\""));

        var restored = mapper.readValue(json, A2AModels.AgentCard.class);
        assertEquals("Agent", restored.name());
    }

    // --- AgentAuthentication ---

    @Test
    void agentAuthentication_construction() {
        var auth = new A2AModels.AgentAuthentication(List.of("Bearer"), "http://auth/token");
        assertEquals(List.of("Bearer"), auth.schemes());
        assertEquals("http://auth/token", auth.credentials());
    }

    // --- AgentSkill ---

    @Test
    void agentSkill_construction() {
        var skill = new A2AModels.AgentSkill("weather", "Weather", "Get weather", List.of("weather"), List.of("What's the weather?"));
        assertEquals("weather", skill.id());
        assertEquals(1, skill.tags().size());
        assertEquals(1, skill.examples().size());
    }

    // --- Part factory methods ---

    @Test
    void textPart_factory() {
        var part = A2AModels.Part.textPart("Hello");
        assertEquals("text", part.type());
        assertEquals("Hello", part.text());
        assertNull(part.data());
        assertNull(part.metadata());
    }

    @Test
    void dataPart_factory() {
        var part = A2AModels.Part.dataPart(Map.of("key", "value"));
        assertEquals("data", part.type());
        assertNull(part.text());
        assertEquals("value", part.data().get("key"));
    }

    // --- JSON-RPC ---

    @Test
    void jsonRpcRequest_construction() {
        var req = new A2AModels.JsonRpcRequest("2.0", "tasks/send", Map.of("message", "hi"), 1);
        assertEquals("2.0", req.jsonrpc());
        assertEquals("tasks/send", req.method());
        assertEquals(1, req.id());
    }

    @Test
    void jsonRpcResponse_construction() {
        var resp = new A2AModels.JsonRpcResponse("2.0", 1, "result", null);
        assertEquals("2.0", resp.jsonrpc());
        assertEquals("result", resp.result());
        assertNull(resp.error());
    }

    @Test
    void jsonRpcError_construction() {
        var err = new A2AModels.JsonRpcError(-32601, "Method not found", null);
        assertEquals(-32601, err.code());
        assertEquals("Method not found", err.message());
    }

    // --- TaskState ---

    @Test
    void taskState_allValues() {
        assertEquals(7, A2AModels.TaskState.values().length);
        assertNotNull(A2AModels.TaskState.submitted);
        assertNotNull(A2AModels.TaskState.completed);
        assertNotNull(A2AModels.TaskState.failed);
    }

    // --- A2ATask ---

    @Test
    void a2aTask_construction() {
        var msg = new A2AModels.A2AMessage("user", List.of(A2AModels.Part.textPart("hello")), null);
        var task = new A2AModels.A2ATask("task-1", "ctx-1", A2AModels.TaskState.working,
                List.of(msg), null, null);

        assertEquals("task-1", task.id());
        assertEquals(A2AModels.TaskState.working, task.status());
        assertEquals(1, task.history().size());
    }

    // --- Artifact ---

    @Test
    void artifact_construction() {
        var artifact = new A2AModels.Artifact("doc", "A document",
                List.of(A2AModels.Part.textPart("content")), 0, null);
        assertEquals("doc", artifact.name());
        assertEquals(0, artifact.index());
    }

    // --- Error codes ---

    @Test
    void errorCodes() {
        assertEquals(-32001, A2AModels.ERROR_TASK_NOT_FOUND);
        assertEquals(-32002, A2AModels.ERROR_TASK_NOT_CANCELABLE);
        assertEquals(-32601, A2AModels.ERROR_METHOD_NOT_FOUND);
        assertEquals(-32602, A2AModels.ERROR_INVALID_PARAMS);
        assertEquals(-32603, A2AModels.ERROR_INTERNAL);
    }
}
