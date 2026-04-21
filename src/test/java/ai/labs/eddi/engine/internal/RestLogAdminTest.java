package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.runtime.InstanceIdProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestLogAdmin}.
 */
class RestLogAdminTest {

    private BoundedLogStore boundedLogStore;
    private IDatabaseLogs databaseLogs;
    private InstanceIdProducer instanceIdProducer;
    private RestLogAdmin restLogAdmin;

    @BeforeEach
    void setUp() {
        boundedLogStore = mock(BoundedLogStore.class);
        databaseLogs = mock(IDatabaseLogs.class);
        instanceIdProducer = mock(InstanceIdProducer.class);
        restLogAdmin = new RestLogAdmin(boundedLogStore, databaseLogs, instanceIdProducer);
    }

    @Nested
    @DisplayName("getRecentLogs")
    class GetRecentLogs {

        @Test
        @DisplayName("should delegate to bounded log store")
        void delegatesToStore() {
            var entry = new LogEntry(System.currentTimeMillis(), "INFO", "test.Logger", "message",
                    null, "agent-1", null, "conv-1", null, null);
            when(boundedLogStore.getEntries("agent-1", null, "INFO", 50))
                    .thenReturn(List.of(entry));

            List<LogEntry> result = restLogAdmin.getRecentLogs("agent-1", null, "INFO", 50);

            assertEquals(1, result.size());
            assertEquals("message", result.get(0).message());
        }

        @Test
        @DisplayName("should return empty when no matching entries")
        void empty() {
            when(boundedLogStore.getEntries(any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            List<LogEntry> result = restLogAdmin.getRecentLogs(null, null, null, 100);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getHistoryLogs")
    class GetHistoryLogs {

        @Test
        @DisplayName("should delegate to database logs")
        void delegatesToDbLogs() {
            when(databaseLogs.getLogs(any(), anyString(), anyInt(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of());

            List<LogEntry> result = restLogAdmin.getHistoryLogs(
                    Deployment.Environment.production, "agent-1", 1,
                    null, null, null, 0, 100);

            assertTrue(result.isEmpty());
            verify(databaseLogs).getLogs(Deployment.Environment.production, "agent-1", 1,
                    null, null, null, 0, 100);
        }
    }

    @Nested
    @DisplayName("getInstanceId")
    class GetInstanceId {

        @Test
        @DisplayName("should return instance info with ID from producer")
        void returnsInstanceInfo() {
            when(instanceIdProducer.getInstanceId()).thenReturn("instance-abc-123");

            var result = restLogAdmin.getInstanceId();

            assertEquals("instance-abc-123", result.instanceId());
        }
    }
}
