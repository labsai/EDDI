/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.objenesis.ObjenesisStd;

import java.io.InputStream;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The GridFS store's quotas: checked under a lock per scope (so concurrent
 * uploads cannot both pass), released on every path, and optionally per user.
 */
class GridFsAttachmentQuotaTest {

    private GridFSBucket gridFSBucket;
    private MongoCollection<Document> locks;
    private GridFsAttachmentStore sut;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        gridFSBucket = mock(GridFSBucket.class);
        locks = mock(MongoCollection.class);
        sut = new ObjenesisStd().newInstance(GridFsAttachmentStore.class);
        set("gridFSBucket", gridFSBucket);
        set("filesCollection", mock(MongoCollection.class));
        set("locksCollection", locks);
        set("maxSizeBytes", 1_000_000L);
        set("maxPerConversation", 0L);
        set("maxTotalBytesPerConversation", 0L);
        set("maxPerUser", 0L);
        set("maxTotalBytesPerUser", 0L);
        when(gridFSBucket.uploadFromStream(anyString(), any(InputStream.class), any(GridFSUploadOptions.class)))
                .thenReturn(new ObjectId());
    }

    private void set(String field, Object value) throws Exception {
        Field f = GridFsAttachmentStore.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(sut, value);
    }

    /**
     * gridFSBucket.find(any) iterates {@code count} files of {@code length} bytes
     * each.
     */
    @SuppressWarnings("unchecked")
    private void givenExistingFiles(int count, long length) {
        GridFSFindIterable iterable = mock(GridFSFindIterable.class);
        MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
        when(gridFSBucket.find(any(Bson.class))).thenReturn(iterable);
        doReturn(cursor).when(iterable).iterator();
        var hasNext = when(cursor.hasNext());
        for (int i = 0; i < count; i++) {
            hasNext = hasNext.thenReturn(true);
        }
        hasNext.thenReturn(false);
        GridFSFile file = mock(GridFSFile.class);
        when(file.getLength()).thenReturn(length);
        when(cursor.next()).thenReturn(file);
    }

    private static MongoWriteException duplicateKey() {
        return new MongoWriteException(new WriteError(11000, "E11000 duplicate key", new BsonDocument()),
                new ServerAddress());
    }

    @Test
    void conversationQuota_checksAndUploadsUnderTheLock_thenReleasesIt() throws Exception {
        set("maxPerConversation", 5L);
        givenExistingFiles(1, 10);

        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, "u1");

        InOrder order = inOrder(locks, gridFSBucket);
        order.verify(locks).insertOne(any(Document.class));
        order.verify(gridFSBucket).find(any(Bson.class));
        order.verify(gridFSBucket).uploadFromStream(anyString(), any(InputStream.class), any(GridFSUploadOptions.class));
        order.verify(locks).deleteOne(any(Bson.class));
    }

    @Test
    void overQuota_isRefused_andTheLockIsStillReleased() throws Exception {
        set("maxPerConversation", 1L);
        givenExistingFiles(1, 10);

        var refused = assertThrows(AttachmentQuotaExceededException.class,
                () -> sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, null));

        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, refused.getScope());
        verify(gridFSBucket, never()).uploadFromStream(anyString(), any(InputStream.class), any(GridFSUploadOptions.class));
        verify(locks).deleteOne(any(Bson.class));
    }

    @Test
    void aHeldLock_isWaitedFor_notSkipped() throws Exception {
        // Another upload holds the conversation lock for the first attempt; this one
        // must wait, not check the quota without it.
        set("maxPerConversation", 5L);
        givenExistingFiles(0, 0);
        doThrow(duplicateKey()).doReturn(null).when(locks).insertOne(any(Document.class));

        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, null);

        verify(locks, times(2)).insertOne(any(Document.class));
        verify(gridFSBucket).uploadFromStream(anyString(), any(InputStream.class), any(GridFSUploadOptions.class));
    }

    @Test
    void anExpiredLock_isTakenOver() throws Exception {
        set("maxPerConversation", 5L);
        givenExistingFiles(0, 0);
        doThrow(duplicateKey()).when(locks).insertOne(any(Document.class));
        when(locks.findOneAndUpdate(any(Bson.class), any(Bson.class))).thenReturn(new Document("_id", "x"));

        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, null);

        verify(gridFSBucket).uploadFromStream(anyString(), any(InputStream.class), any(GridFSUploadOptions.class));
    }

    private static BsonDocument bson(Bson filter) {
        return filter.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    @Test
    void theTakeoverFilter_matchesOnlyALeaseThatHasRunOut() throws Exception {
        set("maxPerConversation", 5L);
        givenExistingFiles(0, 0);
        doThrow(duplicateKey()).when(locks).insertOne(any(Document.class));
        when(locks.findOneAndUpdate(any(Bson.class), any(Bson.class))).thenReturn(new Document("_id", "x"));
        long before = System.currentTimeMillis();

        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, null);

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(locks).findOneAndUpdate(filter.capture(), any(Bson.class));
        String rendered = bson(filter.getValue()).toJson();
        assertTrue(rendered.contains("conversation:conv-1"), rendered);
        assertTrue(rendered.contains("$lt") && rendered.contains("expiresAt"), rendered);
        long bound = bson(filter.getValue()).getArray("$and").stream().map(v -> v.asDocument()).filter(d -> d.containsKey("expiresAt"))
                .findFirst().orElseThrow().getDocument("expiresAt").getDateTime("$lt").getValue();
        assertTrue(bound >= before, "a lease is expired only relative to now, not to the epoch: " + bound);
    }

    @Test
    void theRelease_deletesOnlyTheLeaseThisHolderOwns() throws Exception {
        set("maxPerConversation", 5L);
        givenExistingFiles(0, 0);

        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, null);

        ArgumentCaptor<Document> inserted = ArgumentCaptor.forClass(Document.class);
        verify(locks).insertOne(inserted.capture());
        ArgumentCaptor<Bson> released = ArgumentCaptor.forClass(Bson.class);
        verify(locks).deleteOne(released.capture());
        String rendered = bson(released.getValue()).toJson();
        assertTrue(rendered.contains(inserted.getValue().getString("owner")), "the filter must name the holder token: " + rendered);
    }

    @Test
    void aFailingLockStore_failsTheUpload() throws Exception {
        WriteError notADuplicate = new WriteError(2, "bad", new BsonDocument());
        assertTrue(notADuplicate.getCategory() != ErrorCategory.DUPLICATE_KEY);
        when(locks.insertOne(any(Document.class))).thenThrow(new MongoWriteException(notADuplicate, new ServerAddress()));
        set("maxPerConversation", 5L);

        assertThrows(AttachmentStoreException.class,
                () -> sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-1", null, null));
        verify(gridFSBucket, never()).uploadFromStream(anyString(), any(InputStream.class), any(GridFSUploadOptions.class));
    }

    @Test
    void userQuota_countsTheUsersBlobs_andRefusesWithUserScope() throws Exception {
        set("maxTotalBytesPerUser", 100L);
        givenExistingFiles(2, 50);

        var refused = assertThrows(AttachmentQuotaExceededException.class,
                () -> sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-2", null, "u1"));

        assertEquals(AttachmentQuotaExceededException.SCOPE_USER, refused.getScope());
        ArgumentCaptor<Document> lock = ArgumentCaptor.forClass(Document.class);
        verify(locks).insertOne(lock.capture());
        assertEquals("user:u1", lock.getValue().getString("_id"));
    }

    @Test
    void userQuota_doesNotApplyWithoutAUser() throws Exception {
        set("maxPerUser", 1L);
        givenExistingFiles(5, 1);

        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-2", null, null);

        verify(locks, never()).insertOne(any(Document.class));
    }

    @Test
    void theOwnerIsRecordedOnTheBlob() throws Exception {
        sut.store("hello".getBytes(), "text/plain", "a.txt", "conv-2", null, "u1");

        ArgumentCaptor<GridFSUploadOptions> options = ArgumentCaptor.forClass(GridFSUploadOptions.class);
        verify(gridFSBucket).uploadFromStream(anyString(), any(InputStream.class), options.capture());
        assertEquals("u1", options.getValue().getMetadata().getString("userId"));
    }
}
