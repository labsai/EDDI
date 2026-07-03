/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GridFsAttachmentStore} with mocked GridFSBucket.
 */
class GridFsAttachmentStoreTest {

    private GridFSBucket gridFSBucket;
    private MongoCollection<Document> filesCollection;
    private GridFsAttachmentStore sut;

    @BeforeEach
    void setUp() throws Exception {
        gridFSBucket = mock(GridFSBucket.class);
        filesCollection = mock(MongoCollection.class);
        sut = createWithMocks(gridFSBucket, filesCollection);

        setLong("maxSizeBytes", 20_971_520L);
        // Unlimited quotas by default so store() skips the usage query.
        setLong("maxPerConversation", -1L);
        setLong("maxTotalBytesPerConversation", -1L);
    }

    private void setLong(String field, long value) throws Exception {
        Field f = GridFsAttachmentStore.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setLong(sut, value);
    }

    private static GridFsAttachmentStore createWithMocks(GridFSBucket bucket, MongoCollection<Document> files)
            throws Exception {
        var objenesis = new org.objenesis.ObjenesisStd();
        GridFsAttachmentStore store = objenesis.newInstance(GridFsAttachmentStore.class);
        Field bucketField = GridFsAttachmentStore.class.getDeclaredField("gridFSBucket");
        bucketField.setAccessible(true);
        bucketField.set(store, bucket);
        Field filesField = GridFsAttachmentStore.class.getDeclaredField("filesCollection");
        filesField.setAccessible(true);
        filesField.set(store, files);
        return store;
    }

    /** Mock a single GridFSFile with the given owner / grants metadata. */
    private static GridFSFile mockFile(ObjectId id, String owner, String mime, List<String> grants, String ref) {
        GridFSFile f = mock(GridFSFile.class);
        when(f.getObjectId()).thenReturn(id);
        when(f.getFilename()).thenReturn("file.bin");
        when(f.getLength()).thenReturn(123L);
        Document md = new Document();
        if (owner != null)
            md.append("conversationId", owner);
        if (mime != null)
            md.append("mimeType", mime);
        if (ref != null)
            md.append("storageRef", ref);
        if (grants != null)
            md.append("grants", grants);
        when(f.getMetadata()).thenReturn(md);
        return f;
    }

    /**
     * Make gridFSBucket.find(any) resolve to a single-first() iterable returning
     * {@code file}.
     */
    private void whenFindFirst(GridFSFile file) {
        GridFSFindIterable it = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(file);
    }

    /**
     * Make gridFSBucket.find(any) iterate over the given files (for
     * delete/list/quota).
     */
    private void whenFindIterate(GridFSFile... files) {
        GridFSFindIterable it = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(it);
        @SuppressWarnings("unchecked")
        MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(it).iterator();
        Boolean[] hasNext = new Boolean[files.length + 1];
        for (int i = 0; i < files.length; i++)
            hasNext[i] = true;
        hasNext[files.length] = false;
        if (files.length == 0) {
            when(cursor.hasNext()).thenReturn(false);
        } else if (files.length == 1) {
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(files[0]);
        } else {
            when(cursor.hasNext()).thenReturn(true, true, false);
            when(cursor.next()).thenReturn(files[0], files[1]);
        }
    }

    // ─── store() ────────────────────────────────────────────────

    @Test
    void store_validData_returnsUuidRef() throws Exception {
        byte[] data = "Hello, World!".getBytes();
        when(gridFSBucket.uploadFromStream(anyString(), any(ByteArrayInputStream.class), any(GridFSUploadOptions.class)))
                .thenReturn(new ObjectId());

        Attachment result = sut.store(data, "application/octet-stream", "test.txt", "conv-1", "tenant-1");

        assertNotNull(result);
        // storageRef is a random UUID, not the ObjectId hex
        assertDoesNotThrow(() -> java.util.UUID.fromString(result.storageRef()));
        assertEquals("test.txt", result.filename());
        assertEquals("application/octet-stream", result.mimeType());
        assertEquals(data.length, result.sizeBytes());
        assertEquals("conv-1", result.conversationId());
    }

