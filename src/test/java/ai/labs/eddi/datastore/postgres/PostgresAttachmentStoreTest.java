/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresAttachmentStore} with mocked JDBC connections.
 */
class PostgresAttachmentStoreTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    @SuppressWarnings("unchecked")
    private Instance<DataSource> dataSourceInstance;
    private PostgresAttachmentStore sut;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        dataSourceInstance = mock(Instance.class);

        when(dataSourceInstance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        sut = new PostgresAttachmentStore(dataSourceInstance);

        // Set maxSizeBytes via reflection since @ConfigProperty is not CDI-injected
        Field maxSizeField = PostgresAttachmentStore.class.getDeclaredField("maxSizeBytes");
        maxSizeField.setAccessible(true);
        maxSizeField.set(sut, 20_971_520L); // 20 MB
    }

    // ─── store() ────────────────────────────────────────────────

    @Test
    void store_validData_returnsAttachment() throws Exception {
        // given — plain text bytes (no magic-byte signature →
        // "application/octet-stream")
        byte[] data = "Hello, World!".getBytes();

        // when
        Attachment result = sut.store(data, "application/octet-stream", "test.txt", "conv-1", "tenant-1");

        // then
        assertNotNull(result);
        assertNotNull(result.storageRef());
        assertEquals("test.txt", result.filename());
        assertEquals("application/octet-stream", result.mimeType());
        assertEquals(data.length, result.sizeBytes());
        assertEquals("conv-1", result.conversationId());

        verify(preparedStatement).executeUpdate();
    }

    @Test
    void store_nullBytes_throwsException() {
        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(null, "text/plain", "f.txt", "conv", "t"));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void store_emptyBytes_throwsException() {
        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(new byte[0], "text/plain", "f.txt", "conv", "t"));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void store_exceedsMaxSize_throwsException() throws Exception {
        // given — set small max
        Field maxField = PostgresAttachmentStore.class.getDeclaredField("maxSizeBytes");
        maxField.setAccessible(true);
        maxField.set(sut, 10L);

        byte[] data = new byte[11];

        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(data, "application/octet-stream", "big.bin", "conv", "t"));
        assertTrue(ex.getMessage().contains("exceeds max size"));
    }

    @Test
    void store_mimeMismatch_throwsException() {
        // given — PNG magic bytes
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        // when — declare it as JPEG
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(pngBytes, "image/jpeg", "fake.jpg", "conv", "t"));
        assertTrue(ex.getMessage().contains("MIME type mismatch"));
    }

    @Test
    void store_sqlException_throwsAttachmentStoreException() throws Exception {
        // given
        byte[] data = "content".getBytes();
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(data, "application/octet-stream", "f.txt", "conv", "t"));
        assertTrue(ex.getMessage().contains("Failed to store attachment"));
    }

    @Test
    void store_nullDeclaredMime_usesDetectedMime() throws Exception {
        // given — plain bytes, no declared MIME
        byte[] data = "some data".getBytes();

        // when
        Attachment result = sut.store(data, null, "file.bin", "conv-1", "t");

        // then — should not throw, MIME detection is lenient when declared is null
        assertNotNull(result);
        assertEquals("application/octet-stream", result.mimeType());
    }

    // ─── load() ─────────────────────────────────────────────────

    @Test
    void load_validRef_returnsBytes() throws Exception {
        // given
        byte[] expected = "file content".getBytes();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("conversation_id")).thenReturn("conv-1");
        when(resultSet.getBytes("data")).thenReturn(expected);

        // when
        byte[] result = sut.load("some-ref", "conv-1");

        // then
        assertArrayEquals(expected, result);
    }

    @Test
    void load_notFound_throwsException() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.load("missing-ref", "conv-1"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void load_crossConversation_throwsException() throws Exception {
        // given
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("conversation_id")).thenReturn("conv-owner");
        when(resultSet.getBytes("data")).thenReturn("data".getBytes());

        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.load("ref-1", "conv-other"));
        assertTrue(ex.getMessage().contains("Cross-conversation access denied"));
    }

    @Test
    void load_sqlException_throwsAttachmentStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.load("ref", "conv"));
        assertTrue(ex.getMessage().contains("Failed to load attachment"));
    }

    // ─── deleteByConversation() ─────────────────────────────────

    @Test
    void deleteByConversation_returnsCount() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(3);

        // when
        long deleted = sut.deleteByConversation("conv-1");

        // then
        assertEquals(3, deleted);
        verify(preparedStatement).setString(1, "conv-1");
    }

    @Test
    void deleteByConversation_noneDeleted_returnsZero() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when
        long deleted = sut.deleteByConversation("conv-empty");

        // then
        assertEquals(0, deleted);
    }

    @Test
    void deleteByConversation_sqlException_throwsRuntimeException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(RuntimeException.class,
                () -> sut.deleteByConversation("conv"));
    }

    // ─── listByConversation() ───────────────────────────────────

    @Test
    void listByConversation_multipleResults() throws Exception {
        // given
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("storage_ref")).thenReturn("ref-1", "ref-2");
        when(resultSet.getString("filename")).thenReturn("file1.txt", "file2.txt");
        when(resultSet.getString("mime_type")).thenReturn("text/plain", "image/png");
        when(resultSet.getLong("size_bytes")).thenReturn(100L, 200L);
        when(resultSet.getString("conversation_id")).thenReturn("conv-1", "conv-1");

        // when
        List<Attachment> results = sut.listByConversation("conv-1");

        // then
        assertEquals(2, results.size());
        assertEquals("ref-1", results.get(0).storageRef());
        assertEquals("file1.txt", results.get(0).filename());
        assertEquals("text/plain", results.get(0).mimeType());
        assertEquals(100L, results.get(0).sizeBytes());
        assertEquals("ref-2", results.get(1).storageRef());
    }

    @Test
    void listByConversation_emptyResults() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when
        List<Attachment> results = sut.listByConversation("conv-empty");

        // then
        assertTrue(results.isEmpty());
    }

    @Test
    void listByConversation_sqlException_throwsRuntimeException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(RuntimeException.class,
                () -> sut.listByConversation("conv"));
    }

    // ─── ensureSchema ───────────────────────────────────────────

    @Test
    void ensureSchema_sqlException_throwsRuntimeException() throws Exception {
        // given — fresh store where schema not initialized yet
        when(dataSource.getConnection()).thenThrow(new SQLException("Schema error"));

        @SuppressWarnings("unchecked")
        Instance<DataSource> freshInstance = mock(Instance.class);
        when(freshInstance.get()).thenReturn(dataSource);

        PostgresAttachmentStore freshStore = new PostgresAttachmentStore(freshInstance);
        Field maxField = PostgresAttachmentStore.class.getDeclaredField("maxSizeBytes");
        maxField.setAccessible(true);
        maxField.set(freshStore, 20_971_520L);

        // when/then — ensureSchema will be called on first operation
        assertThrows(RuntimeException.class,
                () -> freshStore.deleteByConversation("conv"));
    }

    @Test
    void ensureSchema_calledOnceAcrossMultipleOps() throws Exception {
        // given — perform two operations
        when(resultSet.next()).thenReturn(false);

        // when
        sut.listByConversation("conv-1");
        sut.listByConversation("conv-2");

        // then — createStatement (schema init) should only be called once
        verify(connection, times(1)).createStatement();
    }
}
