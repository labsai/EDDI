/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.JournalEntry;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.Status;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for {@link PostgresHitlToolJournalStore} with a fully mocked
 * JDBC stack (no Testcontainers). Verifies the at-most-once claim contract
 * (insert vs duplicate vs transient error), that decided_by is persisted at
 * claim time, and the read/delete round-trip.
 */
class PostgresHitlToolJournalStoreUnitTest {

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

    private PostgresHitlToolJournalStore store;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        store = new PostgresHitlToolJournalStore(dataSourceInstance, Duration.ofDays(30));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void tryClaim_inserted_returnsTrue_andPersistsDecidedBy() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        boolean claimed = store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1");

        assertTrue(claimed);
        // decided_by is the 7th INSERT parameter — must be persisted (parity with
        // Mongo).
        verify(preparedStatement).setString(7, "user-1");
    }

    @Test
    void tryClaim_conflict_returnsFalse() throws Exception {
        // ON CONFLICT DO NOTHING → 0 rows affected → already claimed.
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertFalse(store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1"));
    }

    @Test
    void tryClaim_sqlException_propagatesAsRuntime_notFalse() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("connection reset"));

        // A transient failure must NOT be conflated with "already claimed" (false) —
        // it propagates so the caller can retry instead of skipping an approved tool.
        assertThrows(RuntimeException.class,
                () -> store.tryClaim("conv-1", "epoch-1", "call-1", "toolA", "user-1"));
    }

    @Test
    void markExecuted_updates_ok() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.markExecuted("conv-1", "epoch-1", "call-1", "the result");

        // executeUpdate runs for the startup cleanup (via ensureSchema) AND the UPDATE.
        verify(preparedStatement, atLeastOnce()).executeUpdate();
    }

    @Test
    void markExecuted_noClaimedEntry_doesNotThrow() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertDoesNotThrow(() -> store.markExecuted("conv-1", "epoch-1", "call-1", "res"));
    }

    @Test
    void markExecuted_sqlException_throwsRuntime() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0).thenThrow(new SQLException("boom"));
        // First executeUpdate() (schema cleanup) returns 0; the markExecuted UPDATE
        // throws.
        assertThrows(RuntimeException.class, () -> store.markExecuted("conv-1", "epoch-1", "call-1", "res"));
    }

    @Test
    void find_present_mapsAllFields() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("status")).thenReturn(Status.EXECUTED.name());
        when(resultSet.getLong("executed_at")).thenReturn(1_700_000_000_000L);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getString("conversation_id")).thenReturn("conv-1");
        when(resultSet.getString("pause_epoch")).thenReturn("epoch-1");
        when(resultSet.getString("call_id")).thenReturn("call-1");
        when(resultSet.getString("tool_name")).thenReturn("toolA");
        when(resultSet.getString("result_capped")).thenReturn("the result");
        when(resultSet.getString("decided_by")).thenReturn("user-1");

        Optional<JournalEntry> entry = store.find("conv-1", "epoch-1", "call-1");

        assertTrue(entry.isPresent());
        assertEquals(Status.EXECUTED, entry.get().status());
        assertEquals("user-1", entry.get().decidedBy());
        assertNotNull(entry.get().executedAt());
    }

    @Test
    void find_executingEntry_nullExecutedAt() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("status")).thenReturn(Status.EXECUTING.name());
        when(resultSet.getLong("executed_at")).thenReturn(0L);
        when(resultSet.wasNull()).thenReturn(true);

        Optional<JournalEntry> entry = store.find("conv-1", "epoch-1", "call-1");

        assertTrue(entry.isPresent());
        assertEquals(Status.EXECUTING, entry.get().status());
        assertNull(entry.get().executedAt());
    }

    @Test
    void find_absent_returnsEmpty() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertTrue(store.find("conv-1", "epoch-1", "call-1").isEmpty());
    }

    @Test
    void find_sqlException_throwsRuntime() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("boom"));

        assertThrows(RuntimeException.class, () -> store.find("conv-1", "epoch-1", "call-1"));
    }

    @Test
    void deleteByConversationId_returnsDeletedCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(3);

        assertEquals(3, store.deleteByConversationId("conv-1"));
    }

    @Test
    void deleteByConversationId_sqlException_throwsRuntime() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0).thenThrow(new SQLException("boom"));
        assertThrows(RuntimeException.class, () -> store.deleteByConversationId("conv-1"));
    }

    @Test
    void schemaInitializedOnce_thenReused() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.tryClaim("c", "e", "1", "t", "u");
        store.tryClaim("c", "e", "2", "t", "u");

        // CREATE TABLE runs exactly once (schemaInitialized guard), not per call.
        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(statement, times(1)).execute(ddl.capture());
        assertTrue(ddl.getValue().contains("hitl_tool_execution_journal"));
    }

    @Test
    void retention_zeroOrNegative_fallsBackToDefault() throws Exception {
        // A pathological retention must not throw at construction; the 30d fallback is
        // used.
        assertDoesNotThrow(() -> new PostgresHitlToolJournalStore(dataSourceInstance, Duration.ZERO));
        assertDoesNotThrow(() -> new PostgresHitlToolJournalStore(dataSourceInstance, Duration.ofDays(-5)));
    }

    @Test
    void schemaInitFailure_isSwallowed_notFatalToConstruction() throws Exception {
        when(statement.execute(anyString())).thenThrow(new SQLException("no db"));
        // ensureSchema logs + returns without throwing; the store stays usable for
        // retry.
        assertDoesNotThrow(() -> store.deleteByConversationId("conv-1"));
    }
}
