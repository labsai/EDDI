/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.hitl;

import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore.SlackApprovalRecord;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The PostgreSQL half of the Slack approval-record store, pinned at the JDBC
 * boundary: the card id is written on insert AND on the replace of an expired
 * row, read back by {@code findBySubject}, and added to a table created before
 * card binding existed. {@link MongoSlackApprovalRecordStoreTest} covers the
 * same contract on a real MongoDB.
 */
class PostgresSlackApprovalRecordStoreUnitTest {

    private Connection connection;
    private Statement ddl;
    private PreparedStatement statement;
    private PostgresSlackApprovalRecordStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        ddl = mock(Statement.class);
        statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(ddl);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);
        store = new PostgresSlackApprovalRecordStore(instance, Duration.ofDays(30));
    }

    @Test
    void tryRecord_writesTheCardId_onInsertAndOnReplace() throws Exception {
        when(statement.executeUpdate()).thenReturn(1);

        assertTrue(store.tryRecord("acme-int", "conv-1", "1000", "card-a", "C_APPROVAL"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        String insert = sql.getAllValues().stream().filter(s -> s.contains("INSERT")).findFirst().orElseThrow();
        assertTrue(insert.contains("card_id, approval_channel_id"), insert);
        assertTrue(insert.contains("SET card_id = EXCLUDED.card_id"),
                "replacing an expired row must take the new card's id: " + insert);
        verify(statement).setString(4, "card-a");
        verify(statement).setString(5, "C_APPROVAL");
    }

    @Test
    void tryRecord_liveRowExists_returnsFalse() throws Exception {
        when(statement.executeUpdate()).thenReturn(0);
        assertFalse(store.tryRecord("acme-int", "conv-1", "1000", "card-a", "C_APPROVAL"));
    }

    @Test
    void findBySubject_readsTheCardId() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("integration_name")).thenReturn("acme-int");
        when(rows.getString("subject")).thenReturn("conv-1");
        when(rows.getString("pause_epoch")).thenReturn("1000");
        when(rows.getString("card_id")).thenReturn("card-a");
        when(rows.getString("approval_channel_id")).thenReturn("C_APPROVAL");
        Instant now = Instant.now();
        when(rows.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(rows.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(60)));

        List<SlackApprovalRecord> records = store.findBySubject("acme-int", "conv-1");

        assertEquals(1, records.size());
        assertEquals("card-a", records.get(0).cardId());
        assertTrue(records.get(0).matchesCard("card-a"));
    }

    @Test
    void schema_addsTheCardIdColumnToAnExistingTable() throws Exception {
        when(statement.executeUpdate()).thenReturn(1);
        store.tryRecord("acme-int", "conv-1", "1000", "card-a", "C_APPROVAL");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(ddl, atLeastOnce()).execute(sql.capture());
        assertTrue(sql.getAllValues().stream()
                .anyMatch(s -> s.contains("ADD COLUMN IF NOT EXISTS card_id")),
                "a table created before card binding must gain the card_id column");
    }
}
