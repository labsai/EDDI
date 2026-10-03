/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.A2ATaskRecord;
import ai.labs.eddi.engine.a2a.A2AModels.TaskState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2AModelsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void agentCard_omitsNullFields_andRoundTrips() throws Exception {
        var card = new A2AModels.AgentCard("Agent", "desc", "http://url", "0.3.0", "JSONRPC",
                List.of(new A2AModels.AgentInterface("http://url", "JSONRPC", "1.0")), new A2AModels.AgentProvider("EDDI", "http://x"),
                "6.6.0", new A2AModels.AgentCapabilities(true, false, false), null, null, List.of("text/plain"), List.of("text/plain"),
                List.of(new A2AModels.AgentSkill("chat", "Chat", "General chat", List.of("ai"), null)), null);

        String json = mapper.writeValueAsString(card);
        assertFalse(json.contains("authentication"));
        assertFalse(json.contains("securitySchemes"));
        assertTrue(json.contains("\"supportedInterfaces\""));

        var restored = mapper.readValue(json, A2AModels.AgentCard.class);
        assertEquals("Agent", restored.name());
        assertEquals("1.0", restored.supportedInterfaces().getFirst().protocolVersion());
    }

    @Test
    void parts() {
        assertEquals("Hello", A2AModels.Part.textPart("Hello").text());
        assertNull(A2AModels.Part.textPart("Hello").data());
        assertEquals(Map.of("k", "v"), A2AModels.Part.dataPart(Map.of("k", "v")).data());
    }

    @Test
    void jsonRpcRecords() {
        var req = new A2AModels.JsonRpcRequest("2.0", "SendMessage", Map.of("message", "hi"), 1);
        assertEquals("SendMessage", req.method());
        var err = new A2AModels.JsonRpcError(-32601, "Method not found", null);
        assertEquals(-32601, err.code());
    }

    @Test
    void taskStateAccessor_onATaskWithoutStatus_isUnknown() {
        assertEquals(TaskState.unknown, new A2AModels.A2ATask("t", null, null, null, null).state());
    }

    @Test
    void taskRecord_withOutcome_keepsIdentity_andMovesTheTimestamp() {
        var record = new A2ATaskRecord("t", "c", "conv", "agent", TaskState.working, null, null, "hi", Instant.EPOCH);

        var settled = record.withOutcome(TaskState.completed, null, "answer");

        assertEquals("t", settled.taskId());
        assertEquals("conv", settled.conversationId());
        assertEquals("hi", settled.userText());
        assertEquals(TaskState.completed, settled.state());
        assertEquals("answer", settled.responseText());
        assertNotEquals(Instant.EPOCH, settled.updatedAt());
    }

    @Test
    void taskRecord_isSerializable_forAClusteredStore() throws Exception {
        var record = new A2ATaskRecord("t", "c", "conv", "agent", TaskState.input_required, "waiting", null, "hi", Instant.EPOCH);
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(record);
        }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(record, in.readObject());
        }
    }

    @Test
    void errorCodes_matchTheSpec() {
        assertEquals(-32001, A2AModels.ERROR_TASK_NOT_FOUND);
        assertEquals(-32002, A2AModels.ERROR_TASK_NOT_CANCELABLE);
        assertEquals(-32003, A2AModels.ERROR_PUSH_NOTIFICATION_NOT_SUPPORTED);
        assertEquals(-32004, A2AModels.ERROR_UNSUPPORTED_OPERATION);
        assertEquals(-32005, A2AModels.ERROR_CONTENT_TYPE_NOT_SUPPORTED);
        assertEquals(-32007, A2AModels.ERROR_EXTENDED_CARD_NOT_CONFIGURED);
        assertEquals(-32009, A2AModels.ERROR_VERSION_NOT_SUPPORTED);
        assertEquals(-32601, A2AModels.ERROR_METHOD_NOT_FOUND);
        assertEquals(-32602, A2AModels.ERROR_INVALID_PARAMS);
        assertEquals(-32603, A2AModels.ERROR_INTERNAL);
    }

    @Test
    void invalidRequestDefaultsToInvalidParams() {
        assertEquals(A2AModels.ERROR_INVALID_PARAMS, new A2AModels.InvalidA2ARequestException("x").getCode());
        assertEquals(A2AModels.ERROR_TASK_NOT_FOUND, new A2AModels.InvalidA2ARequestException(A2AModels.ERROR_TASK_NOT_FOUND, "x").getCode());
    }
}
