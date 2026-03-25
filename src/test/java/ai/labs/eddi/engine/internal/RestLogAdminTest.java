package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IRestLogAdmin;
import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.runtime.InstanceIdProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
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
    private RestLogAdmin admin;

    @BeforeEach
    void setUp() {
        boundedLogStore = mock(BoundedLogStore.class);
        databaseLogs = mock(IDatabaseLogs.class);
        instanceIdProducer = mock(InstanceIdProducer.class);
        when(instanceIdProducer.getInstanceId()).thenReturn("test-host-1234");
        admin = new RestLogAdmin(boundedLogStore, databaseLogs, instanceIdProducer);
    }

    // ==================== Recent Logs ====================

    @Test
    void shouldReturnRecentLogsFromBuffer() {
        LogEntry entry = new LogEntry(System.currentTimeMillis(), "INFO", "test.Logger", "test message", "production", "agent-1", 1, "conv-1",
                "user-1", "test-host-1234");
        when(boundedLogStore.getEntries("agent-1", null, null, 200)).thenReturn(List.of(entry));

        List<LogEntry> result = admin.getRecentLogs("agent-1", null, null, 200);

        assertEquals(1, result.size());
        assertEquals("test message", result.get(0).message());
        verify(boundedLogStore).getEntries("agent-1", null, null, 200);
    }

    @Test
    void shouldReturnEmptyRecentLogs() {
        when(boundedLogStore.getEntries(null, null, null, 100)).thenReturn(Collections.emptyList());

        List<LogEntry> result = admin.getRecentLogs(null, null, null, 100);

        assertTrue(result.isEmpty());
    }

    // ==================== History Logs ====================

    @Test
    void shouldDelegateHistoryToDatabase() {
        DatabaseLog dbLog = new DatabaseLog();
        dbLog.put("message", "historic error");
        dbLog.put("level", "ERROR");
        when(databaseLogs.getLogs(Deployment.Environment.production, "agent-1", null, null, null, null, 0, 50)).thenReturn(List.of(dbLog));

        List<DatabaseLog> result = admin.getHistoryLogs(Deployment.Environment.production, "agent-1", null, null, null, null, 0, 50);

        assertEquals(1, result.size());
        assertEquals("historic error", result.get(0).get("message"));
        verify(databaseLogs).getLogs(Deployment.Environment.production, "agent-1", null, null, null, null, 0, 50);
    }

    @Test
    void shouldPassInstanceIdToHistory() {
        when(databaseLogs.getLogs(null, null, null, null, null, "instance-xyz", 0, 100)).thenReturn(Collections.emptyList());

        admin.getHistoryLogs(null, null, null, null, null, "instance-xyz", 0, 100);

        verify(databaseLogs).getLogs(null, null, null, null, null, "instance-xyz", 0, 100);
    }

    // ==================== Instance ID ====================

    @Test
    void shouldReturnInstanceId() {
        IRestLogAdmin.InstanceInfo info = admin.getInstanceId();

        assertEquals("test-host-1234", info.instanceId());
        verify(instanceIdProducer).getInstanceId();
    }
}
