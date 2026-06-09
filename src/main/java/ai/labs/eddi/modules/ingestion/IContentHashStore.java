/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import java.util.List;

/**
 * Service for tracking content hashes for deduplication and stale marking in
 * the RAG ingestion pipeline.
 * <p>
 * Implementations must be thread-safe and handle concurrent
 * {@link #shouldIngest(String, String, String)} calls for the same
 * {@code (sourceId, documentId)} pair atomically.
 *
 * @since 6.0.3
 */
public interface IContentHashStore {

    /**
     * Checks if a document should be ingested (new or changed content).
     */
    boolean shouldIngest(String sourceId, String documentId, String content);

    /**
     * Marks documents that were not in the current fetch as stale.
     *
     * @return the number of documents marked stale
     */
    int markStaleDocuments(String sourceId, List<String> documentIds);

    /**
     * Clears all hash tracking for a source.
     */
    void clearSource(String sourceId);

    /**
     * Computes SHA-256 hash of content.
     */
    String computeHash(String content);

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
    record DocumentToProcess(String id, String title, String markdown, String contentHash) {
    }
}
