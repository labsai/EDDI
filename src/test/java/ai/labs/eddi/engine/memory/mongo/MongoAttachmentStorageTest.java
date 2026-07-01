/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.mongo;

import ai.labs.eddi.engine.memory.IAttachmentStorage;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class MongoAttachmentStorageTest {

    private static final String VALID_ID = "aabbccddeeff112233445566";
    private static final ObjectId TEST_OID = new ObjectId(VALID_ID);

    private GridFSBucket gridFSBucket;
    private MongoAttachmentStorage storage;

    @BeforeEach
    void setUp() {
        gridFSBucket = mock(GridFSBucket.class);
        MongoDatabase database = mock(MongoDatabase.class);

        try (MockedStatic<GridFSBuckets> mocked = mockStatic(GridFSBuckets.class)) {
            mocked.when(() -> GridFSBuckets.create(database, "eddi_attachments")).thenReturn(gridFSBucket);
            storage = new MongoAttachmentStorage(database);
        }
    }

    // ==================== store ====================

    @Test
    @DisplayName("store — uploads and returns gridfs:// reference")
    void storeAttachment() {
        when(gridFSBucket.uploadFromStream(anyString(), any(InputStream.class), any(GridFSUploadOptions.class)))
                .thenReturn(TEST_OID);

        InputStream data = new ByteArrayInputStream("hello".getBytes());
        String ref = storage.store("conv-1", "file.txt", "text/plain", data, 5);

        assertEquals("gridfs://" + VALID_ID, ref);
        verify(gridFSBucket).uploadFromStream(eq("file.txt"), eq(data), any(GridFSUploadOptions.class));
    }

    @Test
    @DisplayName("store — uses 'unnamed' when fileName is null")
    void storeNullFileName() {
        when(gridFSBucket.uploadFromStream(anyString(), any(InputStream.class), any(GridFSUploadOptions.class)))
                .thenReturn(TEST_OID);

        InputStream data = new ByteArrayInputStream("data".getBytes());
        storage.store("conv-1", null, "application/octet-stream", data, 0);

        verify(gridFSBucket).uploadFromStream(eq("unnamed"), any(InputStream.class), any(GridFSUploadOptions.class));
    }

    // ==================== load ====================

    @Test
    @DisplayName("load — returns input stream when file exists")
    void loadFound() throws Exception {
        GridFSFile gridFSFile = mock(GridFSFile.class);
        GridFSFindIterable iterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(gridFSFile);

        var downloadStream = mock(com.mongodb.client.gridfs.GridFSDownloadStream.class);
        when(gridFSBucket.openDownloadStream(any(ObjectId.class))).thenReturn(downloadStream);

        InputStream result = storage.load("gridfs://" + VALID_ID);
        assertNotNull(result);
    }

    @Test
    @DisplayName("load — throws AttachmentNotFoundException when file not found")
    void loadNotFound() {
        GridFSFindIterable iterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertThrows(IAttachmentStorage.AttachmentNotFoundException.class,
                () -> storage.load("gridfs://" + VALID_ID));
    }

    @Test
    @DisplayName("load — throws on null storageRef")
    void loadNullRef() {
        assertThrows(IAttachmentStorage.AttachmentNotFoundException.class,
                () -> storage.load(null));
    }

    @Test
    @DisplayName("load — throws on invalid prefix")
    void loadInvalidPrefix() {
        assertThrows(IAttachmentStorage.AttachmentNotFoundException.class,
                () -> storage.load("s3://bucket/key"));
    }

    @Test
    @DisplayName("load — throws on invalid ObjectId")
    void loadInvalidObjectId() {
        assertThrows(IAttachmentStorage.AttachmentNotFoundException.class,
                () -> storage.load("gridfs://invalid-id"));
    }

    // ==================== deleteByConversation ====================

    @Test
    @DisplayName("deleteByConversation — deletes matching files")
    void deleteByConversation() {
        GridFSFile file1 = mock(GridFSFile.class);
        when(file1.getObjectId()).thenReturn(new ObjectId());
        GridFSFile file2 = mock(GridFSFile.class);
        when(file2.getObjectId()).thenReturn(new ObjectId());

        GridFSFindIterable iterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(iterable);

        @SuppressWarnings("unchecked")
        com.mongodb.client.MongoCursor<GridFSFile> cursor = mock(com.mongodb.client.MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(file1, file2);

        long deleted = storage.deleteByConversation("conv-1");
        assertEquals(2, deleted);
        verify(gridFSBucket, times(2)).delete(any(ObjectId.class));
    }

    @Test
    @DisplayName("deleteByConversation — returns 0 when no files")
    void deleteByConversationEmpty() {
        GridFSFindIterable iterable = mock(GridFSFindIterable.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(iterable);

        @SuppressWarnings("unchecked")
        com.mongodb.client.MongoCursor<GridFSFile> cursor = mock(com.mongodb.client.MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(false);

        long deleted = storage.deleteByConversation("conv-1");
        assertEquals(0, deleted);
    }
}
