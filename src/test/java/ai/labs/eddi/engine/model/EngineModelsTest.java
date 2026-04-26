/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for engine model classes. Focuses on serialization contracts, backward
 * compatibility, and non-obvious default values — NOT trivial getter/setter
 * verification.
 */
class EngineModelsTest {

    // ==================== Deployment.Environment ====================

    @Test
    void environment_fromString_production() {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString("production"));
    }

    @Test
    void environment_fromString_test() {
        assertEquals(Deployment.Environment.test,
                Deployment.Environment.fromString("test"));
    }

    @ParameterizedTest
    @CsvSource({"unrestricted", "restricted", "PRODUCTION", "Production"})
    void environment_fromString_backwardCompat_production(String value) {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString(value));
    }

    @Test
    void environment_fromString_null_defaultsToProduction() {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString(null));
    }

    @Test
    void environment_fromString_unknown_defaultsToProduction() {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString("unknown_value"));
    }

    @Test
    void environment_toValue() {
        assertEquals("production", Deployment.Environment.production.toValue());
        assertEquals("test", Deployment.Environment.test.toValue());
    }

    @Test
    void environment_jackson_roundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(Deployment.Environment.production);
        assertEquals("\"production\"", json);
        var deserialized = mapper.readValue(json, Deployment.Environment.class);
        assertEquals(Deployment.Environment.production, deserialized);
    }

    @Test
    void environment_jackson_backwardCompat() throws Exception {
        var mapper = new ObjectMapper();
        var deserialized = mapper.readValue("\"unrestricted\"", Deployment.Environment.class);
        assertEquals(Deployment.Environment.production, deserialized);
    }

    // ==================== Context ====================

    @Test
    void context_fullConstructor() {
        var ctx = new Context(Context.ContextType.string, "hello");
        assertEquals(Context.ContextType.string, ctx.getType());
        assertEquals("hello", ctx.getValue());
    }

    @Test
    void context_objectType_holdsArbitraryValues() {
        var ctx = new Context(Context.ContextType.object, Map.of("key", "val"));
        assertEquals(Context.ContextType.object, ctx.getType());
        assertInstanceOf(Map.class, ctx.getValue());
    }

    // ==================== InputData ====================

    @Test
    void inputData_defaults_nonNull() {
        // API contract: input defaults to empty string, context to empty map — never
        // null
        var input = new InputData();
        assertEquals("", input.getInput());
        assertNotNull(input.getContext());
        assertTrue(input.getContext().isEmpty());
    }

    // ==================== DeadLetterEntry ====================

    @Test
    void deadLetterEntry_jackson() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"id":"dl-1","conversationId":"c1","error":"timeout","timestamp":0,"payload":"{}"}
                """;
        var entry = mapper.readValue(json, DeadLetterEntry.class);
        assertEquals("dl-1", entry.id());
        assertEquals("c1", entry.conversationId());
        assertEquals("timeout", entry.error());
    }

    // ==================== AgentDeploymentStatus ====================

    @Test
    void agentDeploymentStatus_defaults() {
        // Contract: new status defaults to production/NOT_FOUND — used by REST layer
        var status = new AgentDeploymentStatus();
        assertEquals(Deployment.Environment.production, status.getEnvironment());
        assertEquals(Deployment.Status.NOT_FOUND, status.getStatus());
    }

    // ==================== CoordinatorStatus ====================

    @Test
    void coordinatorStatus_record() {
        var status = new CoordinatorStatus(
                "in-memory", true, "ok", 5, 100L, 2L, java.util.Map.of("c1", 3));
        assertEquals("in-memory", status.coordinatorType());
        assertTrue(status.connected());
        assertEquals(5, status.activeConversations());
        assertEquals(100L, status.totalProcessed());
        assertEquals(2L, status.totalDeadLettered());
    }

    // ==================== LogEntry ====================

    @Test
    void logEntry_jacksonRoundTrip() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var entry = new LogEntry(1700000000L, "ERROR", "logger",
                "Something failed", "test", "a1", 1,
                "c1", "u1", "i1");
        var json = mapper.writeValueAsString(entry);
        var deserialized = mapper.readValue(json, LogEntry.class);
        assertEquals(entry.timestamp(), deserialized.timestamp());
        assertEquals(entry.message(), deserialized.message());
        assertEquals(entry.agentId(), deserialized.agentId());
    }

    @Test
    void logEntry_jsonExcludesNulls() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var entry = new LogEntry(0L, "INFO", "log", "msg",
                null, null, null, null, null, null);
        var json = mapper.writeValueAsString(entry);
        assertFalse(json.contains("agentId"));
        assertFalse(json.contains("userId"));
    }
}
