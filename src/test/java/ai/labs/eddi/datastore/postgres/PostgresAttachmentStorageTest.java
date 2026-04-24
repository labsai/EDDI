/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.memory.IAttachmentStorage.AttachmentNotFoundException;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresAttachmentStorage} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresAttachmentStorage IT")
class PostgresAttachmentStorageTest extends PostgresTestBase {

    private static PostgresAttachmentStorage storage;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        storage = new PostgresAttachmentStorage(dsInstance);
        // Manually trigger schema creation (normally @PostConstruct)
        storage.createTable();
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "attachments");
        } catch (SQLException ignored) {
        }
    }

    @Test
    @DisplayName("store + load — binary round-trip")
    void storeAndLoad() throws AttachmentNotFoundException, IOException {
        byte[] content = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        var input = new ByteArrayInputStream(content);

        String ref = storage.store("conv-1", "test.txt", "text/plain", input, content.length);
        assertNotNull(ref);
        assertTrue(ref.startsWith("pg://"));

        try (InputStream loaded = storage.load(ref)) {
            assertArrayEquals(content, loaded.readAllBytes());
        }
    }

    @Test
    @DisplayName("store with zero sizeBytes — still persists")
    void storeZeroSize() throws AttachmentNotFoundException, IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        String ref = storage.store("conv-2", "zero.bin", "application/octet-stream",
                new ByteArrayInputStream(content), 0);

        try (InputStream loaded = storage.load(ref)) {
            assertArrayEquals(content, loaded.readAllBytes());
        }
    }

    @Test
    @DisplayName("load non-existent — throws AttachmentNotFoundException")
    void loadNonExistent() {
        assertThrows(AttachmentNotFoundException.class,
                () -> storage.load("pg://00000000-0000-0000-0000-000000000000"));
    }

    @Test
    @DisplayName("load invalid ref — throws AttachmentNotFoundException")
    void loadInvalidRef() {
        assertThrows(AttachmentNotFoundException.class,
                () -> storage.load("invalid-ref"));
    }

    @Test
    @DisplayName("load null ref — throws AttachmentNotFoundException")
    void loadNullRef() {
        assertThrows(AttachmentNotFoundException.class,
                () -> storage.load(null));
    }

    @Test
    @DisplayName("deleteByConversation — removes all attachments for conversation")
    void deleteByConversation() throws AttachmentNotFoundException, IOException {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        String ref1 = storage.store("conv-del", "a.txt", "text/plain",
                new ByteArrayInputStream(data), data.length);
        String ref2 = storage.store("conv-del", "b.txt", "text/plain",
                new ByteArrayInputStream(data), data.length);
        storage.store("conv-keep", "c.txt", "text/plain",
                new ByteArrayInputStream(data), data.length);

        long deleted = storage.deleteByConversation("conv-del");
        assertEquals(2, deleted);

        // Deleted attachments should not be loadable
        assertThrows(AttachmentNotFoundException.class, () -> storage.load(ref1));
        assertThrows(AttachmentNotFoundException.class, () -> storage.load(ref2));
    }

    @Test
    @DisplayName("deleteByConversation non-existent — returns 0")
    void deleteNonExistent() {
        assertEquals(0, storage.deleteByConversation("no-such-conv"));
    }
}
