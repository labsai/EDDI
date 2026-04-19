package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogEntryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void constructor_setsAllFields() {
        var entry = new LogEntry(
                1000L, "INFO", "ai.labs.test", "Test message",
                "production", "agent-1", 3, "conv-1", "user-1", "inst-1");

        assertEquals(1000L, entry.timestamp());
        assertEquals("INFO", entry.level());
        assertEquals("ai.labs.test", entry.loggerName());
        assertEquals("Test message", entry.message());
        assertEquals("production", entry.environment());
        assertEquals("agent-1", entry.agentId());
        assertEquals(3, entry.agentVersion());
        assertEquals("conv-1", entry.conversationId());
        assertEquals("user-1", entry.userId());
        assertEquals("inst-1", entry.instanceId());
    }

    @Test
    void nullableFields_allowNull() {
        var entry = new LogEntry(
                0L, "WARN", null, "msg",
                null, null, null, null, null, null);

        assertNull(entry.loggerName());
        assertNull(entry.environment());
        assertNull(entry.agentId());
        assertNull(entry.agentVersion());
        assertNull(entry.conversationId());
        assertNull(entry.userId());
        assertNull(entry.instanceId());
    }

    @Test
    void jacksonSerialization_excludesNulls() throws Exception {
        var entry = new LogEntry(
                1000L, "INFO", "logger", "msg",
                null, null, null, null, null, null);

        String json = mapper.writeValueAsString(entry);
        assertFalse(json.contains("agentId"));
        assertFalse(json.contains("conversationId"));
        assertTrue(json.contains("\"level\":\"INFO\""));
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        var entry = new LogEntry(
                5000L, "ERROR", "ai.labs.eddi", "Something went wrong",
                "test", "agent-2", 1, "conv-5", "user-3", "inst-7");

        String json = mapper.writeValueAsString(entry);
        LogEntry restored = mapper.readValue(json, LogEntry.class);

        assertEquals(entry.timestamp(), restored.timestamp());
        assertEquals(entry.level(), restored.level());
        assertEquals(entry.message(), restored.message());
        assertEquals(entry.agentId(), restored.agentId());
        assertEquals(entry.conversationId(), restored.conversationId());
    }

    @Test
    void equals_sameValues_true() {
        var e1 = new LogEntry(1L, "INFO", "l", "m", null, null, null, null, null, null);
        var e2 = new LogEntry(1L, "INFO", "l", "m", null, null, null, null, null, null);
        assertEquals(e1, e2);
    }

    @Test
    void equals_differentValues_false() {
        var e1 = new LogEntry(1L, "INFO", "l", "m1", null, null, null, null, null, null);
        var e2 = new LogEntry(1L, "INFO", "l", "m2", null, null, null, null, null, null);
        assertNotEquals(e1, e2);
    }
}
