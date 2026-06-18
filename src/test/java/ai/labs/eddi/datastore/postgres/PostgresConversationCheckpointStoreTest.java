/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresConversationCheckpointStore} with mocked JDBC
 * connections.
 */
class PostgresConversationCheckpointStoreTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private IJsonSerialization jsonSerialization;
    @SuppressWarnings("unchecked")
    private Instance<DataSource> dataSourceInstance;
    private PostgresConversationCheckpointStore sut;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        jsonSerialization = mock(IJsonSerialization.class);
        dataSourceInstance = mock(Instance.class);

        when(dataSourceInstance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        sut = new PostgresConversationCheckpointStore(dataSourceInstance, jsonSerialization);
    }

    // ─── create() ───────────────────────────────────────────────

    @Test
    void create_validCheckpoint_executesInsert() throws Exception {
        // given
        Instant now = Instant.now();
        var checkpoint = new MemoryCheckpoint(
                "ckpt-1", "conv-1", null, 3,
                Map.of(), now, "test-trigger", "TestClass");
        when(jsonSerialization.serialize(checkpoint)).thenReturn("{\"checkpointId\":\"ckpt-1\"}");

        // when
        sut.create(checkpoint);

        // then
        verify(preparedStatement).setString(1, "ckpt-1");
        verify(preparedStatement).setString(2, "conv-1");
        verify(preparedStatement).setString(3, null); // parentConversationId
        verify(preparedStatement).setInt(4, 3);
        verify(preparedStatement).setTimestamp(eq(5), any(Timestamp.class));
        verify(preparedStatement).setString(6, "test-trigger");
        verify(preparedStatement).setString(7, "TestClass");
        verify(preparedStatement).setString(8, "{\"checkpointId\":\"ckpt-1\"}");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void create_withParentConversation_setsParentId() throws Exception {
        // given
        var checkpoint = new MemoryCheckpoint(
                "ckpt-2", "conv-fork", "conv-parent", 5,
                Map.of(), Instant.now(), "fork", "ForkClass");
        when(jsonSerialization.serialize(checkpoint)).thenReturn("{}");

        // when
        sut.create(checkpoint);

        // then
        verify(preparedStatement).setString(3, "conv-parent");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void create_ioException_throwsRuntimeException() throws Exception {
        // given
        var checkpoint = new MemoryCheckpoint(
                "ckpt-3", "conv-1", null, 0,
                Map.of(), Instant.now(), "trigger", "Class");
        when(jsonSerialization.serialize(checkpoint)).thenThrow(new IOException("Serialize error"));

        // when/then
        assertThrows(RuntimeException.class, () -> sut.create(checkpoint));
    }

    @Test
    void create_sqlException_throwsRuntimeException() throws Exception {
        // given
        var checkpoint = new MemoryCheckpoint(
                "ckpt-4", "conv-1", null, 0,
                Map.of(), Instant.now(), "trigger", "Class");
        when(jsonSerialization.serialize(checkpoint)).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(RuntimeException.class, () -> sut.create(checkpoint));
    }

    // ─── findByConversationId() ─────────────────────────────────

    @Test
    void findByConversationId_multipleResults() throws Exception {
        // given
        var ckpt1 = new MemoryCheckpoint(
                "c1", "conv-1", null, 1, Map.of(), Instant.now(), "t1", "C1");
        var ckpt2 = new MemoryCheckpoint(
                "c2", "conv-1", null, 2, Map.of(), Instant.now(), "t2", "C2");
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("data")).thenReturn("{\"c1\":true}", "{\"c2\":true}");
        when(jsonSerialization.deserialize("{\"c1\":true}", MemoryCheckpoint.class)).thenReturn(ckpt1);
        when(jsonSerialization.deserialize("{\"c2\":true}", MemoryCheckpoint.class)).thenReturn(ckpt2);

        // when
        List<MemoryCheckpoint> result = sut.findByConversationId("conv-1", 10);

        // then
        assertEquals(2, result.size());
        assertEquals("c1", result.get(0).checkpointId());
        assertEquals("c2", result.get(1).checkpointId());

        verify(preparedStatement).setString(1, "conv-1");
        verify(preparedStatement).setInt(2, 10);
    }

    @Test
    void findByConversationId_emptyResult() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when
        List<MemoryCheckpoint> result = sut.findByConversationId("conv-empty", 5);

        // then
        assertTrue(result.isEmpty());
    }

    @Test
    void findByConversationId_sqlException_throwsRuntimeException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(RuntimeException.class,
                () -> sut.findByConversationId("conv", 10));
    }

    @Test
    void findByConversationId_ioException_throwsRuntimeException() throws Exception {
        // given
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("data")).thenReturn("{bad}");
        when(jsonSerialization.deserialize("{bad}", MemoryCheckpoint.class))
                .thenThrow(new IOException("Parse error"));

        // when/then
        assertThrows(RuntimeException.class,
                () -> sut.findByConversationId("conv", 10));
    }

    // ─── findById() ─────────────────────────────────────────────

    @Test
    void findById_found_returnsCheckpoint() throws Exception {
        // given
        var checkpoint = new MemoryCheckpoint(
                "ckpt-1", "conv-1", null, 5, Map.of(), Instant.now(), "t", "C");
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("data")).thenReturn("{\"id\":\"ckpt-1\"}");
        when(jsonSerialization.deserialize("{\"id\":\"ckpt-1\"}", MemoryCheckpoint.class))
                .thenReturn(checkpoint);

        // when
        MemoryCheckpoint result = sut.findById("ckpt-1");

        // then
        assertNotNull(result);
        assertEquals("ckpt-1", result.checkpointId());
        verify(preparedStatement).setString(1, "ckpt-1");
    }

    @Test
    void findById_notFound_returnsNull() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when
        MemoryCheckpoint result = sut.findById("nonexistent");

        // then
        assertNull(result);
    }

    @Test
    void findById_sqlException_throwsRuntimeException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(RuntimeException.class, () -> sut.findById("ckpt-1"));
    }

    @Test
    void findById_ioException_throwsRuntimeException() throws Exception {
        // given
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("data")).thenReturn("{}");
        when(jsonSerialization.deserialize("{}", MemoryCheckpoint.class))
                .thenThrow(new IOException("Deserialize fail"));

        // when/then
        assertThrows(RuntimeException.class, () -> sut.findById("ckpt-1"));
    }

    // ─── deleteById() ───────────────────────────────────────────

    @Test
    void deleteById_executesDelete() throws Exception {
        // when
        sut.deleteById("ckpt-1");

        // then
        verify(preparedStatement).setString(1, "ckpt-1");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void deleteById_sqlException_throwsRuntimeException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(RuntimeException.class, () -> sut.deleteById("ckpt-1"));
    }

    // ─── pruneOldest() ──────────────────────────────────────────

    @Test
    void pruneOldest_deletesOldCheckpoints() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(5);

        // when
        int pruned = sut.pruneOldest("conv-1", 3);

        // then
        assertEquals(5, pruned);
        verify(preparedStatement).setString(1, "conv-1");
        verify(preparedStatement).setString(2, "conv-1");
        verify(preparedStatement).setInt(3, 3);
    }

    @Test
    void pruneOldest_nothingToPrune_returnsZero() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when
        int pruned = sut.pruneOldest("conv-1", 100);

        // then
        assertEquals(0, pruned);
    }

    @Test
    void pruneOldest_sqlException_throwsRuntimeException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(RuntimeException.class, () -> sut.pruneOldest("conv-1", 5));
    }

    // ─── deleteByConversationId() ───────────────────────────────

    @Test
    void deleteByConversationId_returnsCount() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(7);

        // when
        long deleted = sut.deleteByConversationId("conv-1");

        // then
        assertEquals(7, deleted);
        verify(preparedStatement).setString(1, "conv-1");
    }

    @Test
    void deleteByConversationId_noneDeleted() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when
        long deleted = sut.deleteByConversationId("conv-empty");

        // then
        assertEquals(0, deleted);
    }

    @Test
    void deleteByConversationId_sqlException_throwsRuntimeException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(RuntimeException.class,
                () -> sut.deleteByConversationId("conv-1"));
    }

    // ─── ensureSchema ───────────────────────────────────────────

    @Test
    void ensureSchema_sqlException_throwsRuntimeException() throws Exception {
        // given — fresh store with connection failure on schema init
        DataSource failDs = mock(DataSource.class);
        when(failDs.getConnection()).thenThrow(new SQLException("Schema error"));

        @SuppressWarnings("unchecked")
        Instance<DataSource> failInstance = mock(Instance.class);
        when(failInstance.get()).thenReturn(failDs);

        var freshStore = new PostgresConversationCheckpointStore(failInstance, jsonSerialization);

        // when/then
        assertThrows(RuntimeException.class,
                () -> freshStore.deleteById("ckpt"));
    }

    @Test
    void ensureSchema_onlyInitializedOnce() throws Exception {
        // given — perform two operations
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when
        sut.deleteByConversationId("conv-1");
        sut.deleteByConversationId("conv-2");

        // then — createStatement should be called only once (for schema init)
        verify(connection, times(1)).createStatement();
    }
}
