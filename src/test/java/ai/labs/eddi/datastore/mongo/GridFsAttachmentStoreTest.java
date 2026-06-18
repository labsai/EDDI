/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private GridFsAttachmentStore sut;

    @BeforeEach
    void setUp() throws Exception {
        gridFSBucket = mock(GridFSBucket.class);

        // Create the store with a mocked MongoDatabase, then replace the bucket via
        // reflection
        // since the constructor calls the static GridFSBuckets.create()
        sut = createWithMockedBucket(gridFSBucket);

        // Set maxSizeBytes via reflection
        Field maxSizeField = GridFsAttachmentStore.class.getDeclaredField("maxSizeBytes");
        maxSizeField.setAccessible(true);
        maxSizeField.set(sut, 20_971_520L);
    }

    private static GridFsAttachmentStore createWithMockedBucket(GridFSBucket bucket) throws Exception {
        // Allocate instance without calling constructor (which would call
        // GridFSBuckets.create())
        var objenesis = new org.objenesis.ObjenesisStd();
        GridFsAttachmentStore store = objenesis.newInstance(GridFsAttachmentStore.class);
        Field bucketField = GridFsAttachmentStore.class.getDeclaredField("gridFSBucket");
        bucketField.setAccessible(true);
        bucketField.set(store, bucket);
        return store;
    }

    // ─── store() ────────────────────────────────────────────────

    @Test
    void store_validData_returnsAttachment() throws Exception {
        // given
        byte[] data = "Hello, World!".getBytes();
        ObjectId fileId = new ObjectId();
        when(gridFSBucket.uploadFromStream(anyString(), any(ByteArrayInputStream.class), any(GridFSUploadOptions.class)))
                .thenReturn(fileId);

        // when
        Attachment result = sut.store(data, "application/octet-stream", "test.txt", "conv-1", "tenant-1");

        // then
        assertNotNull(result);
        assertEquals(fileId.toHexString(), result.storageRef());
        assertEquals("test.txt", result.filename());
        assertEquals("application/octet-stream", result.mimeType());
        assertEquals(data.length, result.sizeBytes());
        assertEquals("conv-1", result.conversationId());
    }

    @Test
    void store_nullFilename_usesUnnamed() throws Exception {
        // given
        byte[] data = "content".getBytes();
        ObjectId fileId = new ObjectId();
        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        when(gridFSBucket.uploadFromStream(filenameCaptor.capture(), any(ByteArrayInputStream.class),
                any(GridFSUploadOptions.class))).thenReturn(fileId);

        // when
        sut.store(data, "application/octet-stream", null, "conv-1", "tenant-1");

        // then
        assertEquals("unnamed", filenameCaptor.getValue());
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
        // given — set small max
        Field maxField = GridFsAttachmentStore.class.getDeclaredField("maxSizeBytes");
        maxField.setAccessible(true);
        maxField.set(sut, 5L);

        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(new byte[6], "application/octet-stream", "big.bin", "conv", "t"));
        assertTrue(ex.getMessage().contains("exceeds max size"));
    }

    @Test
    void store_mimeMismatch_throwsException() {
        // given — PNG magic bytes, declared as JPEG
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.store(pngBytes, "image/jpeg", "fake.jpg", "conv", "t"));
        assertTrue(ex.getMessage().contains("MIME type mismatch"));
    }

    // ─── load() ─────────────────────────────────────────────────

    @Test
    void load_validRef_returnsBytes() throws Exception {
        // given
        ObjectId fileId = new ObjectId();
        GridFSFile gridFSFile = mock(GridFSFile.class);
        Document metadata = new Document().append("conversationId", "conv-1");

        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(gridFSFile);
        when(gridFSFile.getMetadata()).thenReturn(metadata);

        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("file content".getBytes());
            return null;
        }).when(gridFSBucket).downloadToStream(eq(fileId), any(OutputStream.class));

        // when
        byte[] result = sut.load(fileId.toHexString(), "conv-1");

        // then
        assertArrayEquals("file content".getBytes(), result);
    }

    @Test
    void load_notFound_throwsException() throws Exception {
        // given
        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.load(new ObjectId().toHexString(), "conv-1"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void load_crossConversation_throwsException() throws Exception {
        // given
        ObjectId fileId = new ObjectId();
        GridFSFile gridFSFile = mock(GridFSFile.class);
        Document metadata = new Document().append("conversationId", "conv-owner");

        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(gridFSFile);
        when(gridFSFile.getMetadata()).thenReturn(metadata);

        // when/then
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.load(fileId.toHexString(), "conv-other"));
        assertTrue(ex.getMessage().contains("Cross-conversation access denied"));
    }

    @Test
    void load_nullMetadata_allowsAccess() throws Exception {
        // given — metadata is null, owner check is skipped
        ObjectId fileId = new ObjectId();
        GridFSFile gridFSFile = mock(GridFSFile.class);

        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(gridFSFile);
        when(gridFSFile.getMetadata()).thenReturn(null);

        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("data".getBytes());
            return null;
        }).when(gridFSBucket).downloadToStream(eq(fileId), any(OutputStream.class));

        // when
        byte[] result = sut.load(fileId.toHexString(), "any-conv");

        // then
        assertArrayEquals("data".getBytes(), result);
    }

    @Test
    void load_metadataNullConversationId_allowsAccess() throws Exception {
        // given — metadata exists but conversationId is null
        ObjectId fileId = new ObjectId();
        GridFSFile gridFSFile = mock(GridFSFile.class);
        Document metadata = new Document(); // no conversationId key

        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(gridFSFile);
        when(gridFSFile.getMetadata()).thenReturn(metadata);

        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("bytes".getBytes());
            return null;
        }).when(gridFSBucket).downloadToStream(eq(fileId), any(OutputStream.class));

        // when
        byte[] result = sut.load(fileId.toHexString(), "conv-1");

        // then
        assertNotNull(result);
    }

    @Test
    void load_invalidStorageRef_throwsException() {
        // given — not a valid ObjectId
        var ex = assertThrows(AttachmentStoreException.class,
                () -> sut.load("not-a-valid-object-id", "conv-1"));
        assertTrue(ex.getMessage().contains("Invalid storage reference"));
    }

    // ─── deleteByConversation() ─────────────────────────────────

    @Test
    void deleteByConversation_deletesMatchingFiles() throws Exception {
        // given
        GridFSFile file1 = mock(GridFSFile.class);
        GridFSFile file2 = mock(GridFSFile.class);
        ObjectId id1 = new ObjectId();
        ObjectId id2 = new ObjectId();
        when(file1.getObjectId()).thenReturn(id1);
        when(file2.getObjectId()).thenReturn(id2);

        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);

        @SuppressWarnings("unchecked")
        MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(findIterable).iterator();
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(file1, file2);

        // when
        long count = sut.deleteByConversation("conv-1");

        // then
        assertEquals(2, count);
        verify(gridFSBucket).delete(id1);
        verify(gridFSBucket).delete(id2);
    }

    @Test
    void deleteByConversation_noFiles_returnsZero() throws Exception {
        // given
        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);

        @SuppressWarnings("unchecked")
        MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(findIterable).iterator();
        when(cursor.hasNext()).thenReturn(false);

        // when
        long count = sut.deleteByConversation("conv-empty");

        // then
        assertEquals(0, count);
    }

    // ─── listByConversation() ───────────────────────────────────

    @Test
    void listByConversation_returnsAttachments() throws Exception {
        // given
        ObjectId id1 = new ObjectId();
        GridFSFile file1 = mock(GridFSFile.class);
        when(file1.getObjectId()).thenReturn(id1);
        when(file1.getFilename()).thenReturn("image.png");
        when(file1.getLength()).thenReturn(1024L);
        Document metadata1 = new Document().append("mimeType", "image/png");
        when(file1.getMetadata()).thenReturn(metadata1);

        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);

        @SuppressWarnings("unchecked")
        MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(findIterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(file1);

        // when
        List<Attachment> results = sut.listByConversation("conv-1");

        // then
        assertEquals(1, results.size());
        assertEquals(id1.toHexString(), results.getFirst().storageRef());
        assertEquals("image.png", results.getFirst().filename());
        assertEquals("image/png", results.getFirst().mimeType());
        assertEquals(1024L, results.getFirst().sizeBytes());
        assertEquals("conv-1", results.getFirst().conversationId());
    }

    @Test
    void listByConversation_nullMetadata_usesDefaultMime() throws Exception {
        // given
        ObjectId id1 = new ObjectId();
        GridFSFile file1 = mock(GridFSFile.class);
        when(file1.getObjectId()).thenReturn(id1);
        when(file1.getFilename()).thenReturn("unknown.bin");
        when(file1.getLength()).thenReturn(512L);
        when(file1.getMetadata()).thenReturn(null);

        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);

        @SuppressWarnings("unchecked")
        MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(findIterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(file1);

        // when
        List<Attachment> results = sut.listByConversation("conv-1");

        // then
        assertEquals(1, results.size());
        assertEquals("application/octet-stream", results.getFirst().mimeType());
    }

    @Test
    void listByConversation_emptyResults() throws Exception {
        // given
        GridFSFindIterable findIterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(findIterable);

        @SuppressWarnings("unchecked")
        MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(findIterable).iterator();
        when(cursor.hasNext()).thenReturn(false);

        // when
        List<Attachment> results = sut.listByConversation("conv-empty");

        // then
        assertTrue(results.isEmpty());
    }
}
