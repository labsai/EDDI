/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.arc.DefaultBean;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * MongoDB-backed {@link IContentHashStore} that tracks content hashes in the
 * {@code rag_ingestion_hashes} collection.
 * <p>
 * Uses MongoDB's {@code findOneAndUpdate} with upsert and
 * {@code ReturnDocument.BEFORE} for atomic deduplication.
 *
 * @since 6.0.3
 */
@ApplicationScoped
@DefaultBean
public class MongoContentHashStore implements IContentHashStore {

    private static final Logger LOGGER = Logger.getLogger(MongoContentHashStore.class);
    private static final String COLLECTION_NAME = "rag_ingestion_hashes";

    private MongoCollection<Document> collection;

    @Inject
    public MongoContentHashStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION_NAME);
        createIndexes();
    }

    public void createIndexes() {
        // Compound index on sourceId + documentId for efficient lookups
        collection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("sourceId"),
                        Indexes.ascending("documentId")),
                new IndexOptions().unique(true));

        // Index on sourceId for clearing operations
        collection.createIndex(Indexes.ascending("sourceId"));

        // Index on stale field for cleanup queries
        collection.createIndex(Indexes.ascending("stale"));

        LOGGER.info("Content hash tracker indexes created");
    }

    /**
     * Checks if a document should be ingested (new or changed content).
     *
     * @param sourceId
     *            the ingestion source ID
     * @param documentId
     *            the document identifier (URL, path, etc.)
     * @param content
     *            the document content (Markdown)
     * @return true if content is new or changed, false if unchanged
     */
    public boolean shouldIngest(String sourceId, String documentId, String content) {
        if (documentId == null || documentId.isEmpty()) {
            LOGGER.warn("Skipping document with null/empty ID");
            return false;
        }
        if (content == null || content.isEmpty()) {
            LOGGER.warnf("Skipping document with null/empty content: %s", sanitize(documentId));
            return false;
        }
        String hash = computeHash(content);
        Instant now = Instant.now();

        Bson filter = Filters.and(
                Filters.eq("sourceId", sourceId),
                Filters.eq("documentId", documentId));

        Bson update = Updates.combine(
                Updates.set("hash", hash),
                Updates.set("ingestedAt", now),
                Updates.set("updatedAt", now),
                Updates.set("stale", false),
                Updates.setOnInsert("sourceId", sourceId),
                Updates.setOnInsert("documentId", documentId));

        FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.BEFORE);

        Document before = collection.findOneAndUpdate(filter, update, options);

        // If before is null, the document was just created (upserted)
        return before == null || !hash.equals(before.getString("hash"));
    }

    /**
     * Marks documents that were not in the current fetch as stale.
     *
     * @param sourceId
     *            the ingestion source ID
     * @param documentIds
     *            the document IDs that were successfully fetched this run
     * @return the number of documents marked stale
     */
    public int markStaleDocuments(String sourceId, List<String> documentIds) {
        List<String> normalizedIds = documentIds.stream()
                .map(this::normalizeId)
                .filter(id -> !id.isEmpty())
                .toList();

        if (normalizedIds.isEmpty()) {
            LOGGER.debugf("All document IDs were empty after normalization for source '%s' — skipping stale marking", sanitize(sourceId));
            return 0;
        }

        Bson filter = Filters.and(
                Filters.eq("sourceId", sourceId),
                Filters.eq("stale", false),
                Filters.nin("documentId", normalizedIds));

        var result = collection.updateMany(
                filter,
                new Document("$set", new Document()
                        .append("stale", true)
                        .append("staleAt", Instant.now().toString())));

        int markedStale = (int) result.getModifiedCount();
        if (markedStale > 0) {
            LOGGER.infof("Marked %d documents as stale for source '%s'", markedStale, sanitize(sourceId));
        }
        return markedStale;
    }

    /**
     * Clears all hash tracking for a source. Call when deleting an ingestion
     * source.
     *
     * @param sourceId
     *            the ingestion source ID
     */
    public void clearSource(String sourceId) {
        collection.deleteMany(Filters.eq("sourceId", sourceId));
        LOGGER.infof("Cleared hash tracking for source '%s'", sanitize(sourceId));
    }

    /**
     * Computes SHA-256 hash of content.
     *
     * @param content
     *            the content to hash
     * @return hexadecimal hash string
     */
    public String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String normalizeId(String id) {
        // Normalize ID for comparison (remove trailing slash for URLs, fragment)
        if (id == null) {
            return "";
        }
        String normalized = id;
        // Remove fragment (for URLs)
        int hashIdx = normalized.indexOf('#');
        if (hashIdx >= 0) {
            normalized = normalized.substring(0, hashIdx);
        }
        // Remove trailing slash (for URLs/paths)
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
