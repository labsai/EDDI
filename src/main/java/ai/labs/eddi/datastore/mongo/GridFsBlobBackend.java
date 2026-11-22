/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.blob.BlobBackend;
import ai.labs.eddi.engine.attachments.blob.BlobKeys;
import ai.labs.eddi.engine.attachments.blob.BlobStat;
import ai.labs.eddi.engine.attachments.blob.BlobStreams;
import ai.labs.eddi.engine.attachments.blob.LengthCheckedInputStream;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoGridFSException;
import com.mongodb.MongoServerException;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.GridFSUploadStream;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.BsonString;
import org.bson.Document;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The {@code gridfs} {@link BlobBackend}: each object is a GridFS file whose
 * {@code _id} is the key, in its own bucket {@code attachment_blobs}. The
 * existing {@code attachments} bucket of {@link GridFsAttachmentStore} is never
 * touched.
 * <p>
 * GridFS writes a file's chunks as they fill and its files document only on
 * close, so an upload that dies leaves chunks without a files document.
 * {@link #list} therefore reports the keys of such orphan chunks as well, and
 * {@link #delete} removes both documents and chunks.
 * <p>
 * Every read goes to the primary: the client reads {@code nearest}, and a
 * lagging secondary would answer "absent" for an object {@code put} just wrote.
 * This backend is not wired into the attachment store yet.
 */
@ApplicationScoped
public class GridFsBlobBackend implements BlobBackend {

    /** The name a {@code BlobLocator} records for this backend. */
    public static final String NAME = "gridfs";
    /** The bucket; deliberately not {@code attachments}. */
    public static final String BUCKET_NAME = "attachment_blobs";
    /** GridFS's own default chunk size. */
    static final int DEFAULT_CHUNK_BYTES = 255 * 1024;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    /** About how many bytes of chunks one read batch fetches. */
    private static final int READ_BATCH_BYTES = 4 * 1024 * 1024;

    private final GridFSBucket bucket;
    private final MongoCollection<Document> files;
    private final MongoCollection<Document> chunks;
    private final int chunkBytes;

    @Inject
    public GridFsBlobBackend(MongoDatabase database) {
        this(database, DEFAULT_CHUNK_BYTES);
    }

    /**
     * @param chunkBytes
     *            the GridFS chunk size for new objects; an object keeps the one it
     *            was written with
     */
    public GridFsBlobBackend(MongoDatabase database, int chunkBytes) {
        if (chunkBytes < 1) {
            throw new IllegalArgumentException("chunkBytes must be positive: " + chunkBytes);
        }
        MongoDatabase primary = database.withReadPreference(ReadPreference.primary());
        this.bucket = GridFSBuckets.create(primary, BUCKET_NAME);
        this.files = primary.getCollection(BUCKET_NAME + ".files");
        this.chunks = primary.getCollection(BUCKET_NAME + ".chunks");
        this.chunkBytes = chunkBytes;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void put(String key, InputStream in, long declaredLength, long maxBytes) throws IOException {
        BlobKeys.requireValidKey(key);
        LengthCheckedInputStream source = new LengthCheckedInputStream(in, declaredLength, maxBytes);
        GridFSUploadStream upload;
        try {
            if (exists(key)) {
                throw new FileAlreadyExistsException("A blob with this key already exists");
            }
            upload = bucket.openUploadStream(new BsonString(key), key, new GridFSUploadOptions().chunkSizeBytes(chunkBytes));
        } catch (MongoException e) {
            throw asIOException(e);
        }
        try {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int n;
            while ((n = source.read(buffer)) != -1) {
                upload.write(buffer, 0, n);
            }
            upload.close();
        } catch (IOException | RuntimeException e) {
            if (isDuplicateKey(e)) {
                // Another put won the key between the check and the write: the unique
                // index refused our first write, so nothing here is ours to remove. (Had the
                // winner been an empty object, our chunks would already be in and the refusal
                // would come from the files document on close; random keys make that moot.)
                throw new FileAlreadyExistsException("A blob with this key already exists");
            }
            IOException failure = asIOException(e);
            removePartial(upload, key, failure);
            throw failure;
        }
    }

    @Override
    public InputStream get(String key) throws IOException {
        BlobKeys.requireValidKey(key);
        return open(key).stream();
    }

    @Override
    public boolean supportsRange() {
        return true;
    }

    @Override
    public InputStream getRange(String key, long offset, long length) throws IOException {
        BlobKeys.requireValidKey(key);
        BlobStreams.requireValidRange(offset, length);
        Opened opened = open(key);
        InputStream stream = opened.stream();
        if (length == 0 || offset >= opened.length()) {
            stream.close();
            return InputStream.nullInputStream();
        }
        try {
            // GridFS's skip jumps to the chunk holding the offset rather than reading up to
            // it.
            long remaining = offset;
            while (remaining > 0) {
                long skipped = stream.skip(remaining);
                if (skipped <= 0) {
                    break;
                }
                remaining -= skipped;
            }
        } catch (IOException | RuntimeException e) {
            stream.close();
            throw asIOException(e);
        }
        return BlobStreams.limit(stream, length);
    }

    @Override
    public void delete(String key) throws IOException {
        BlobKeys.requireValidKey(key);
        try {
            purge(key);
        } catch (MongoException e) {
            throw asIOException(e);
        }
    }

    @Override
    public Optional<BlobStat> stat(String key) throws IOException {
        BlobKeys.requireValidKey(key);
        try {
            GridFSFile file = bucket.find(Filters.eq("_id", key)).first();
            return file == null ? Optional.empty() : Optional.of(new BlobStat(key, file.getLength()));
        } catch (MongoException e) {
            throw asIOException(e);
        }
    }

    @Override
    public Stream<String> list(String prefix) throws IOException {
        BlobKeys.requireValidPrefix(prefix);
        // The prefix is validated to letters, digits and '-', so it holds no regex
        // metacharacter.
        String anchored = "^" + prefix;
        MongoCursor<Document> fileIds = null;
        MongoCursor<Document> orphanIds = null;
        try {
            fileIds = files.find(Filters.regex("_id", anchored)).projection(Projections.include("_id")).sort(Sorts.ascending("_id"))
                    .iterator();
            orphanIds = chunks
                    .aggregate(List.of(Aggregates.match(Filters.regex("files_id", anchored)), Aggregates.group("$files_id"),
                            Aggregates.sort(Sorts.ascending("_id"))))
                    .allowDiskUse(true).iterator();
        } catch (MongoException e) {
            closeQuietly(fileIds);
            closeQuietly(orphanIds);
            throw asIOException(e);
        }
        MongoCursor<Document> filesCursor = fileIds;
        MongoCursor<Document> chunksCursor = orphanIds;
        // Both cursors ascend by key; merging them drops the duplicates (every
        // non-empty
        // object is in both) without remembering the keys already returned.
        Iterator<String> merged = new SortedUnion(ids(filesCursor), ids(chunksCursor));
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(merged, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL),
                false).onClose(() -> {
                    closeQuietly(filesCursor);
                    closeQuietly(chunksCursor);
                });
    }

    private record Opened(InputStream stream, long length) {
    }

    private Opened open(String key) throws IOException {
        try {
            GridFSDownloadStream download = bucket.openDownloadStream(new BsonString(key));
            // Unbounded, the server fills a batch up to 16 MB, decoded into documents on
            // the heap.
            download.batchSize(Math.max(1, READ_BATCH_BYTES / Math.max(1, download.getGridFSFile().getChunkSize())));
            return new Opened(new MongoErrorTranslatingInputStream(download), download.getGridFSFile().getLength());
        } catch (MongoGridFSException e) {
            // Thrown on open only when there is no files document for the id.
            throw new NoSuchFileException("No blob with this key");
        } catch (MongoException e) {
            throw asIOException(e);
        }
    }

    private boolean exists(String key) {
        return files.find(Filters.eq("_id", key)).projection(Projections.include("_id")).first() != null
                || chunks.find(Filters.eq("files_id", key)).projection(Projections.include("_id")).first() != null;
    }

    /**
     * Files document first, so the object stops being visible before its chunks go.
     */
    private void purge(String key) {
        files.deleteOne(Filters.eq("_id", key));
        chunks.deleteMany(Filters.eq("files_id", key));
    }

    /** Removes what a failed put wrote; never masks the failure. */
    private void removePartial(GridFSUploadStream upload, String key, IOException failure) {
        try {
            upload.abort();
        } catch (RuntimeException e) {
            // already closed, or the abort's own delete failed: the purge below covers both
            failure.addSuppressed(e);
        }
        try {
            purge(key);
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * The {@code _id}s of an ascending cursor; a read failure surfaces as
     * {@link UncheckedIOException}.
     */
    private static Iterator<String> ids(MongoCursor<Document> cursor) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                try {
                    return cursor.hasNext();
                } catch (MongoException e) {
                    throw new UncheckedIOException(asIOException(e));
                }
            }

            @Override
            public String next() {
                try {
                    return cursor.next().getString("_id");
                } catch (MongoException e) {
                    throw new UncheckedIOException(asIOException(e));
                }
            }
        };
    }

    /**
     * The union of two ascending key iterators, ascending and without duplicates.
     */
    private static final class SortedUnion implements Iterator<String> {

        private final Iterator<String> left;
        private final Iterator<String> right;
        private String nextLeft;
        private String nextRight;

        SortedUnion(Iterator<String> left, Iterator<String> right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean hasNext() {
            if (nextLeft == null && left.hasNext()) {
                nextLeft = left.next();
            }
            if (nextRight == null && right.hasNext()) {
                nextRight = right.next();
            }
            return nextLeft != null || nextRight != null;
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String result;
            if (nextRight == null) {
                result = nextLeft;
            } else if (nextLeft == null) {
                result = nextRight;
            } else {
                result = nextLeft.compareTo(nextRight) <= 0 ? nextLeft : nextRight;
            }
            if (result.equals(nextLeft)) {
                nextLeft = null;
            }
            if (result.equals(nextRight)) {
                nextRight = null;
            }
            return result;
        }
    }

    private static boolean isDuplicateKey(Throwable e) {
        return e instanceof MongoServerException server && ErrorCategory.fromErrorCode(server.getCode()) == ErrorCategory.DUPLICATE_KEY;
    }

    private static void closeQuietly(MongoCursor<Document> cursor) {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (RuntimeException ignored) {
                // closing a cursor that failed is best effort
            }
        }
    }

    private static IOException asIOException(Exception e) {
        if (e instanceof IOException io) {
            return io;
        }
        return new IOException("gridfs blob backend failed: " + e.getMessage(), e);
    }

    /**
     * GridFS reads throw unchecked {@link MongoException}s; callers expect
     * {@link IOException}.
     */
    private static final class MongoErrorTranslatingInputStream extends FilterInputStream {

        MongoErrorTranslatingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (MongoException e) {
                throw asIOException(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (MongoException e) {
                throw asIOException(e);
            }
        }

        @Override
        public long skip(long n) throws IOException {
            try {
                return in.skip(n);
            } catch (MongoException e) {
                throw asIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } catch (MongoException e) {
                throw asIOException(e);
            }
        }
    }
}
