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
 * Content hash tracker for deduplication and stale marking in web crawling.
 * <p>
 * Uses MongoDB collection {@code rag_ingestion_hashes} to track content hashes
 * per URL per ingestion source. When a page is re-crawled:
 * <ul>
 * <li>If hash unchanged → skip (unchanged page)</li>
 * <li>If hash changed → re-ingest and update hash</li>
 * <li>If URL no longer present in crawl → mark as stale</li>
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
        // Compound index on sourceId + url for efficient lookups
        collection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("sourceId"),
                        Indexes.ascending("url")),
                new IndexOptions().unique(true));

        // Index on sourceId for clearing operations
        collection.createIndex(Indexes.ascending("sourceId"));

        // Index on stale field for cleanup queries
        collection.createIndex(Indexes.ascending("stale"));

        LOGGER.info("Content hash tracker indexes created");
    }

    /**
     * Checks if a page should be ingested (new or changed content).
     *
     * @param sourceId
     *            the ingestion source ID
     * @param url
     *            the page URL
     * @param content
     *            the page content (Markdown)
     * @return true if content is new or changed, false if unchanged
     */
    public boolean shouldIngest(String sourceId, String url, String content) {
        String hash = computeHash(content);

        Document existing = collection.find(
                Filters.and(
                        Filters.eq("sourceId", sourceId),
                        Filters.eq("url", url)))
                .first();

        if (existing == null) {
            // New page
            Document doc = new Document()
                    .append("sourceId", sourceId)
                    .append("url", url)
                    .append("hash", hash)
                    .append("stale", false)
                    .append("crawledAt", Instant.now())
                    .append("updatedAt", Instant.now());
            collection.insertOne(doc);
            return true;
        }

        String existingHash = existing.getString("hash");
        if (hash.equals(existingHash)) {
            // Unchanged - update crawledAt but don't ingest
            collection.updateOne(
                    Filters.and(
                            Filters.eq("sourceId", sourceId),
                            Filters.eq("url", url)),
                    Updates.combine(
                            Updates.set("crawledAt", Instant.now()),
                            Updates.set("stale", false) // Clear stale flag if page reappears
                    ));
            return false;
        }

        // Changed - update hash and re-ingest
        collection.updateOne(
                Filters.and(
                        Filters.eq("sourceId", sourceId),
                        Filters.eq("url", url)),
                Updates.combine(
                        Updates.set("hash", hash),
                        Updates.set("crawledAt", Instant.now()),
                        Updates.set("updatedAt", Instant.now()),
                        Updates.set("stale", false)));
        return true;
    }

    /**
     * Marks pages that were not in the current crawl as stale.
     *
     * @param sourceId
     *            the ingestion source ID
     * @param crawledUrls
     *            the URLs that were successfully crawled this run
     * @return the number of pages marked stale
     */
    public int markStalePages(String sourceId, List<String> crawledUrls) {
        // Find all non-stale entries for this source that are NOT in crawledUrls
        List<String> normalizedUrls = crawledUrls.stream()
                .map(this::normalizeUrl)
                .toList();

        Bson filter = Filters.and(
                Filters.eq("sourceId", sourceId),
                Filters.eq("stale", false),
                Filters.nin("url", normalizedUrls));

        var result = collection.updateMany(
                filter,
                Updates.combine(
                        Updates.set("stale", true),
                        Updates.set("staleAt", Instant.now())));

        int markedStale = (int) result.getModifiedCount();
        if (markedStale > 0) {
            LOGGER.infof("Marked %d pages as stale for source '%s'", markedStale, sanitize(sourceId));
        }
        return markedStale;
    }

    /**
     * Gets all tracked URLs for a source, including stale ones.
     *
     * @param sourceId
     *            the ingestion source ID
     * @return map of URL to hash entry
     */
    public Map<String, HashEntry> getTrackedUrls(String sourceId) {
        Map<String, HashEntry> result = new HashMap<>();

        for (Document doc : collection.find(Filters.eq("sourceId", sourceId))) {
            String url = doc.getString("url");
            String hash = doc.getString("hash");
            boolean stale = doc.getBoolean("stale", false);
            Instant crawledAt = doc.getDate("crawledAt") != null
                    ? doc.getDate("crawledAt").toInstant()
                    : null;
            result.put(url, new HashEntry(url, hash, stale, crawledAt));
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

    private String normalizeUrl(String url) {
        // Normalize URL for comparison (remove trailing slash, fragment)
        if (url == null) {
            return "";
        }
        String normalized = url;
        // Remove fragment
        int hashIdx = normalized.indexOf('#');
        if (hashIdx >= 0) {
            normalized = normalized.substring(0, hashIdx);
        }
        // Remove trailing slash
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * A tracked hash entry.
     */
    public record HashEntry(String url, String hash, boolean stale, Instant crawledAt) {
    }

    /**
     * Result of deduplication processing.
     */
    public record DedupResult(
            List<PageToProcess> newPages,
            List<PageToProcess> updatedPages,
            List<String> unchangedUrls,
            int stalePagesMarked) {
    }

    /**
     * A page that needs to be processed (new or updated).
     */
    public record PageToProcess(String url, String title, String markdown, String contentHash) {
    }
}
