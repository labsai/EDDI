/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import java.util.Map;
import java.util.Set;

/**
 * Text prepared by server code for a document index, with the metadata that
 * locates it in its document (unit, pages, kind). Chunked by the index's RAG
 * configuration and embedded by {@link RagIngestionService#ingestPrepared};
 * each chunk carries a copy of the metadata.
 *
 * <p>
 * The keys that decide whose a chunk is and which index it belongs to —
 * {@link #RESERVED_KEYS} — are set by {@code ingestPrepared} itself and may not
 * appear here, so no prepared text can claim another owner or another index.
 * Values are strings, integers, longs or doubles, the types every vector store
 * can hold as metadata.
 *
 * @param text
 *            the text; must not be blank
 * @param metadata
 *            locating metadata, or {@code null} for none
 */
public record PreparedSegment(String text, Map<String, Object> metadata) {

    /**
     * Metadata keys a prepared segment may not set: the chunk's tag, owner and
     * document, plus the keys of the knowledge base's own text and source
     * ingestion, whose deletes match on them.
     */
    public static final Set<String> RESERVED_KEYS = Set.of("kbId", "sourceKey", "documentId", "ingestionId", "source", "sourceName",
            "runId", "index");

    public PreparedSegment {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A prepared segment needs text");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (RESERVED_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("Metadata key '" + entry.getKey() + "' is set by the index, not by a prepared segment");
            }
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Double)) {
                throw new IllegalArgumentException("Metadata '" + entry.getKey() + "' must be a string, integer, long or double");
            }
        }
    }
}
