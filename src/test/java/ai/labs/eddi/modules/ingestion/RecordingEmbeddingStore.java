/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A vector store that remembers what was added and removed.
 *
 * <p>
 * Behaves like a real store for the one operation that matters to ingestion
 * correctness — {@code removeAll(Filter)} on a metadata key actually removes
 * the matching segments — so a test can assert that re-ingesting a document
 * <em>replaced</em> its chunks rather than appending a second copy beside them.
 */
public final class RecordingEmbeddingStore implements EmbeddingStore<TextSegment> {

    private final List<TextSegment> segments = new ArrayList<>();
    private final List<Filter> removeFilters = new ArrayList<>();
    private boolean removalSupported = true;
    private boolean removalFails;

    /** Simulates a backend whose driver cannot delete by metadata. */
    public RecordingEmbeddingStore withoutRemovalSupport() {
        this.removalSupported = false;
        return this;
    }

    /** Simulates a store that accepts deletes and fails them — a sick backend. */
    public RecordingEmbeddingStore withFailingRemoval() {
        this.removalFails = true;
        return this;
    }

    public List<TextSegment> segments() {
        return segments;
    }

    public List<Filter> removeFilters() {
        return removeFilters;
    }

    /** Segments currently stored for a document. */
    public List<TextSegment> segmentsOf(String documentId) {
        return segments.stream()
                .filter(segment -> documentId.equals(segment.metadata().getString(
                        IngestionPipeline.METADATA_DOCUMENT_ID)))
                .toList();
    }

    public List<String> textsOf(String documentId) {
        return segmentsOf(documentId).stream().map(TextSegment::text).toList();
    }

    @Override
    public String add(Embedding embedding) {
        return UUID.randomUUID().toString();
    }

    @Override
    public void add(String id, Embedding embedding) {
        // Not used by the pipeline.
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        segments.add(textSegment);
        return UUID.randomUUID().toString();
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return embeddings.stream().map(embedding -> UUID.randomUUID().toString()).toList();
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        segments.addAll(textSegments);
        return textSegments.stream().map(segment -> UUID.randomUUID().toString()).toList();
    }

    @Override
    public void removeAll(Filter filter) {
        if (!removalSupported) {
            throw new UnsupportedFeatureException("Not supported yet.");
        }
        if (removalFails) {
            throw new IllegalStateException("vector store is unwell");
        }
        removeFilters.add(filter);
        // Evaluate the filter itself rather than pattern-matching one shape of it.
        // Matching only IsEqualTo made every compound filter a silent no-op here,
        // while the real stores honoured it — so the double disagreed with
        // production exactly where replacement correctness is decided.
        segments.removeIf(segment -> filter.test(segment.metadata()));
    }

    @Override
    public void removeAll(Collection<String> ids) {
        throw new UnsupportedFeatureException("Not used by the ingestion pipeline.");
    }

    @Override
    public void removeAll() {
        segments.clear();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return new EmbeddingSearchResult<>(List.of());
    }
}
