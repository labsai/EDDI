/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.memory.IAttachmentStorage;
import ai.labs.eddi.engine.memory.IAttachmentStorage.AttachmentNotFoundException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostgresAttachmentStorageUnitTest {

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

    private PostgresAttachmentStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(dataSourceInstance.isResolvable()).thenReturn(true);

        storage = new PostgresAttachmentStorage(dataSourceInstance);
    }

    // ─── createTable (@PostConstruct) ───

    @Test
    void createTable_whenDataSourceResolvable_createsTable() throws Exception {
        storage.createTable();

        verify(statement, times(2)).execute(anyString());
    }

    @Test
    void createTable_whenDataSourceNotResolvable_skips() throws Exception {
        when(dataSourceInstance.isResolvable()).thenReturn(false);

        storage.createTable();

        verify(connection, never()).createStatement();
    }

    @Test
    void createTable_sqlException_logsWarning() throws Exception {
        when(statement.execute(anyString())).thenThrow(new SQLException("error"));

        assertDoesNotThrow(() -> storage.createTable());
    }

    // ─── store ───

    @Test
    void store_withPositiveSize() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        InputStream data = new ByteArrayInputStream("test data".getBytes());

        String ref = storage.store("conv-1", "test.txt", "text/plain", data, 9);

        assertNotNull(ref);
        assertTrue(ref.startsWith("pg://"));
        verify(preparedStatement).setBinaryStream(eq(6), eq(data), eq(9L));
    }

    @Test
    void store_withZeroSize() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        InputStream data = new ByteArrayInputStream("data".getBytes());

        String ref = storage.store("conv-1", "test.txt", "text/plain", data, 0);

        assertNotNull(ref);
        assertTrue(ref.startsWith("pg://"));
        verify(preparedStatement).setBinaryStream(eq(6), eq(data));
    }

    @Test
    void store_withNegativeSize() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        InputStream data = new ByteArrayInputStream("data".getBytes());

        String ref = storage.store("conv-1", "test.txt", "text/plain", data, -1);

        assertNotNull(ref);
        verify(preparedStatement).setLong(5, 0L); // Math.max(-1, 0) = 0
        verify(preparedStatement).setBinaryStream(eq(6), eq(data));
    }

    @Test
    void store_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));
        InputStream data = new ByteArrayInputStream("data".getBytes());

        assertThrows(RuntimeException.class,
                () -> storage.store("conv-1", "test.txt", "text/plain", data, 4));
    }

    // ─── load ───

    @Test
    void load_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBytes("data")).thenReturn("test content".getBytes());

        String validUuid = "pg://" + java.util.UUID.randomUUID();
        InputStream result = storage.load(validUuid);

        assertNotNull(result);
        assertTrue(result.available() > 0);
    }

    @Test
    void load_notFound_throwsAttachmentNotFoundException() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        String validUuid = "pg://" + java.util.UUID.randomUUID();
        assertThrows(AttachmentNotFoundException.class, () -> storage.load(validUuid));
    }

    @Test
    void load_invalidRef_nullRef_throwsAttachmentNotFoundException() {
        assertThrows(AttachmentNotFoundException.class, () -> storage.load(null));
    }

    @Test
    void load_invalidRef_wrongPrefix_throwsAttachmentNotFoundException() {
        assertThrows(AttachmentNotFoundException.class, () -> storage.load("mongo://abc"));
    }

    @Test
    void load_invalidRef_badUuid_throwsAttachmentNotFoundException() {
        assertThrows(AttachmentNotFoundException.class, () -> storage.load("pg://not-a-uuid"));
    }

    @Test
    void load_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        String validUuid = "pg://" + java.util.UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> storage.load(validUuid));
    }

    // ─── deleteByConversation ───

    @Test
    void deleteByConversation_deletesAndReturnsCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(3);

        assertEquals(3, storage.deleteByConversation("conv-1"));
        verify(preparedStatement).setString(1, "conv-1");
    }

    @Test
    void deleteByConversation_noneDeleted() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertEquals(0, storage.deleteByConversation("conv-empty"));
    }

    @Test
    void deleteByConversation_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        assertThrows(RuntimeException.class, () -> storage.deleteByConversation("conv-1"));
    }
}