    @Test
    void store_nullBytes_throwsException() {
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(null, "text/plain", "f.txt", "conv", "t"));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void store_emptyBytes_throwsException() {
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(new byte[0], "text/plain", "f.txt", "conv", "t"));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void store_exceedsMaxSize_throwsException() throws Exception {
        setLong("maxSizeBytes", 5L);
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(new byte[6], "application/octet-stream", "big.bin", "conv", "t"));
        assertTrue(ex.getMessage().contains("exceeds max size"));
    }

    @Test
    void store_mimeMismatch_throwsException() {
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(pngBytes, "image/jpeg", "fake.jpg", "conv", "t"));
        assertTrue(ex.getMessage().contains("MIME type mismatch"));
    }

    @Test
    void store_countQuotaExceeded_throwsException() throws Exception {
        setLong("maxPerConversation", 1L);
        // one existing file → adding a second exceeds the count quota
        whenFindIterate(mockFile(new ObjectId(), "conv", "text/plain", List.of(), "r1"));

        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store("data".getBytes(), "text/plain", "f.txt", "conv", "t"));
        assertTrue(ex.getMessage().contains("quota exceeded"));
    }

    @Test
    void store_byteQuotaExceeded_throwsException() throws Exception {
        setLong("maxTotalBytesPerConversation", 100L);
        GridFSFile existing = mock(GridFSFile.class);
        when(existing.getLength()).thenReturn(60L);
        whenFindIterate(existing); // 60 existing + 50 incoming > 100

        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(new byte[50], "text/plain", "f.txt", "conv", "t"));
        assertTrue(ex.getMessage().contains("storage quota exceeded"));
    }

    // ─── load() ─────────────────────────────────────────────────

    @Test
    void load_owner_returnsBytes() throws Exception {
        ObjectId id = new ObjectId();
        whenFindFirst(mockFile(id, "conv-1", "text/plain", List.of(), "uuid-1"));
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("file content".getBytes());
            return null;
        }).when(gridFSBucket).downloadToStream(eq(id), any(OutputStream.class));

        byte[] result = sut.load("uuid-1", "conv-1");
        assertArrayEquals("file content".getBytes(), result);
    }

    @Test
    void load_grantedConversation_returnsBytes() throws Exception {
        ObjectId id = new ObjectId();
        whenFindFirst(mockFile(id, "conv-owner", "text/plain", List.of("conv-guest"), "uuid-1"));
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("granted".getBytes());
            return null;
        }).when(gridFSBucket).downloadToStream(eq(id), any(OutputStream.class));

        byte[] result = sut.load("uuid-1", "conv-guest");
        assertArrayEquals("granted".getBytes(), result);
    }

    @Test
    void load_notFound_throwsException() {
        whenFindFirst(null);
        var ex = assertThrows(AttachmentStoreException.class, () -> sut.load("missing", "conv-1"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void load_crossConversationNoGrant_throwsException() {
        whenFindFirst(mockFile(new ObjectId(), "conv-owner", "text/plain", List.of(), "uuid-1"));
        var ex = assertThrows(AttachmentStoreException.class, () -> sut.load("uuid-1", "conv-other"));
        assertTrue(ex.getMessage().contains("Cross-conversation access denied"));
    }

    @Test
    void load_nullMetadata_allowsAccess() throws Exception {
        ObjectId id = new ObjectId();
        GridFSFile f = mock(GridFSFile.class);
        when(f.getObjectId()).thenReturn(id);
        when(f.getMetadata()).thenReturn(null);
        whenFindFirst(f);
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("data".getBytes());
            return null;
        }).when(gridFSBucket).downloadToStream(eq(id), any(OutputStream.class));

        assertArrayEquals("data".getBytes(), sut.load("any", "any-conv"));
    }

    @Test
    void load_legacyObjectIdRef_resolves() throws Exception {
        ObjectId id = new ObjectId();
        whenFindFirst(mockFile(id, "conv-1", "text/plain", List.of(), null));
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("legacy".getBytes());
            return null;
        }).when(gridFSBucket).downloadToStream(eq(id), any(OutputStream.class));

        // a valid ObjectId hex still resolves (legacy blobs)
        byte[] result = sut.load(id.toHexString(), "conv-1");
        assertArrayEquals("legacy".getBytes(), result);
    }

    @Test
    void load_nullOwnerMetadata_allowsAccess() throws Exception {
        // metadata present but conversationId absent → owner check skipped
        ObjectId id = new ObjectId();
        whenFindFirst(mockFile(id, null, "text/plain", List.of(), "uuid-1"));
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("ok".getBytes());
            return null;
        }).when(gridFSBucket).downloadToStream(eq(id), any(OutputStream.class));

        assertArrayEquals("ok".getBytes(), sut.load("uuid-1", "any-conv"));
    }

    // ─── getMetadata() ──────────────────────────────────────────

    @Test
    void getMetadata_owner_returnsMetadata() throws Exception {
        whenFindFirst(mockFile(new ObjectId(), "conv-1", "image/png", List.of(), "uuid-1"));
        Attachment meta = sut.getMetadata("uuid-1", "conv-1");
        assertEquals("uuid-1", meta.storageRef());
        assertEquals("image/png", meta.mimeType());
        assertEquals("conv-1", meta.conversationId());
        assertEquals(123L, meta.sizeBytes());
    }

    @Test
    void getMetadata_denied_throws() {
        whenFindFirst(mockFile(new ObjectId(), "conv-1", "image/png", List.of(), "uuid-1"));
        assertThrows(AttachmentStoreException.class, () -> sut.getMetadata("uuid-1", "conv-other"));
    }

    @Test
    void getMetadata_notFound_throws() {
        whenFindFirst(null);
        assertThrows(AttachmentStoreException.class, () -> sut.getMetadata("missing", "conv-1"));
    }

    @Test
    void getMetadata_grantedConversation_returnsMetadata() throws Exception {
        whenFindFirst(mockFile(new ObjectId(), "conv-owner", "application/pdf", List.of("conv-guest"), "uuid-1"));
        Attachment meta = sut.getMetadata("uuid-1", "conv-guest");
        assertEquals("application/pdf", meta.mimeType());
    }

    @Test
    void getMetadata_nullMetadataDefaults() throws Exception {
        GridFSFile f = mock(GridFSFile.class);
        when(f.getFilename()).thenReturn("x.bin");
        when(f.getLength()).thenReturn(9L);
        when(f.getMetadata()).thenReturn(null);
        whenFindFirst(f);

        Attachment meta = sut.getMetadata("uuid-1", "conv-1");
        assertEquals("application/octet-stream", meta.mimeType());
        assertEquals("uuid-1", meta.storageRef());
    }

    // ─── grantAccess() ──────────────────────────────────────────

    @Test
    void grantAccess_updatesMetadata() throws Exception {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(1L);
        when(filesCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertDoesNotThrow(() -> sut.grantAccess("uuid-1", "conv-guest"));
        verify(filesCollection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void grantAccess_notFound_throws() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getMatchedCount()).thenReturn(0L);
        when(filesCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertThrows(AttachmentStoreException.class, () -> sut.grantAccess("missing", "conv-guest"));
    }

    // ─── delete() ───────────────────────────────────────────────

    @Test
    void delete_owner_deletesAndReturnsTrue() throws Exception {
        ObjectId id = new ObjectId();
        whenFindFirst(mockFile(id, "conv-1", "text/plain", List.of(), "uuid-1"));
        assertTrue(sut.delete("uuid-1", "conv-1"));
        verify(gridFSBucket).delete(id);
    }

    @Test
    void delete_notFound_returnsFalse() throws Exception {
        whenFindFirst(null);
        assertFalse(sut.delete("missing", "conv-1"));
    }

    @Test
    void delete_nonOwner_throws() {
        whenFindFirst(mockFile(new ObjectId(), "conv-owner", "text/plain", List.of("conv-guest"), "uuid-1"));
        // even a grantee cannot delete
        assertThrows(AttachmentStoreException.class, () -> sut.delete("uuid-1", "conv-guest"));
    }

    // ─── deleteByConversation() ─────────────────────────────────

    @Test
    void deleteByConversation_deletesMatchingFiles() {
        ObjectId id1 = new ObjectId();
        ObjectId id2 = new ObjectId();
        GridFSFile f1 = mock(GridFSFile.class);
        GridFSFile f2 = mock(GridFSFile.class);
        when(f1.getObjectId()).thenReturn(id1);
        when(f2.getObjectId()).thenReturn(id2);
        whenFindIterate(f1, f2);

        long count = sut.deleteByConversation("conv-1");
        assertEquals(2, count);
        verify(gridFSBucket).delete(id1);
        verify(gridFSBucket).delete(id2);
    }

    @Test
    void deleteByConversation_noFiles_returnsZero() {
        whenFindIterate();
        assertEquals(0, sut.deleteByConversation("conv-empty"));
    }

    // ─── listByConversation() ───────────────────────────────────

    @Test
    void listByConversation_returnsAttachmentsWithUuidRef() {
        ObjectId id1 = new ObjectId();
        GridFSFile f1 = mock(GridFSFile.class);
        when(f1.getObjectId()).thenReturn(id1);
        when(f1.getFilename()).thenReturn("image.png");
        when(f1.getLength()).thenReturn(1024L);
        when(f1.getMetadata()).thenReturn(new Document()
                .append("mimeType", "image/png").append("storageRef", "uuid-1")
                .append("conversationId", "conv-1"));
        whenFindIterate(f1);

        List<Attachment> results = sut.listByConversation("conv-1");
        assertEquals(1, results.size());
        assertEquals("uuid-1", results.getFirst().storageRef());
        assertEquals("image.png", results.getFirst().filename());
        assertEquals("image/png", results.getFirst().mimeType());
        assertEquals(1024L, results.getFirst().sizeBytes());
        assertEquals("conv-1", results.getFirst().conversationId());
    }

    @Test
    void listByConversation_nullMetadata_usesDefaultsAndObjectIdRef() {
        ObjectId id1 = new ObjectId();
        GridFSFile f1 = mock(GridFSFile.class);
        when(f1.getObjectId()).thenReturn(id1);
        when(f1.getFilename()).thenReturn("unknown.bin");
        when(f1.getLength()).thenReturn(512L);
        when(f1.getMetadata()).thenReturn(null);
        whenFindIterate(f1);

        List<Attachment> results = sut.listByConversation("conv-1");
        assertEquals(1, results.size());
        assertEquals("application/octet-stream", results.getFirst().mimeType());
        assertEquals(id1.toHexString(), results.getFirst().storageRef());
    }

    @Test
    void listByConversation_emptyResults() {
        whenFindIterate();
        assertTrue(sut.listByConversation("conv-empty").isEmpty());
    }

    @Test
    void listAccessible_returnsOwnerFromMetadata() {
        // A blob owned by another conversation but granted to the requester: the
        // OR filter returns it, and its owner (not the requester) is reported.
        GridFSFile f = mock(GridFSFile.class);
        when(f.getObjectId()).thenReturn(new ObjectId());
        when(f.getFilename()).thenReturn("shared.pdf");
        when(f.getLength()).thenReturn(10L);
        when(f.getMetadata()).thenReturn(new Document()
                .append("mimeType", "application/pdf").append("storageRef", "uuid-g")
                .append("conversationId", "owner-conv"));
        whenFindIterate(f);

        List<Attachment> results = sut.listAccessible("member-conv");
        assertEquals(1, results.size());
        assertEquals("uuid-g", results.getFirst().storageRef());
        assertEquals("owner-conv", results.getFirst().conversationId());
    }
}
