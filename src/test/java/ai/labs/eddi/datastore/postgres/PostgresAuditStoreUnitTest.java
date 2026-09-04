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
import org.mockito.ArgumentCaptor;
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

    /**
     * G18's tamper detection rests on a signed per-conversation sequence. This
     * backend did not persist it and left supportsSequence() at its false default,
     * so AuditLedgerService skipped assigning one and every PostgreSQL deployment
     * silently degraded to HMAC-only — where a DELETED audit row leaves nothing
     * behind to fail verification. MongoDB deployments were protected; these were
     * not, and nothing said so.
     */
    @Test
    void appendEntry_persistsTheSequenceSoTheChainCanBeVerified() throws Exception {
        AuditEntry entry = new AuditEntry("id-1", "conv-1", "agent-1", 1, "user-1", "production",
                0, "task-1", "langchain", 0, 100L, null, null, null, null, null, 0.0, Instant.now(), "hmac", null, 7L);
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.appendEntry(entry);

        verify(preparedStatement).setLong(16, 7L);
    }

    /**
     * {@code eddi.audit.agent-signing-enabled} defaults to true and the Ed25519
     * signature was computed for every entry — but this backend had no column for
     * it and its row-mapper hard-coded null, so the signature was discarded on
     * write and read back absent. The non-repudiation half of the ledger was
     * silently inert on PostgreSQL while MongoDB deployments had it.
     */
    @Test
    void appendEntry_persistsTheAgentSignature() throws Exception {
        AuditEntry entry = new AuditEntry("id-1", "conv-1", "agent-1", 1, "user-1", "production",
                0, "task-1", "langchain", 0, 100L, null, null, null, null, null, 0.0, Instant.now(), "hmac", "ed25519-signature", 7L);
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.appendEntry(entry);

        verify(preparedStatement).setString(17, "ed25519-signature");
    }

    @Test
    void ensureSchema_addsTheAgentSignatureColumnToExistingLedgers() throws Exception {
        AuditEntry entry = createEntry();
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.appendEntry(entry);

        verify(statement).execute("ALTER TABLE audit_ledger ADD COLUMN IF NOT EXISTS agent_signature TEXT");
    }

    /**
     * Findings c1 and 01. {@code agentVersion} is a nullable {@link Integer}
     * precisely because "unknown" is a value the rest of the system produces: the
     * GDPR compliance entries have no conversation and no agent at all, and the
     * HITL and group oversight entries know the conversation but not the agent
     * version. This backend bound it with
     * {@code ps.setInt(4, entry.agentVersion())}, which auto-unboxes — so every one
     * of those entries threw an NPE before the statement was even sent,
     * {@code appendBatch}'s {@code SQLException|IOException} catch did not see it,
     * and the escaping exception took the ledger's whole flush batch down. On
     * PostgreSQL that repeated every time an approval was cancelled, a paused
     * conversation was ended, a group HITL decision was recorded or a facilitator
     * emitted a checkpoint.
     */
    @Test
    void appendEntry_acceptsANullAgentVersion() throws Exception {
        AuditEntry entry = new AuditEntry("id-1", "conv-1", null, null, "user-1", "production",
                0, "task-1", "hitl.approval", 0, 100L, null, null, null, null, null, 0.0, Instant.now(), "hmac", null, 0L);
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertDoesNotThrow(() -> store.appendEntry(entry));
        verify(preparedStatement).setObject(4, null, Types.INTEGER);
    }

    /**
     * The three columns those entries leave empty were {@code NOT NULL}, so even
     * once the binding is fixed an existing ledger would reject the row. Dropping a
     * constraint a column does not have is a no-op in PostgreSQL, so the ALTERs are
     * idempotent and safe on every start.
     */
    @Test
    void ensureSchema_relaxesTheNotNullConstraintsOnExistingLedgers() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.appendEntry(createEntry());

        verify(statement).execute("ALTER TABLE audit_ledger ALTER COLUMN conversation_id DROP NOT NULL");
        verify(statement).execute("ALTER TABLE audit_ledger ALTER COLUMN AGENT_ID DROP NOT NULL");
        verify(statement).execute("ALTER TABLE audit_ledger ALTER COLUMN AGENT_VERSION DROP NOT NULL");
    }

    /**
     * Finding 02: the ledger recovers from a failed bulk write by retrying the
     * batch one entry at a time, and a JDBC batch in autocommit stops at the first
     * failing statement having already committed the ones before it. Without
     * {@code ON CONFLICT (id) DO NOTHING} that retry collides with the rows that
     * did land, so every entry fails again and the whole batch is dead-lettered —
     * exactly the outcome the per-entry fallback exists to avoid.
     */
    @Test
    void insert_isIdempotentSoThePerEntryRetryCanReofferRowsThatLanded() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.appendEntry(createEntry());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("ON CONFLICT (id) DO NOTHING"),
                "the single-entry insert must accept a row the aborted batch already committed: " + sql.getValue());
    }

    /**
     * Finding 09: GDPR export and erasure both select on {@code user_id}, and the
     * ledger is the largest never-pruned table in the system.
     */
    @Test
    void ensureSchema_indexesUserIdForTheGdprScans() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.appendEntry(createEntry());

        verify(statement).execute("CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_ledger (user_id)");
    }

    /**
     * Finding 03: seeding a sequence counter from {@code MAX(sequence)} rather than
     * a row count is what stops a dead-lettered gap turning into a re-issued
     * position, which {@code /auditstore/verify} grades as {@code BROKEN}. SQL
     * {@code MAX} over an empty set is NULL, which {@code getLong} reports as 0 —
     * indistinguishable from a real position 0 without {@code wasNull()}.
     */
    @Test
    void maxSequence_distinguishesAnEmptyChainFromPositionZero() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        when(resultSet.getLong(1)).thenReturn(9L);
        when(resultSet.wasNull()).thenReturn(false);
        assertEquals(9L, store.maxSequence("conv-1"));

        when(resultSet.getLong(1)).thenReturn(0L);
        when(resultSet.wasNull()).thenReturn(true);
        assertEquals(AuditEntry.UNSEQUENCED, store.maxSequence("conv-1"),
                "an empty chain must not look like a chain whose position 0 is taken");
    }

    @Test
    void supportsSequence_isTrueSoTheChainIsActuallyChecked() {
        assertTrue(store.supportsSequence(),
                "returning false here silently disables deletion detection on this backend");
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
        assertEquals(1, entries.get(0).agentVersion(), "the stored agent version must survive the round trip");
    }

    /**
     * The read half of the null-agentVersion fix, and the half nothing asserted.
     * <p>
     * {@code getInt} maps SQL NULL to {@code 0}, so every entry the system
     * legitimately stores without a version — GDPR compliance events, HITL
     * approvals, group oversight, facilitator checkpoints — read back as version 0.
     * The HMAC canonical form renders {@code null} and {@code 0} differently, so
     * {@code /auditstore/verify} would grade every one of those rows as tampered,
     * permanently, and precisely on the EU-AI-Act human-oversight records the
     * ledger exists to hold.
     */
    @Test
    void getEntries_mapsASqlNullAgentVersionToNullNotZero() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        mockAuditResultSet();
        // A row written by one of the six call sites that have no agent version.
        when(resultSet.getObject("AGENT_VERSION", Integer.class)).thenReturn(null);

        List<AuditEntry> entries = store.getEntries("conv-1", 0, 10);

        assertEquals(1, entries.size());
        assertNull(entries.get(0).agentVersion(),
                "a stored SQL NULL must read back as null; 0 is a different HMAC input and verifies as tampered");
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
        assertEquals(1, entries.get(0).agentVersion(), "the stored agent version must survive the round trip");
    }

    @Test
    void getEntriesByAgent_withoutVersion() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        mockAuditResultSet();

        List<AuditEntry> entries = store.getEntriesByAgent("agent-1", null, 0, 10);
        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).agentVersion(), "the stored agent version must survive the round trip");
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
        assertEquals(1, entries.get(0).agentVersion(), "the stored agent version must survive the round trip");
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
        // getObject, not getInt: fromRow reads the nullable Integer through
        // getObject("AGENT_VERSION", Integer.class). The getInt stub this fixture
        // used to carry was dead — Mockito is not in strict-stubs mode here, so it
        // was silent — and every test built on the fixture quietly round-tripped
        // agentVersion = null while claiming to exercise version 1.
        when(resultSet.getObject("AGENT_VERSION", Integer.class)).thenReturn(1);
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
