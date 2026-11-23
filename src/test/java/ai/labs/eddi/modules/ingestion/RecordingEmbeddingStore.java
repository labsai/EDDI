/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.modules.llm.impl.SegmentMetadataUpdates;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A vector store that remembers what was added and removed.
 *
 * <p>
 * Behaves like a real store for the one operation that matters to ingestion
 * correctness — {@code removeAll(Filter)} on a metadata key actually removes
 * the matching segments — so a test can assert that re-ingesting a document
 * <em>replaced</em> its chunks rather than appending a second copy beside them.
 *
 * <p>
 * Updates metadata in place like the in-memory and pgvector stores do, unless
 * {@link #withoutMetadataUpdates()} makes it refuse the way every other store
 * would.
 */
public final class RecordingEmbeddingStore implements EmbeddingStore<TextSegment>, SegmentMetadataUpdates {

    private final List<TextSegment> segments = new ArrayList<>();
    private final List<Filter> removeFilters = new ArrayList<>();
    private boolean removalSupported = true;
    private boolean removalFails;
    private boolean metadataUpdatesSupported = true;
    private int metadataUpdates;
    private int metadataFailuresLeft;

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

    /** Ends {@link #withFailingRemoval}: the backend is well again. */
    public RecordingEmbeddingStore withWorkingRemoval() {
        this.removalFails = false;
        return this;
    }

    /** Simulates a store that cannot change metadata in place. */
    public RecordingEmbeddingStore withoutMetadataUpdates() {
        this.metadataUpdatesSupported = false;
        return this;
    }

    /** Simulates a store that fails the next metadata update, then recovers. */
    public RecordingEmbeddingStore failNextMetadataUpdate() {
        this.metadataFailuresLeft = 1;
        return this;
    }

    /** How many metadata updates were applied. */
    public int metadataUpdates() {
        return metadataUpdates;
    }

    @Override
    public int updateMetadata(Map<String, String> match, Map<String, String> values) {
        if (!metadataUpdatesSupported) {
            throw new UnsupportedFeatureException("Not supported yet.");
        }
        SegmentMetadataUpdates.requireValid(match, values);
        if (metadataFailuresLeft > 0) {
            metadataFailuresLeft--;
            throw new IllegalStateException("vector store is unwell");
        }
        metadataUpdates++;
        int changed = 0;
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Metadata metadata = segment.metadata();
            boolean matches = match.entrySet().stream()
                    .allMatch(condition -> condition.getValue().equals(metadata.getString(condition.getKey())));
            if (!matches) {
                continue;
            }
            Metadata copy = metadata.copy();
            values.forEach((key, value) -> {
                if (value == null) {
                    copy.remove(key);
                } else {
                    copy.put(key, value);
                }
            });
            segments.set(i, TextSegment.from(segment.text(), copy));
            changed++;
        }
        return changed;
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

    private boolean searchable;

    /**
     * Makes {@link #search} answer every stored segment, in storage order and with
     * score 1, up to the request's maximum — ignoring its filter. Off by default.
     */
    public RecordingEmbeddingStore searchingEverything() {
        this.searchable = true;
        return this;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (!searchable) {
            return new EmbeddingSearchResult<>(List.of());
        }
        return new EmbeddingSearchResult<>(segments.stream().limit(request.maxResults())
                .map(segment -> new EmbeddingMatch<>(1.0, UUID.randomUUID().toString(), null, segment)).toList());
    }
}
