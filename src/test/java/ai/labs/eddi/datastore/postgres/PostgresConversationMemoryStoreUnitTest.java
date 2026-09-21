/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
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
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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

    /**
     * G12 parity with the MongoDB store: an UPDATE that matches no row means either
     * the conversation was deleted mid-turn or another writer moved it to a newer
     * revision. Before the fix the count was discarded and the conversation id was
     * returned as if the turn had been persisted, so
     * {@code ConversationService.onComplete} never reached
     * {@code logConversationError}. The existence probe stubbed here is what tells
     * the two causes apart.
     */
    @Test
    void storeSnapshot_conversationDeletedMidTurn_throwsResourceStoreException() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot("conv-123");
        when(jsonSerialization.serialize(snapshot)).thenReturn("{\"test\":true}");
        when(preparedStatement.executeUpdate()).thenReturn(0);
        ProbeResources probe = stubConversationExists(false);

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.storeConversationMemorySnapshot(snapshot));

        assertTrue(thrown.getMessage().contains("conv-123"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("NOT persisted"), thrown.getMessage());
        assertFalse(thrown instanceof ConcurrentConversationModificationException,
                "an erased conversation has nothing to retry against — it must not be reported as a revision conflict");
        verify(probe.probeResult()).close();
        verify(probe.probeStatement()).close();
    }

    /**
     * Postgres parity for the optimistic-concurrency guard: the row is still there,
     * only at a different revision, so this is a conflict a retry from a fresh load
     * can still resolve — not a deletion.
     */
    @Test
    void storeSnapshot_concurrentWriter_throwsConcurrentModification() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot("conv-123");
        snapshot.setRevision(4L);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{\"test\":true}");
        when(preparedStatement.executeUpdate()).thenReturn(0);
        ProbeResources probe = stubConversationExists(true);

        var thrown = assertThrows(ConcurrentConversationModificationException.class,
                () -> store.storeConversationMemorySnapshot(snapshot));

        assertEquals("conv-123", thrown.getConversationId());
        assertEquals(4L, thrown.getExpectedRevision());
        assertEquals(4L, snapshot.getRevision(),
                "a refused write must leave the snapshot on the revision it was derived from");
        // The guard has to reach the SQL, not just the exception mapping.
        verify(preparedStatement).setLong(6, 4L);
        verify(probe.probeResult()).close();
        verify(probe.probeStatement()).close();
    }

    /**
     * The two mocks behind the existence probe, returned together so a test can
     * assert the store closed <em>both</em>.
     * <p>
     * Returning only the {@code ResultSet} was the gap: the helper's comment
     * claimed the tests verified both closes, and no test could, because the
     * statement never left this method. Both matter — {@code ResultSet.close()} is
     * not specified to close the statement that produced it, so a leaked
     * {@code PreparedStatement} per refused write holds a server-side portal open
     * until the connection is returned.
     */
    private record ProbeResources(PreparedStatement probeStatement, ResultSet probeResult) {
    }

    /**
     * Stubs the existence probe the store runs after a zero-row write to tell "the
     * row is gone" apart from "the row moved to another revision".
     * <p>
     * The probe gets its own statement and result set rather than reusing the
     * shared update mocks, so a test can verify the store closes both — the probe
     * runs in try-with-resources, and a leak per refused write would be a real one.
     */
    private ProbeResources stubConversationExists(boolean exists) throws SQLException {
        PreparedStatement probeStatement = mock(PreparedStatement.class);
        ResultSet probeResult = mock(ResultSet.class);
        when(connection.prepareStatement(startsWith("SELECT 1 FROM conversation_memories"))).thenReturn(probeStatement);
        when(probeStatement.executeQuery()).thenReturn(probeResult);
        when(probeResult.next()).thenReturn(exists);
        return new ProbeResources(probeStatement, probeResult);
    }

    /**
     * The append path exists so a long conversation is not re-serialized and
     * re-shipped on every turn. A full-document {@code serialize(snapshot)} at the
     * top of {@code storeConversationMemorySnapshot} put that cost straight back:
     * its result was dead on all three branches — the append path returns before
     * using it, the full-replace path overwrites it after stamping the new
     * revision, and the insert path serializes separately once the id exists.
     * <p>
     * One call, not none: {@code appendConversationSteps} legitimately serializes
     * the same instance once, for the body, with the step and output arrays
     * temporarily emptied so the stored history is neither re-serialized nor sent.
     */
    @Test
    void storeSnapshot_pureAppend_serializesTheSnapshotOnceForTheBody() throws Exception {
        ConversationMemorySnapshot snapshot = createSnapshot("conv-123");
        snapshot.setConversationSteps(new LinkedList<>(List.of(new ConversationMemorySnapshot.ConversationStepSnapshot())));
        snapshot.setConversationOutputs(new LinkedList<>(List.of(new ConversationOutput())));
        snapshot.setPersistedStepCount(0);
        when(jsonSerialization.serialize(any())).thenReturn("[]");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.storeConversationMemorySnapshot(snapshot);

        verify(jsonSerialization, times(1)).serialize(snapshot);
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
