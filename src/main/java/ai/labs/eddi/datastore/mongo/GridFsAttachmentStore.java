/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.MimeValidator;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * MongoDB GridFS implementation of {@link IAttachmentStore}.
 * <p>
 * Uses GridFS for storing binary attachments with metadata. Files are stored in
 * the {@code attachments} GridFS bucket.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class GridFsAttachmentStore implements IAttachmentStore {

    private static final Logger LOGGER = Logger.getLogger(GridFsAttachmentStore.class);
    private static final String BUCKET_NAME = "attachments";

    private final GridFSBucket gridFSBucket;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520") // 20 MB
    long maxSizeBytes;

    @Inject
    public GridFsAttachmentStore(MongoDatabase database) {
        this.gridFSBucket = GridFSBuckets.create(database, BUCKET_NAME);
    }

    @Override
    public Attachment store(byte[] bytes, String declaredMime, String filename,
                            String conversationId, String tenantId)
            throws AttachmentStoreException {

        if (bytes == null || bytes.length == 0) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        if (bytes.length > maxSizeBytes) {
            throw new AttachmentStoreException(
                    "Attachment exceeds max size: %d bytes (limit: %d)".formatted(bytes.length, maxSizeBytes));
        }

        // MIME validation
        String detectedMime = MimeValidator.detectMime(bytes);
        if (!MimeValidator.isCompatible(declaredMime, detectedMime)) {
            throw new AttachmentStoreException(
                    "MIME type mismatch: declared='%s', detected='%s'".formatted(declaredMime, detectedMime));
        }

        String resolvedMime = MimeValidator.normalize(declaredMime != null ? declaredMime : detectedMime);

        Document metadata = new Document()
                .append("conversationId", conversationId)
                .append("tenantId", tenantId)
                .append("mimeType", resolvedMime)
                .append("sizeBytes", (long) bytes.length);

        GridFSUploadOptions options = new GridFSUploadOptions()
                .metadata(metadata);

        ObjectId fileId = gridFSBucket.uploadFromStream(
                filename != null ? filename : "unnamed",
                new ByteArrayInputStream(bytes),
                options);

        String storageRef = fileId.toHexString();
        LOGGER.debugf("Stored attachment '%s' (%s, %d bytes) for conversation '%s' → GridFS %s",
                sanitize(filename), resolvedMime, bytes.length, sanitize(conversationId), storageRef);

        return new Attachment(storageRef, filename, resolvedMime, bytes.length, conversationId);
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        try {
            ObjectId fileId = new ObjectId(storageRef);
            GridFSFile file = gridFSBucket.find(Filters.eq("_id", fileId)).first();

            if (file == null) {
                throw new AttachmentStoreException("Attachment not found: " + storageRef);
            }

            // Check conversation ownership
            Document metadata = file.getMetadata();
            if (metadata != null) {
                String ownerConv = metadata.getString("conversationId");
                if (ownerConv != null && !ownerConv.equals(requestingConversationId)) {
                    throw new AttachmentStoreException(
                            "Cross-conversation access denied: attachment belongs to '%s', requested from '%s'"
                                    .formatted(ownerConv, requestingConversationId));
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            gridFSBucket.downloadToStream(fileId, out);
            return out.toByteArray();
        } catch (IllegalArgumentException e) {
            throw new AttachmentStoreException("Invalid storage reference: " + storageRef, e);
        }
    }

    @Override
    public long deleteByConversation(String conversationId) {
        long count = 0;
        for (GridFSFile file : gridFSBucket.find(Filters.eq("metadata.conversationId", conversationId))) {
            gridFSBucket.delete(file.getObjectId());
            count++;
        }
        if (count > 0) {
            LOGGER.debugf("Deleted %d attachments for conversation '%s'", count, sanitize(conversationId));
        }
        return count;
    }

    @Override
    public List<Attachment> listByConversation(String conversationId) {
        List<Attachment> results = new ArrayList<>();
        for (GridFSFile file : gridFSBucket.find(Filters.eq("metadata.conversationId", conversationId))) {
            Document metadata = file.getMetadata();
            results.add(new Attachment(
                    file.getObjectId().toHexString(),
                    file.getFilename(),
                    metadata != null ? metadata.getString("mimeType") : "application/octet-stream",
                    file.getLength(),
                    conversationId));
        }
        return results;
    }
}
