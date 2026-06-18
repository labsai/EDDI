/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PostgresConversationMemoryStoreUnitTest {

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

    private PostgresConversationMemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        store = new PostgresConversationMemoryStore(dataSourceInstance, jsonSerialization);
    }

    // ─── storeConversationMemorySnapshot ───

    @Test
    void storeSnapshot_newConversation_insertsAndReturnsId() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot(null);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{\"test\":true}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        String id = store.storeConversationMemorySnapshot(snapshot);

        assertNotNull(id);
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void storeSnapshot_existingConversation_updates() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot("conv-123");
        when(jsonSerialization.serialize(snapshot)).thenReturn("{\"test\":true}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        String id = store.storeConversationMemorySnapshot(snapshot);

        assertEquals("conv-123", id);
        verify(preparedStatement).setString(5, "conv-123");
    }

    @Test
    void storeSnapshot_ioException_throwsRuntimeException() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot(null);
        when(jsonSerialization.serialize(snapshot)).thenThrow(new IOException("serialization error"));

        assertThrows(RuntimeException.class, () -> store.storeConversationMemorySnapshot(snapshot));
    }

    @Test
    void storeSnapshot_nullState_defaultsToInProgress() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot(null);
        snapshot.setConversationState(null);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.storeConversationMemorySnapshot(snapshot);

        verify(preparedStatement).setString(eq(4), eq("IN_PROGRESS"));
    }

    // ─── loadConversationMemorySnapshot ───

    @Test
    void loadSnapshot_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("data")).thenReturn("{\"agentId\":\"a1\"}");

        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationSteps(Collections.emptyList());
        when(jsonSerialization.deserialize("{\"agentId\":\"a1\"}", ConversationMemorySnapshot.class)).thenReturn(snapshot);

        ConversationMemorySnapshot result = store.loadConversationMemorySnapshot("conv-1");

        assertNotNull(result);
        assertEquals("conv-1", result.getConversationId());
    }

    @Test
    void loadSnapshot_notFound_returnsNull() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertNull(store.loadConversationMemorySnapshot("missing-id"));
    }

    @Test
    void loadSnapshot_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));
        assertThrows(RuntimeException.class, () -> store.loadConversationMemorySnapshot("id"));
    }

    // ─── loadActiveConversationMemorySnapshot ───

    @Test
    void loadActiveSnapshots_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("data")).thenReturn("{}");
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        when(jsonSerialization.deserialize("{}", ConversationMemorySnapshot.class)).thenReturn(snapshot);

        List<ConversationMemorySnapshot> results = store.loadActiveConversationMemorySnapshot("agent-1", 1);

        assertEquals(1, results.size());
    }

    @Test
    void loadActiveSnapshots_exception_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.loadActiveConversationMemorySnapshot("agent-1", 1));
    }

    // ─── setConversationState ───

    @Test
    void setConversationState_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> store.setConversationState("conv-1", ConversationState.ENDED));
        verify(preparedStatement).setString(1, "ENDED");
    }

    @Test
    void setConversationState_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.setConversationState("conv", ConversationState.ENDED));
    }

    // ─── deleteConversationMemorySnapshot ───

    @Test
    void deleteSnapshot_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> store.deleteConversationMemorySnapshot("conv-1"));
    }

    @Test
    void deleteSnapshot_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.deleteConversationMemorySnapshot("conv"));
    }

    // ─── getConversationState ───

    @Test
    void getConversationState_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("conversation_state")).thenReturn("READY");

        assertEquals(ConversationState.READY, store.getConversationState("conv-1"));
    }

    @Test
    void getConversationState_notFound_returnsNull() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertNull(store.getConversationState("missing"));
    }

    @Test
    void getConversationState_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.getConversationState("conv"));
    }

    // ─── getActiveConversationCount ───

    @Test
    void getActiveConversationCount_returnsCount() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(42L);

        assertEquals(42L, store.getActiveConversationCount("agent-1", 1));
    }

    @Test
    void getActiveConversationCount_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.getActiveConversationCount("a", 1));
    }

    // ─── getEndedConversationIds ───

    @Test
    void getEndedConversationIds_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("id")).thenReturn("conv-1", "conv-2");

        List<String> ids = store.getEndedConversationIds();
        assertEquals(2, ids.size());
        assertEquals("conv-1", ids.get(0));
    }

    @Test
    void getEndedConversationIds_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.getEndedConversationIds());
    }

    // ─── IResourceStore delegate methods ───

    @Test
    void readIncludingDeleted_delegatesToLoad() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertNull(store.readIncludingDeleted("id-1", 0));
    }

    @Test
    void create_delegatesToStore() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot(null);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        IResourceStore.IResourceId resourceId = store.create(snapshot);
        assertNotNull(resourceId.getId());
        assertEquals(0, resourceId.getVersion());
    }

    @Test
    void read_delegatesToLoad() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertNull(store.read("id-1", 0));
    }

    @Test
    void update_delegatesToStore() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot("conv-1");
        when(jsonSerialization.serialize(snapshot)).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        Integer result = store.update("conv-1", 0, snapshot);
        assertEquals(0, result);
    }

    @Test
    void delete_delegatesToDeleteSnapshot() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> store.delete("conv-1", 0));
    }

    @Test
    void deleteAllPermanently_delegatesToDeleteSnapshot() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> store.deleteAllPermanently("conv-1"));
    }

    @Test
    void getCurrentResourceId_returnsId() {
        IResourceStore.IResourceId resourceId = store.getCurrentResourceId("conv-1");
        assertEquals("conv-1", resourceId.getId());
        assertEquals(0, resourceId.getVersion());
    }

    // ─── GDPR ───

    @Test
    void getConversationIdsByUserId_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("conv-1");

        List<String> ids = store.getConversationIdsByUserId("user-1");
        assertEquals(1, ids.size());
        assertEquals("conv-1", ids.get(0));
    }

    @Test
    void getConversationIdsByUserId_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.getConversationIdsByUserId("user-1"));
    }

    @Test
    void deleteConversationsByUserId_returnsCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(3);
        assertEquals(3, store.deleteConversationsByUserId("user-1"));
    }

    @Test
    void deleteConversationsByUserId_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));
        assertThrows(RuntimeException.class, () -> store.deleteConversationsByUserId("user-1"));
    }

    // ─── Helpers ───

    private ConversationMemorySnapshot createSnapshot(String conversationId) {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(conversationId);
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.IN_PROGRESS);
        snapshot.setConversationSteps(Collections.emptyList());
        return snapshot;
    }
}
