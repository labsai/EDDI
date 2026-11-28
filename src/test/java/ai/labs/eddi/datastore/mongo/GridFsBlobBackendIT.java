/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.blob.BlobBackend;
import ai.labs.eddi.engine.attachments.blob.BlobBackendContract;
import ai.labs.eddi.engine.attachments.blob.BlobKeys;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GridFsBlobBackend} against a real MongoDB (Testcontainers): the shared
 * {@link BlobBackendContract}, plus what only GridFS can get wrong — chunks
 * orphaned by a crashed upload, and the existing {@code attachments} bucket.
 */
@DisplayName("BlobBackend contract (gridfs)")
class GridFsBlobBackendIT extends MongoTestBase implements BlobBackendContract {

    private static final int CHUNK_BYTES = 64 * 1024;

    private GridFsBlobBackend backend;

    @BeforeEach
    void setUp() {
        dropCollections(GridFsBlobBackend.BUCKET_NAME + ".files", GridFsBlobBackend.BUCKET_NAME + ".chunks", "attachments.files",
                "attachments.chunks");
        backend = new GridFsBlobBackend(getDatabase(), CHUNK_BYTES);
    }

    @Override
    public BlobBackend backend() {
        return backend;
    }

    @Override
    public BlobBackend backendWithChunkBytes(int chunkBytes) {
        return new GridFsBlobBackend(getDatabase(), chunkBytes);
    }

    @Override
    public int chunkBytes() {
        return CHUNK_BYTES;
    }

    @Override
    public long storedRecordCount() {
        return getDatabase().getCollection(GridFsBlobBackend.BUCKET_NAME + ".files").countDocuments()
                + getDatabase().getCollection(GridFsBlobBackend.BUCKET_NAME + ".chunks").countDocuments();
    }

    @Override
    public List<String> heapProbeArgs() {
        return List.of(GridFsBlobBackend.NAME, MONGO.getConnectionString(), getDatabase().getName());
    }

    /**
     * GridFS's own default. The test container is a one-node replica set, so every
     * chunk insert waits for a majority (journal) commit: 64 KiB chunks make the 64
     * MiB probe take a minute.
     */
    @Override
    public int heapProbeChunkBytes() {
        return 255 * 1024;
    }

    @Test
    void listsAndDeletesTheOrphanChunksOfACrashedUpload() throws Exception {
        String key = BlobKeys.random();
        // What a node that died mid-upload leaves: chunks, no files document.
        getDatabase().getCollection(GridFsBlobBackend.BUCKET_NAME + ".chunks")
                .insertOne(new Document("files_id", key).append("n", 0).append("data", new Binary(new byte[]{1, 2, 3})));

        try (Stream<String> keys = backend.list("")) {
            assertEquals(List.of(key), keys.toList());
        }
        assertThrows(FileAlreadyExistsException.class, () -> backend.put(key, new ByteArrayInputStream(new byte[]{9}), 1, 1));

        backend.delete(key);

        assertEquals(0, storedRecordCount());
    }

    /**
     * Two puts of one absent key both pass the existence check. A blocks in its
     * source before writing a chunk, B completes, then A's source fails: A stored
     * nothing, so it must not purge B's object.
     */
    @Test
    void aFailedPutDoesNotRemoveTheObjectACompetingPutCompleted() throws Exception {
        String key = BlobKeys.random();
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InputStream blocked = new InputStream() {
            @Override
            public int read() throws IOException {
                inRead.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("the source broke");
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> {
                backend.put(key, blocked, 10, 10);
                return null;
            });
            assertTrue(inRead.await(30, TimeUnit.SECONDS), "the first put reached its source");

            byte[] winner = {4, 5, 6};
            backend.put(key, new ByteArrayInputStream(winner), winner.length, winner.length);
            release.countDown();

            ExecutionException failed = assertThrows(ExecutionException.class, () -> first.get(30, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failed.getCause());
            try (InputStream in = backend.get(key)) {
                assertArrayEquals(winner, in.readAllBytes(), "the completed object survives the failed put");
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void neverTouchesTheAttachmentsBucket() throws Exception {
        String key = BlobKeys.random();
        backend.put(key, new ByteArrayInputStream(new byte[3 * CHUNK_BYTES]), 3 * CHUNK_BYTES, 3 * CHUNK_BYTES);
        backend.delete(key);
        backend.put(key, InputStream.nullInputStream(), 0, 1);

        List<String> collections = getDatabase().listCollectionNames().into(new ArrayList<>());
        assertFalse(collections.contains("attachments.files"), collections.toString());
        assertFalse(collections.contains("attachments.chunks"), collections.toString());
    }

    @Test
    void storesTheKeyAsTheFileIdWithTheChunkSizeItWasWrittenWith() throws Exception {
        String key = BlobKeys.random();
        backend.put(key, new ByteArrayInputStream(new byte[2 * CHUNK_BYTES + 1]), 2 * CHUNK_BYTES + 1, 2 * CHUNK_BYTES + 1);

        Document file = getDatabase().getCollection(GridFsBlobBackend.BUCKET_NAME + ".files").find().first();
        assertEquals(key, file.getString("_id"));
        assertEquals(CHUNK_BYTES, file.getInteger("chunkSize"));
        assertEquals(3, getDatabase().getCollection(GridFsBlobBackend.BUCKET_NAME + ".chunks").countDocuments());
    }
}
