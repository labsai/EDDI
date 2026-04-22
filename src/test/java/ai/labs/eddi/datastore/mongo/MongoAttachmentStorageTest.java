package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.memory.IAttachmentStorage.AttachmentNotFoundException;
import ai.labs.eddi.engine.memory.mongo.MongoAttachmentStorage;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoAttachmentStorage} (GridFS) using
 * Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoAttachmentStorage IT")
class MongoAttachmentStorageTest extends MongoTestBase {

    private static MongoAttachmentStorage storage;

    @BeforeAll
    static void init() {
        storage = new MongoAttachmentStorage(getDatabase());
    }

    @BeforeEach
    void clean() {
        // Drop GridFS collections
        dropCollections("eddi_attachments.files", "eddi_attachments.chunks");
    }

    @Test
    @DisplayName("store + load — binary round-trip")
    void storeAndLoad() throws AttachmentNotFoundException, IOException {
        byte[] content = "Hello GridFS!".getBytes(StandardCharsets.UTF_8);
        String ref = storage.store("conv-1", "test.txt", "text/plain",
                new ByteArrayInputStream(content), content.length);

        assertNotNull(ref);
        assertTrue(ref.startsWith("gridfs://"));

        try (InputStream loaded = storage.load(ref)) {
            assertArrayEquals(content, loaded.readAllBytes());
        }
    }

    @Test
    @DisplayName("store with null fileName — uses 'unnamed'")
    void storeNullFilename() throws AttachmentNotFoundException, IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        String ref = storage.store("conv-1", null, "application/octet-stream",
                new ByteArrayInputStream(content), content.length);

        try (InputStream loaded = storage.load(ref)) {
            assertArrayEquals(content, loaded.readAllBytes());
        }
    }

    @Test
    @DisplayName("load non-existent — throws AttachmentNotFoundException")
    void loadNonExistent() {
        assertThrows(AttachmentNotFoundException.class,
                () -> storage.load("gridfs://aaaaaaaaaaaaaaaaaaaaaaaa"));
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
    @DisplayName("deleteByConversation — removes all for conversation")
    void deleteByConversation() throws AttachmentNotFoundException {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        String ref1 = storage.store("conv-del", "a.txt", "text/plain",
                new ByteArrayInputStream(data), data.length);
        String ref2 = storage.store("conv-del", "b.txt", "text/plain",
                new ByteArrayInputStream(data), data.length);
        storage.store("conv-keep", "c.txt", "text/plain",
                new ByteArrayInputStream(data), data.length);

        long deleted = storage.deleteByConversation("conv-del");
        assertEquals(2, deleted);

        assertThrows(AttachmentNotFoundException.class, () -> storage.load(ref1));
        assertThrows(AttachmentNotFoundException.class, () -> storage.load(ref2));
    }

    @Test
    @DisplayName("deleteByConversation non-existent — returns 0")
    void deleteNonExistent() {
        assertEquals(0, storage.deleteByConversation("no-such-conv"));
    }
}
