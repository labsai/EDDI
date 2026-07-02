/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.MimeValidator;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * MongoDB GridFS implementation of {@link IAttachmentStore}.
 * <p>
 * Files are stored in the {@code attachments} GridFS bucket. The public
 * {@code storageRef} is a random UUID kept in the file metadata (not the raw
 * ObjectId), so references are unguessable; legacy blobs referenced by their
 * plain ObjectId hex still resolve. Access grants are stored as a
 * {@code metadata.grants} array and die with the blob.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class GridFsAttachmentStore implements IAttachmentStore {

    private static final Logger LOGGER = Logger.getLogger(GridFsAttachmentStore.class);
    private static final String BUCKET_NAME = "attachments";
    private static final String META_CONVERSATION_ID = "conversationId";
    private static final String META_STORAGE_REF = "storageRef";
    private static final String META_MIME_TYPE = "mimeType";
    private static final String META_GRANTS = "grants";

    private final GridFSBucket gridFSBucket;
    private final MongoCollection<Document> filesCollection;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520") // 20 MB
    long maxSizeBytes;

    @ConfigProperty(name = "eddi.attachments.max-per-conversation", defaultValue = "50")
    long maxPerConversation;

    @ConfigProperty(name = "eddi.attachments.max-total-bytes-per-conversation", defaultValue = "104857600") // 100 MB
    long maxTotalBytesPerConversation;

    @Inject
    public GridFsAttachmentStore(MongoDatabase database) {
        this.gridFSBucket = GridFSBuckets.create(database, BUCKET_NAME);
        this.filesCollection = database.getCollection(BUCKET_NAME + ".files");
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

        enforceQuota(conversationId, bytes.length);

        String resolvedMime = MimeValidator.normalize(declaredMime != null ? declaredMime : detectedMime);
        String storageRef = UUID.randomUUID().toString();

        Document metadata = new Document()
                .append(META_CONVERSATION_ID, conversationId)
                .append("tenantId", tenantId)
                .append(META_MIME_TYPE, resolvedMime)
                .append("sizeBytes", (long) bytes.length)
                .append(META_STORAGE_REF, storageRef)
                .append(META_GRANTS, new ArrayList<String>());

        GridFSUploadOptions options = new GridFSUploadOptions().metadata(metadata);

        gridFSBucket.uploadFromStream(
                filename != null ? filename : "unnamed",
                new ByteArrayInputStream(bytes),
                options);

        LOGGER.debugf("Stored attachment '%s' (%s, %d bytes) for conversation '%s' → GridFS %s",
                sanitize(filename), resolvedMime, bytes.length, sanitize(conversationId), storageRef);

        return new Attachment(storageRef, filename, resolvedMime, bytes.length, conversationId);
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentStoreException("Attachment not found: " + storageRef);
        }
        authorize(file, requestingConversationId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        gridFSBucket.downloadToStream(file.getObjectId(), out);
        return out.toByteArray();
    }

    @Override
    public Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentStoreException("Attachment not found: " + storageRef);
        }
        authorize(file, requestingConversationId);

        Document metadata = file.getMetadata();
        String mime = metadata != null ? metadata.getString(META_MIME_TYPE) : null;
        String owner = metadata != null ? metadata.getString(META_CONVERSATION_ID) : null;
        String ref = metadata != null && metadata.getString(META_STORAGE_REF) != null
                ? metadata.getString(META_STORAGE_REF)
                : storageRef;
        return new Attachment(ref, file.getFilename(),
                mime != null ? mime : "application/octet-stream", file.getLength(), owner);
    }

    @Override
    public void grantAccess(String storageRef, String conversationId) throws AttachmentStoreException {
        var result = filesCollection.updateOne(refFilter(storageRef),
                Updates.addToSet("metadata." + META_GRANTS, conversationId));
        if (result.getMatchedCount() == 0) {
            throw new AttachmentStoreException("Attachment not found: " + storageRef);
        }
        LOGGER.debugf("Granted conversation '%s' access to attachment %s",
                sanitize(conversationId), storageRef);
    }

    @Override
    public boolean delete(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            return false;
        }
        Document metadata = file.getMetadata();
        String owner = metadata != null ? metadata.getString(META_CONVERSATION_ID) : null;
        if (owner != null && !owner.equals(requestingConversationId)) {
            throw new AttachmentStoreException(
                    "Delete denied: attachment belongs to '%s', requested from '%s'"
                            .formatted(owner, requestingConversationId));
        }
        gridFSBucket.delete(file.getObjectId());
        return true;
    }

    @Override
    public long deleteByConversation(String conversationId) {
        long count = 0;
        for (GridFSFile file : gridFSBucket.find(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId))) {
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
        for (GridFSFile file : gridFSBucket.find(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId))) {
            Document metadata = file.getMetadata();
            String ref = metadata != null && metadata.getString(META_STORAGE_REF) != null
                    ? metadata.getString(META_STORAGE_REF)
                    : file.getObjectId().toHexString();
            results.add(new Attachment(
                    ref,
                    file.getFilename(),
                    metadata != null && metadata.getString(META_MIME_TYPE) != null
                            ? metadata.getString(META_MIME_TYPE)
                            : "application/octet-stream",
                    file.getLength(),
                    conversationId));
        }
        return results;
    }

    private void enforceQuota(String conversationId, long incomingBytes) throws AttachmentStoreException {
        if (maxPerConversation <= 0 && maxTotalBytesPerConversation <= 0) {
            return;
        }
        long count = 0;
        long totalBytes = 0;
        for (GridFSFile file : gridFSBucket.find(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId))) {
            count++;
            totalBytes += file.getLength();
        }
        if (maxPerConversation > 0 && count >= maxPerConversation) {
            throw new AttachmentStoreException(
                    "Attachment quota exceeded for conversation: %d/%d files. Delete some attachments first."
                            .formatted(count, maxPerConversation));
        }
        if (maxTotalBytesPerConversation > 0 && totalBytes + incomingBytes > maxTotalBytesPerConversation) {
            throw new AttachmentStoreException(
                    "Attachment storage quota exceeded for conversation: %d + %d bytes exceeds limit of %d. Delete some attachments first."
                            .formatted(totalBytes, incomingBytes, maxTotalBytesPerConversation));
        }
    }

    private GridFSFile findFileByRef(String storageRef) {
        return gridFSBucket.find(refFilter(storageRef)).first();
    }

    private Bson refFilter(String storageRef) {
        if (ObjectId.isValid(storageRef)) {
            // Match the modern UUID metadata ref or a legacy plain-ObjectId ref.
            return Filters.or(
                    Filters.eq("metadata." + META_STORAGE_REF, storageRef),
                    Filters.eq("_id", new ObjectId(storageRef)));
        }
        return Filters.eq("metadata." + META_STORAGE_REF, storageRef);
    }

    private void authorize(GridFSFile file, String requester) throws AttachmentStoreException {
        Document metadata = file.getMetadata();
        if (metadata == null) {
            return; // legacy blobs without metadata remain accessible
        }
        String owner = metadata.getString(META_CONVERSATION_ID);
        if (owner == null || owner.equals(requester)) {
            return;
        }
        List<String> grants = metadata.getList(META_GRANTS, String.class);
        if (grants != null && grants.contains(requester)) {
            return;
        }
        throw new AttachmentStoreException(
                "Cross-conversation access denied: attachment belongs to '%s', requested from '%s'"
                        .formatted(owner, requester));
    }
}
