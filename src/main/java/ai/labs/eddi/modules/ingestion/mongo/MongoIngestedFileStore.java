/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.mongo;

import ai.labs.eddi.modules.ingestion.ContentHashes;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.IngestedFileIds;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MongoDB GridFS implementation of {@link IIngestedFileStore}.
 *
 * <p>
 * GridFS rather than a plain document because a document is capped at 16 MB and
 * an uploaded manual is routinely larger. The bucket is separate from the
 * conversation attachment bucket: these files belong to a knowledge base, they
 * outlive every conversation, and conversation erasure must not reach them.
 */
@ApplicationScoped
@DefaultBean
public class MongoIngestedFileStore implements IIngestedFileStore {

    static final String BUCKET_NAME = "rag_ingested_files";

    private static final String META_SOURCE_KEY = "sourceKey";
    private static final String META_FILE_ID = "fileId";
    private static final String META_MIME_TYPE = "mimeType";
    private static final String META_CONTENT_HASH = "contentHash";

    private final GridFSBucket bucket;
    private final MongoCollection<Document> files;

    @Inject
    public MongoIngestedFileStore(MongoDatabase database) {
        this.bucket = GridFSBuckets.create(database, BUCKET_NAME);
        this.files = database.getCollection(BUCKET_NAME + ".files");
        // Every query here filters on these two. Without the index each one is a
        // collection scan of every file every knowledge base has ever been given.
        this.files.createIndex(
                Indexes.ascending("metadata." + META_SOURCE_KEY, "metadata." + META_FILE_ID),
                new IndexOptions().name("rag_ingested_files_source_file"));
    }

    @Override
    public StoredFile store(String sourceKey, String fileName, String mimeType, byte[] content) {
        String fileId = IngestedFileIds.forFileName(fileName);
        String hash = ContentHashes.sha256Bytes(content);
        Document metadata = new Document(META_SOURCE_KEY, sourceKey)
                .append(META_FILE_ID, fileId)
                .append(META_MIME_TYPE, mimeType)
                .append(META_CONTENT_HASH, hash);

        try {
            // What is already there is noted first, then the replacement is written,
            // then only those earlier copies are dropped. Writing first means a
            // failure in between leaves the previous file readable rather than
            // leaving the source with a document row and no bytes behind it; deleting
            // a snapshot rather than "everything that is not mine" means two uploads
            // of the same name racing leave a spare copy instead of leaving none.
            Set<ObjectId> previous = objectIdsOf(byFileId(sourceKey, fileId));
            bucket.uploadFromStream(fileName, new ByteArrayInputStream(content),
                    new GridFSUploadOptions().metadata(metadata));
            previous.forEach(bucket::delete);
        } catch (RuntimeException e) {
            throw new IngestedFileStoreException("Could not store uploaded file '" + fileName + "'", e);
        }
        return new StoredFile(fileId, sourceKey, fileName, mimeType, content.length, hash, Instant.now());
    }

    @Override
    public List<StoredFile> list(String sourceKey) {
        // Keyed by file id so a spare copy left by a racing re-upload is shown once,
        // as the operator's one file, rather than twice.
        Map<String, StoredFile> newestByFileId = new LinkedHashMap<>();
        try {
            for (GridFSFile file : bucket.find(Filters.eq("metadata." + META_SOURCE_KEY, sourceKey))
                    .sort(Sorts.ascending("uploadDate"))) {
                StoredFile stored = toStoredFile(file);
                newestByFileId.put(stored.fileId(), stored);
            }
        } catch (RuntimeException e) {
            throw new IngestedFileStoreException("Could not list uploaded files of source " + sourceKey, e);
        }
        return new ArrayList<>(newestByFileId.values());
    }

    @Override
    public Optional<StoredFile> find(String sourceKey, String fileId) {
        try {
            return Optional.ofNullable(newest(sourceKey, fileId)).map(MongoIngestedFileStore::toStoredFile);
        } catch (RuntimeException e) {
            throw new IngestedFileStoreException("Could not read uploaded file " + fileId, e);
        }
    }

    @Override
    public Optional<byte[]> load(String sourceKey, String fileId) {
        try {
            GridFSFile file = newest(sourceKey, fileId);
            if (file == null) {
                return Optional.empty();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bucket.downloadToStream(file.getObjectId(), out);
            return Optional.of(out.toByteArray());
        } catch (RuntimeException e) {
            throw new IngestedFileStoreException("Could not read uploaded file " + fileId, e);
        }
    }

    @Override
    public boolean delete(String sourceKey, String fileId) {
        boolean deleted = false;
        try {
            for (GridFSFile file : bucket.find(byFileId(sourceKey, fileId))) {
                bucket.delete(file.getObjectId());
                deleted = true;
            }
        } catch (RuntimeException e) {
            throw new IngestedFileStoreException("Could not delete uploaded file " + fileId, e);
        }
        return deleted;
    }

    @Override
    public long deleteAll(String sourceKey) {
        long deleted = 0;
        try {
            for (GridFSFile file : bucket.find(Filters.eq("metadata." + META_SOURCE_KEY, sourceKey))) {
                bucket.delete(file.getObjectId());
                deleted++;
            }
        } catch (RuntimeException e) {
            throw new IngestedFileStoreException("Could not delete the uploaded files of source " + sourceKey, e);
        }
        return deleted;
    }

    @Override
    public Usage usage(String sourceKey) {
        try {
            // Aggregated in the database: a source at its 500-file limit would
            // otherwise stream every file's metadata across the wire on every upload.
            Document totals = files.aggregate(List.of(
                    new Document("$match", new Document("metadata." + META_SOURCE_KEY, sourceKey)),
                    new Document("$group", new Document("_id", null)
                            .append("fileCount", new Document("$sum", 1))
                            .append("totalBytes", new Document("$sum", "$length")))))
                    .first();
            if (totals == null) {
                return Usage.empty();
            }
            return new Usage(totals.getInteger("fileCount", 0), toLong(totals.get("totalBytes")));
        } catch (RuntimeException e) {
            throw new IngestedFileStoreException("Could not measure the uploaded files of source " + sourceKey, e);
        }
    }

    /** The current copy: the last one written wins. */
    private GridFSFile newest(String sourceKey, String fileId) {
        return bucket.find(byFileId(sourceKey, fileId)).sort(Sorts.descending("uploadDate")).first();
    }

    private Set<ObjectId> objectIdsOf(Bson filter) {
        return bucket.find(filter).into(new ArrayList<>()).stream()
                .map(GridFSFile::getObjectId)
                .collect(Collectors.toSet());
    }

    private static Bson byFileId(String sourceKey, String fileId) {
        return Filters.and(
                Filters.eq("metadata." + META_SOURCE_KEY, sourceKey),
                Filters.eq("metadata." + META_FILE_ID, fileId));
    }

    private static StoredFile toStoredFile(GridFSFile file) {
        Document metadata = file.getMetadata() == null ? new Document() : file.getMetadata();
        Date uploaded = file.getUploadDate();
        return new StoredFile(
                metadata.getString(META_FILE_ID),
                metadata.getString(META_SOURCE_KEY),
                file.getFilename(),
                metadata.getString(META_MIME_TYPE),
                file.getLength(),
                metadata.getString(META_CONTENT_HASH),
                uploaded == null ? Instant.EPOCH : uploaded.toInstant());
    }

    /**
     * {@code $sum} answers with whichever numeric type fits; the total is bytes.
     */
    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
