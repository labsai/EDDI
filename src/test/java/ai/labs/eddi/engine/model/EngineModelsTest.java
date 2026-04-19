package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    // ==================== Deployment.Status ====================

    @Test
    void status_allValues() {
        assertEquals(4, Deployment.Status.values().length);
        assertNotNull(Deployment.Status.valueOf("READY"));
        assertNotNull(Deployment.Status.valueOf("IN_PROGRESS"));
        assertNotNull(Deployment.Status.valueOf("NOT_FOUND"));
        assertNotNull(Deployment.Status.valueOf("ERROR"));
    }

    // ==================== Context ====================

    @Test
    void context_defaultConstructor() {
        var ctx = new Context();
        assertNull(ctx.getType());
        assertNull(ctx.getValue());
    }

    @Test
    void context_fullConstructor() {
        var ctx = new Context(Context.ContextType.string, "hello");
        assertEquals(Context.ContextType.string, ctx.getType());
        assertEquals("hello", ctx.getValue());
    }

    @Test
    void context_objectType() {
        var ctx = new Context(Context.ContextType.object, Map.of("key", "val"));
        assertEquals(Context.ContextType.object, ctx.getType());
        assertInstanceOf(Map.class, ctx.getValue());
    }

    @Test
    void context_setters() {
        var ctx = new Context();
        ctx.setType(Context.ContextType.expressions);
        ctx.setValue("greeting(hello)");
        assertEquals(Context.ContextType.expressions, ctx.getType());
        assertEquals("greeting(hello)", ctx.getValue());
    }

    @Test
    void contextType_allValues() {
        assertEquals(4, Context.ContextType.values().length);
        assertNotNull(Context.ContextType.valueOf("string"));
        assertNotNull(Context.ContextType.valueOf("expressions"));
        assertNotNull(Context.ContextType.valueOf("object"));
        assertNotNull(Context.ContextType.valueOf("array"));
    }

    // ==================== InputData ====================

    @Test
    void inputData_defaults() {
        var input = new InputData();
        assertEquals("", input.getInput());
        assertNotNull(input.getContext());
        assertTrue(input.getContext().isEmpty());
    }

    @Test
    void inputData_fullConstructor() {
        var ctx = Map.of("lang", new Context(Context.ContextType.string, "en"));
        var input = new InputData("Hello", ctx);
        assertEquals("Hello", input.getInput());
        assertEquals(1, input.getContext().size());
    }

    @Test
    void inputData_setters() {
        var input = new InputData();
        input.setInput("How are you?");
        input.setContext(Map.of("mood", new Context(Context.ContextType.string, "happy")));
        assertEquals("How are you?", input.getInput());
        assertEquals(1, input.getContext().size());
    }

    // ==================== DeadLetterEntry ====================

    @Test
    void deadLetterEntry_record() {
        var entry = new DeadLetterEntry("dl-1", "conv-123", "timeout", 1700000000L, "{}");
        assertEquals("dl-1", entry.id());
        assertEquals("conv-123", entry.conversationId());
        assertEquals("timeout", entry.error());
        assertEquals(1700000000L, entry.timestamp());
        assertEquals("{}", entry.payload());
    }

    @Test
    void deadLetterEntry_jackson() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"id":"dl-1","conversationId":"c1","error":"timeout","timestamp":0,"payload":"{}"}
                """;
        var entry = mapper.readValue(json, DeadLetterEntry.class);
        assertEquals("dl-1", entry.id());
        assertEquals("c1", entry.conversationId());
    }

    // ==================== AgentDeploymentStatus ====================

    @Test
    void agentDeploymentStatus_defaults() {
        var status = new AgentDeploymentStatus();
        assertEquals(Deployment.Environment.production, status.getEnvironment());
        assertEquals(Deployment.Status.NOT_FOUND, status.getStatus());
        assertNull(status.getAgentId());
        assertNull(status.getAgentVersion());
    }

    @Test
    void agentDeploymentStatus_setters() {
        var status = new AgentDeploymentStatus();
        status.setAgentId("agent-1");
        status.setAgentVersion(3);
        status.setEnvironment(Deployment.Environment.test);
        status.setStatus(Deployment.Status.READY);
        assertEquals("agent-1", status.getAgentId());
        assertEquals(3, status.getAgentVersion());
        assertEquals(Deployment.Environment.test, status.getEnvironment());
        assertEquals(Deployment.Status.READY, status.getStatus());
    }

    // ==================== CoordinatorStatus ====================

    @Test
    void coordinatorStatus_record() {
        var status = new CoordinatorStatus(
                "in-memory", true, "ok", 5, 100L, 2L, java.util.Map.of("c1", 3));
        assertEquals("in-memory", status.coordinatorType());
        assertTrue(status.connected());
        assertEquals("ok", status.connectionStatus());
        assertEquals(5, status.activeConversations());
        assertEquals(100L, status.totalProcessed());
        assertEquals(2L, status.totalDeadLettered());
        assertEquals(3, status.queueDepths().get("c1"));
    }

    // ==================== AgentDeployment ====================

    @Test
    void agentDeployment_defaults() {
        var deployment = new AgentDeployment();
        assertEquals(Deployment.Environment.production, deployment.getEnvironment());
        assertNull(deployment.getAgentId());
        assertNotNull(deployment.getInitialContext());
        assertTrue(deployment.getInitialContext().isEmpty());
    }

    @Test
    void agentDeployment_setters() {
        var deployment = new AgentDeployment();
        deployment.setEnvironment(Deployment.Environment.test);
        deployment.setAgentId("agent-42");
        deployment.setInitialContext(Map.of(
                "lang", new Context(Context.ContextType.string, "en")));

        assertEquals(Deployment.Environment.test, deployment.getEnvironment());
        assertEquals("agent-42", deployment.getAgentId());
        assertEquals(1, deployment.getInitialContext().size());
    }

    // ==================== LogEntry ====================

    @Test
    void logEntry_record() {
        var entry = new LogEntry(
                1700000000L, "INFO", "ai.labs.eddi.TestClass",
                "Test message", "production", "agent-1", 3,
                "conv-123", "user-1", "inst-1");
        assertEquals(1700000000L, entry.timestamp());
        assertEquals("INFO", entry.level());
        assertEquals("ai.labs.eddi.TestClass", entry.loggerName());
        assertEquals("Test message", entry.message());
        assertEquals("production", entry.environment());
        assertEquals("agent-1", entry.agentId());
        assertEquals(3, entry.agentVersion());
        assertEquals("conv-123", entry.conversationId());
        assertEquals("user-1", entry.userId());
        assertEquals("inst-1", entry.instanceId());
    }

    @Test
    void logEntry_nullFields() {
        var entry = new LogEntry(0L, "WARN", "logger", "msg",
                null, null, null, null, null, null);
        assertNull(entry.environment());
        assertNull(entry.agentId());
        assertNull(entry.agentVersion());
    }

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
