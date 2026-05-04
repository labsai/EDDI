/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Content hash tracker for deduplication and stale marking in ingestion.
 * <p>
 * Uses MongoDB collection {@code rag_ingestion_hashes} to track content hashes
 * per document per ingestion source. When content is re-ingested:
 * <ul>
 * <li>If hash unchanged → skip (unchanged document)</li>
 * <li>If hash changed → re-ingest and update hash</li>
 * <li>If document no longer present → mark as stale</li>
 * </ul>
 *
 * @since 6.0.3
 */
@ApplicationScoped
public class ContentHashTracker {

    private static final Logger LOGGER = Logger.getLogger(ContentHashTracker.class);
    private static final String COLLECTION_NAME = "rag_ingestion_hashes";

    private final MongoClient mongoClient;
    private MongoCollection<Document> collection;

    @Inject
    public ContentHashTracker(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    @PostConstruct
    void init() {
        this.collection = mongoClient.getDatabase("eddi").getCollection(COLLECTION_NAME);
        createIndexes();
    }

    private void createIndexes() {
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
        String hash = computeHash(content);

        Document existing = collection.find(
                Filters.and(
                        Filters.eq("sourceId", sourceId),
                        Filters.eq("documentId", documentId)))
                .first();

        if (existing == null) {
            // New document
            Document doc = new Document()
                    .append("sourceId", sourceId)
                    .append("documentId", documentId)
                    .append("hash", hash)
                    .append("stale", false)
                    .append("ingestedAt", Instant.now())
                    .append("updatedAt", Instant.now());
            collection.insertOne(doc);
            return true;
        }

        String existingHash = existing.getString("hash");
        if (hash.equals(existingHash)) {
            // Unchanged - update ingestedAt but don't ingest
            collection.updateOne(
                    Filters.and(
                            Filters.eq("sourceId", sourceId),
                            Filters.eq("documentId", documentId)),
                    Updates.combine(
                            Updates.set("ingestedAt", Instant.now()),
                            Updates.set("stale", false) // Clear stale flag if document reappears
                    ));
            return false;
        }

        // Changed - update hash and re-ingest
        collection.updateOne(
                Filters.and(
                        Filters.eq("sourceId", sourceId),
                        Filters.eq("documentId", documentId)),
                Updates.combine(
                        Updates.set("hash", hash),
                        Updates.set("ingestedAt", Instant.now()),
                        Updates.set("updatedAt", Instant.now()),
                        Updates.set("stale", false)));
        return true;
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
        // Find all non-stale entries for this source that are NOT in documentIds
        List<String> normalizedIds = documentIds.stream()
                .map(this::normalizeId)
                .toList();

        Bson filter = Filters.and(
                Filters.eq("sourceId", sourceId),
                Filters.eq("stale", false),
                Filters.nin("documentId", normalizedIds));

        var result = collection.updateMany(
                filter,
                Updates.combine(
                        Updates.set("stale", true),
                        Updates.set("staleAt", Instant.now())));

        int markedStale = (int) result.getModifiedCount();
        if (markedStale > 0) {
            LOGGER.infof("Marked %d documents as stale for source '%s'", markedStale, sanitize(sourceId));
        }
        return markedStale;
    }

    /**
     * Gets all tracked document IDs for a source, including stale ones.
     *
     * @param sourceId
     *            the ingestion source ID
     * @return map of documentId to hash entry
     */
    public Map<String, HashEntry> getTrackedDocuments(String sourceId) {
        Map<String, HashEntry> result = new HashMap<>();

        for (Document doc : collection.find(Filters.eq("sourceId", sourceId))) {
            String documentId = doc.getString("documentId");
            String hash = doc.getString("hash");
            boolean stale = doc.getBoolean("stale", false);
            Instant ingestedAt = doc.getDate("ingestedAt") != null
                    ? doc.getDate("ingestedAt").toInstant()
                    : null;
            result.put(documentId, new HashEntry(documentId, hash, stale, ingestedAt));
        }

        return result;
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

    /**
     * A tracked hash entry.
     *
     * @param documentId
     *            the document identifier
     * @param hash
     *            the content hash
     * @param stale
     *            whether the document is stale
     * @param ingestedAt
     *            when the document was last ingested
     */
    public record HashEntry(String documentId, String hash, boolean stale, Instant ingestedAt) {
    }

    /**
     * Result of deduplication processing.
     *
     * @param newDocuments
     *            newly discovered documents
     * @param updatedDocuments
     *            documents with changed content
     * @param unchangedIds
     *            documents with unchanged content
     * @param staleDocumentsMarked
     *            number of documents marked stale
     */
    public record DedupResult(
            List<DocumentToProcess> newDocuments,
            List<DocumentToProcess> updatedDocuments,
            List<String> unchangedIds,
            int staleDocumentsMarked) {
    }

    /**
     * A document that needs to be processed (new or updated).
     *
     * @param id
     *            the document identifier
     * @param title
     *            the document title
     * @param markdown
     *            the markdown content
     * @param contentHash
     *            the content hash
     */
    public record DocumentToProcess(String id, String title, String markdown, String contentHash) {
    }
}
