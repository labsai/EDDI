/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostgresAuditStoreUnitTest {

    @Mock
    private Instance<DataSource> dataSourceInstance;
    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private Statement statement;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;
    @Mock
    private IJsonSerialization jsonSerialization;

    private PostgresAuditStore store;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        store = new PostgresAuditStore(dataSourceInstance, jsonSerialization);
    }

    // ─── appendEntry ───

    @Test
    void appendEntry_happyPath() throws Exception {
        AuditEntry entry = createEntry();
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertDoesNotThrow(() -> store.appendEntry(entry));
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void appendEntry_nullId_generatesUuid() throws Exception {
        AuditEntry entry = new AuditEntry(null, "conv-1", "agent-1", 1, "user-1", "production",
                0, "task-1", "langchain", 0, 100L, null, null, null, null, null, 0.0, Instant.now(), null, null);
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertDoesNotThrow(() -> store.appendEntry(entry));
        verify(preparedStatement).setString(eq(1), anyString()); // UUID generated
    }

    @Test
    void appendEntry_nullTimestamp_usesNow() throws Exception {
        AuditEntry entry = new AuditEntry("id-1", "conv-1", "agent-1", 1, "user-1", "production",
                0, "task-1", "langchain", 0, 100L, null, null, null, null, null, 0.0, null, null, null);
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertDoesNotThrow(() -> store.appendEntry(entry));
        verify(preparedStatement).setTimestamp(eq(14), any(Timestamp.class));
    }

    @Test
    void appendEntry_withAllDataFields() throws Exception {
        AuditEntry entry = new AuditEntry("id-1", "conv-1", "agent-1", 1, "user-1", "production",
                0, "task-1", "langchain", 0, 100L,
                Map.of("key", "val"), Map.of("out", "val"),
                Map.of("model", "gpt"), Map.of("tool", "web"),
                List.of("action1"), 0.05, Instant.now(), "hmac-val", null);
        when(jsonSerialization.serialize(any())).thenReturn("{\"input\":{}}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertDoesNotThrow(() -> store.appendEntry(entry));
    }

    @Test
    void appendEntry_sqlException_throwsRuntimeException() throws Exception {
        AuditEntry entry = createEntry();
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        assertThrows(RuntimeException.class, () -> store.appendEntry(entry));
    }

    // ─── appendBatch ───

    @Test
    void appendBatch_nullEntries_doesNothing() {
        assertDoesNotThrow(() -> store.appendBatch(null));
        verifyNoInteractions(dataSource);
    }

    @Test
    void appendBatch_emptyEntries_doesNothing() {
        assertDoesNotThrow(() -> store.appendBatch(List.of()));
        verifyNoInteractions(dataSource);
    }

    @Test
    void appendBatch_multipleEntries() throws Exception {
        AuditEntry entry1 = createEntry();
        AuditEntry entry2 = createEntry();
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        store.appendBatch(List.of(entry1, entry2));

        verify(preparedStatement, times(2)).addBatch();
        verify(preparedStatement).executeBatch();
    }

    @Test
    void appendBatch_sqlException_throwsRuntimeException() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeBatch()).thenThrow(new SQLException("batch error"));

        assertThrows(RuntimeException.class, () -> store.appendBatch(List.of(createEntry())));
    }

    // ─── getEntries ───

    @Test
    void getEntries_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        mockAuditResultSet();

        List<AuditEntry> entries = store.getEntries("conv-1", 0, 10);
        assertEquals(1, entries.size());
        assertEquals("conv-1", entries.get(0).conversationId());
    }

    @Test
    void getEntries_empty() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<AuditEntry> entries = store.getEntries("conv-1", 0, 10);
        assertTrue(entries.isEmpty());
    }

    @Test
    void getEntries_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.getEntries("conv", 0, 10));
    }

    // ─── getEntriesByAgent ───

    @Test
    void getEntriesByAgent_withVersion() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        mockAuditResultSet();

        List<AuditEntry> entries = store.getEntriesByAgent("agent-1", 1, 0, 10);
        assertEquals(1, entries.size());
    }

    @Test
    void getEntriesByAgent_withoutVersion() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        mockAuditResultSet();

        List<AuditEntry> entries = store.getEntriesByAgent("agent-1", null, 0, 10);
        assertEquals(1, entries.size());
    }

    // ─── countByConversation ───

    @Test
    void countByConversation_returnsCount() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(5L);

        assertEquals(5L, store.countByConversation("conv-1"));
    }

    @Test
    void countByConversation_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.countByConversation("conv"));
    }

    // ─── getEntriesByUserId ───

    @Test
    void getEntriesByUserId_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        mockAuditResultSet();

        List<AuditEntry> entries = store.getEntriesByUserId("user-1", 0, 10);
        assertEquals(1, entries.size());
    }

    // ─── GDPR: pseudonymizeByUserId ───

    @Test
    void pseudonymizeByUserId_returnsCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(3);

        assertEquals(3, store.pseudonymizeByUserId("user-1", "anon-123"));
        verify(preparedStatement).setString(1, "anon-123");
        verify(preparedStatement).setString(2, "user-1");
    }

    @Test
    void pseudonymizeByUserId_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.pseudonymizeByUserId("u", "p"));
    }

    // ─── Helpers ───

    private AuditEntry createEntry() {
        return new AuditEntry("entry-1", "conv-1", "agent-1", 1, "user-1", "production",
                0, "task-1", "langchain", 0, 100L, null, null, null, null, null, 0.0, Instant.now(), null, null);
    }

    @SuppressWarnings("unchecked")
    private void mockAuditResultSet() throws Exception {
        Timestamp now = Timestamp.from(Instant.now());
        when(resultSet.getString("id")).thenReturn("entry-1");
        when(resultSet.getString("conversation_id")).thenReturn("conv-1");
        when(resultSet.getString("AGENT_ID")).thenReturn("agent-1");
        when(resultSet.getInt("AGENT_VERSION")).thenReturn(1);
        when(resultSet.getString("user_id")).thenReturn("user-1");
        when(resultSet.getString("environment")).thenReturn("production");
        when(resultSet.getInt("step_index")).thenReturn(0);
        when(resultSet.getString("task_id")).thenReturn("task-1");
        when(resultSet.getString("task_type")).thenReturn("langchain");
        when(resultSet.getInt("task_index")).thenReturn(0);
        when(resultSet.getLong("duration_ms")).thenReturn(100L);
        when(resultSet.getDouble("cost")).thenReturn(0.0);
        when(resultSet.getString("hmac")).thenReturn(null);
        when(resultSet.getTimestamp("created_at")).thenReturn(now);
        when(resultSet.getString("data")).thenReturn("{}");
        when(jsonSerialization.deserialize(eq("{}"), eq(Map.class))).thenReturn(Map.of());
    }
}
