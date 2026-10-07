/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Postgres store's quotas: lock, count and insert in one transaction, so
 * concurrent uploads cannot both pass the check.
 */
class PostgresAttachmentQuotaTest {

    private Connection connection;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private final List<String> sql = new ArrayList<>();
    private PostgresAttachmentStore sut;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            sql.add(invocation.getArgument(0));
            return preparedStatement;
        });
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        sut = new PostgresAttachmentStore(instance);
        set("maxSizeBytes", 1_000_000L);
    }

    private void set(String field, long value) throws Exception {
        Field f = PostgresAttachmentStore.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setLong(sut, value);
    }

    /** Every usage query answers {count, bytes}. */
    private void givenUsage(long count, long bytes) throws Exception {
        when(resultSet.getLong(1)).thenReturn(count);
        when(resultSet.getLong(2)).thenReturn(bytes);
    }

    @Test
    void withinQuota_locksCountsAndInsertsInOneTransaction() throws Exception {
        set("maxPerConversation", 5L);
        givenUsage(1, 10);

        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, "u1");

        assertTrue(sql.get(0).contains("pg_advisory_xact_lock"), sql.toString());
        assertTrue(sql.get(1).contains("WHERE conversation_id = ?"), sql.toString());
        assertTrue(sql.get(2).startsWith("INSERT INTO attachments"), sql.toString());
        InOrder order = inOrder(connection);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true);
    }

    @Test
    void overQuota_rollsBack_andInsertsNothing() throws Exception {
        set("maxPerConversation", 1L);
        givenUsage(1, 10);

        var refused = assertThrows(AttachmentQuotaExceededException.class,
                () -> sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, null));

        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, refused.getScope());
        verify(connection).rollback();
        verify(connection, never()).commit();
        assertTrue(sql.stream().noneMatch(statement -> statement.startsWith("INSERT")), sql.toString());
    }

    @Test
    void userQuota_locksTheUserAfterTheConversation() throws Exception {
        set("maxPerConversation", 50L);
        set("maxTotalBytesPerUser", 100L);
        givenUsage(2, 99);

        var refused = assertThrows(AttachmentQuotaExceededException.class,
                () -> sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, "u1"));

        assertEquals(AttachmentQuotaExceededException.SCOPE_USER, refused.getScope());
        assertTrue(sql.get(0).contains("pg_advisory_xact_lock") && sql.get(1).contains("pg_advisory_xact_lock"), sql.toString());
        InOrder order = inOrder(preparedStatement);
        order.verify(preparedStatement).setString(1, "eddi-attachments-quota:conversation:conv-1");
        order.verify(preparedStatement).setString(1, "eddi-attachments-quota:user:u1");
        assertTrue(sql.stream().anyMatch(statement -> statement.contains("WHERE user_id = ?")), sql.toString());
    }

    @Test
    void noQuota_insertsWithoutATransactionOrLock() throws Exception {
        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, "u1");

        assertTrue(sql.stream().noneMatch(statement -> statement.contains("pg_advisory")), sql.toString());
        verify(connection, never()).setAutoCommit(false);
        verify(preparedStatement).setString(4, "u1");
    }
}
