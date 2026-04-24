/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.mongo;

import ai.labs.eddi.engine.memory.IAttachmentStorage;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.io.InputStream;

/**
 * MongoDB GridFS implementation of {@link IAttachmentStorage}.
 * <p>
 * Stores binary attachment payloads in a GridFS bucket named
 * {@code eddi_attachments}. Each file has metadata linking it to the owning
 * conversation for efficient GDPR cleanup via
 * {@link #deleteByConversation(String)}.
 * <p>
 * Annotated {@code @DefaultBean} so it yields to other implementations when
 * alternative profiles are active (e.g., PostgreSQL).
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class MongoAttachmentStorage implements IAttachmentStorage {

    private static final Logger LOGGER = Logger.getLogger(MongoAttachmentStorage.class);
    private static final String BUCKET_NAME = "eddi_attachments";
    private static final String META_CONVERSATION_ID = "conversationId";
    private static final String META_MIME_TYPE = "mimeType";

    private final GridFSBucket gridFSBucket;

    @Inject
    public MongoAttachmentStorage(MongoDatabase database) {
        this.gridFSBucket = GridFSBuckets.create(database, BUCKET_NAME);
    }

    @Override
    public String store(String conversationId, String fileName, String mimeType, InputStream data, long sizeBytes) {
        Document metadata = new Document()
                .append(META_CONVERSATION_ID, conversationId)
                .append(META_MIME_TYPE, mimeType);

        if (sizeBytes > 0) {
            metadata.append("sizeBytes", sizeBytes);
        }

        GridFSUploadOptions options = new GridFSUploadOptions()
                .metadata(metadata);

        ObjectId fileId = gridFSBucket.uploadFromStream(
                fileName != null ? fileName : "unnamed",
                data,
                options);

        String storageRef = "gridfs://" + fileId.toHexString();
        LOGGER.debugf("Stored attachment '%s' (%s, %d bytes) → %s",
                fileName, mimeType, sizeBytes, storageRef);
        return storageRef;
    }

    @Override
    public InputStream load(String storageRef) throws AttachmentNotFoundException {
        ObjectId fileId = parseStorageRef(storageRef);

        // Verify the file exists first
        GridFSFile file = gridFSBucket.find(Filters.eq("_id", fileId)).first();
        if (file == null) {
            throw new AttachmentNotFoundException("No attachment found for storage ref: " + storageRef);
        }

        return gridFSBucket.openDownloadStream(fileId);
    }

    @Override
    public long deleteByConversation(String conversationId) {
        long deleted = 0;

        // Find all files belonging to this conversation
        for (GridFSFile file : gridFSBucket.find(
                Filters.eq("metadata." + META_CONVERSATION_ID, conversationId))) {
            gridFSBucket.delete(file.getObjectId());
            deleted++;
        }

        if (deleted > 0) {
            LOGGER.debugf("Deleted %d attachments for conversation '%s'", deleted, conversationId);
        }
        return deleted;
    }

    /**
     * Parse a storage reference back to a GridFS ObjectId.
     *
     * @param storageRef
     *            format: {@code gridfs://<hex-id>}
     * @throws AttachmentNotFoundException
     *             if the format is invalid
     */
    private static ObjectId parseStorageRef(String storageRef) throws AttachmentNotFoundException {
        if (storageRef == null || !storageRef.startsWith("gridfs://")) {
            throw new AttachmentNotFoundException("Invalid GridFS storage ref: " + storageRef);
        }
        try {
            return new ObjectId(storageRef.substring("gridfs://".length()));
        } catch (IllegalArgumentException e) {
            throw new AttachmentNotFoundException("Invalid ObjectId in storage ref: " + storageRef);
        }
    }
}
