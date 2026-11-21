/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * An {@link InMemoryEmbeddingStore} that asks a deployment-wide guard before
 * every write, and carries the identity of this particular instance.
 * <p>
 * The guard sees every in-memory store at once, which is what lets the cap be
 * on the total rather than per knowledge base: memory is one budget. The check
 * and the write happen under the guard's lock, so concurrent ingestions cannot
 * overshoot it between them.
 * <p>
 * The {@linkplain #epoch() epoch} is created with the instance and never
 * shared. Ingestion state records the epoch it was written against, so a store
 * that was recreated — after a restart, or because its embedding configuration
 * changed — is recognised as empty instead of being trusted to hold what the
 * content hashes say.
 * <p>
 * A store the factory replaces or drops is {@linkplain #retire() retired}: it
 * accepts no further writes. An ingestion run that took the store before the
 * replacement still holds it, and without this its writes would land in a store
 * nothing reads any more, outside the cap, which counts only the stores still
 * registered. Refusing them keeps the cap exact and stops the run with a
 * reason, instead of letting it fill an orphan. The check runs under the same
 * lock as the cap, so no write slips in between the replacement and the flag.
 */
final class CappedInMemoryEmbeddingStore extends InMemoryEmbeddingStore<TextSegment> implements SegmentMetadataUpdates {

    /** Decides whether {@code incoming} more segments may be stored. */
    interface CapacityGuard {

        /** The lock every check-and-write runs under. */
        Object lock();

        /**
         * Throws {@link InMemoryStoreFullException} when the write would take the
         * deployment past its cap. Called with {@link #lock()} held.
         */
        void reserve(String knowledgeBaseId, int incoming);
    }

    private final String knowledgeBaseId;
    private final String epoch = UUID.randomUUID().toString();
    private final CapacityGuard guard;

    /** Guarded by {@code guard.lock()}. */
    private boolean retired;

    CappedInMemoryEmbeddingStore(String knowledgeBaseId, CapacityGuard guard) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.guard = guard;
    }

    /**
     * Refuses every later write. Called by the factory when it replaces or drops
     * this store; what is already stored stays readable to anyone still holding it.
     */
    void retire() {
        synchronized (guard.lock()) {
            retired = true;
        }
    }

    /** Whether {@link #retire()} has been called. */
    boolean isRetired() {
        synchronized (guard.lock()) {
            return retired;
        }
    }

    /** Called with {@code guard.lock()} held. */
    private void admit(int incoming) {
        if (retired) {
            throw new InMemoryStoreRetiredException(knowledgeBaseId);
        }
        guard.reserve(knowledgeBaseId, incoming);
    }

    /** This instance's identity — different for every store ever created. */
    String epoch() {
        return epoch;
    }

    /**
     * The size of the vectors stored here, learned from the writes; 0 while nothing
     * has been stored. Guarded by {@code guard.lock()}.
     */
    private int dimension;

    private void learnDimension(Embedding embedding) {
        if (embedding != null && dimension == 0) {
            dimension = embedding.dimension();
        }
    }

    private void learnDimension(List<Embedding> embeddings) {
        if (embeddings != null && !embeddings.isEmpty()) {
            learnDimension(embeddings.get(0));
        }
    }

    /**
     * Replaces the matching segments with copies carrying the new values, under the
     * same ids and with the same vectors.
     *
     * <p>
     * langchain4j keeps the entries out of reach, and its segments' metadata is a
     * plain map that retrieval reads without a lock, so it is not changed in place.
     * The matching entries are found with a filtered search over all of them — the
     * query vector is irrelevant, the minimum score is 0 and every cosine maps to a
     * score of at least 0 — then removed and added back. Both happen under the
     * cap's lock, and the count does not change, so the cap still holds; a search
     * running between the two misses the document for that instant.
     */
    @Override
    public int updateMetadata(Map<String, String> match, Map<String, String> values) {
        SegmentMetadataUpdates.requireValid(match, values);
        Filter filter = null;
        for (Map.Entry<String, String> condition : match.entrySet()) {
            Filter next = metadataKey(condition.getKey()).isEqualTo(condition.getValue());
            filter = filter == null ? next : filter.and(next);
        }
        synchronized (guard.lock()) {
            if (retired) {
                throw new InMemoryStoreRetiredException(knowledgeBaseId);
            }
            if (dimension == 0 || isEmpty()) {
                return 0;
            }
            float[] probe = new float[dimension];
            Arrays.fill(probe, 1f);
            List<EmbeddingMatch<TextSegment>> found = search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(probe))
                    .filter(filter)
                    .maxResults(Integer.MAX_VALUE)
                    .minScore(0.0)
                    .build()).matches();
            List<String> ids = new ArrayList<>(found.size());
            List<Embedding> embeddings = new ArrayList<>(found.size());
            List<TextSegment> segments = new ArrayList<>(found.size());
            for (EmbeddingMatch<TextSegment> matched : found) {
                TextSegment segment = matched.embedded();
                if (segment == null) {
                    continue;
                }
                Metadata metadata = segment.metadata().copy();
                values.forEach((key, value) -> {
                    if (value == null) {
                        metadata.remove(key);
                    } else {
                        metadata.put(key, value);
                    }
                });
                ids.add(matched.embeddingId());
                embeddings.add(matched.embedding());
                segments.add(TextSegment.from(segment.text(), metadata));
            }
            if (ids.isEmpty()) {
                return 0;
            }
            super.removeAll(ids);
            super.addAll(ids, embeddings, segments);
            return ids.size();
        }
    }

    @Override
    public String add(Embedding embedding) {
        synchronized (guard.lock()) {
            admit(1);
            learnDimension(embedding);
            return super.add(embedding);
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        synchronized (guard.lock()) {
            admit(1);
            learnDimension(embedding);
            super.add(id, embedding);
        }
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        synchronized (guard.lock()) {
            admit(1);
            learnDimension(embedding);
            return super.add(embedding, embedded);
        }
    }

    @Override
    public void add(String id, Embedding embedding, TextSegment embedded) {
        synchronized (guard.lock()) {
            admit(1);
            learnDimension(embedding);
            super.add(id, embedding, embedded);
        }
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        synchronized (guard.lock()) {
            admit(embeddings == null ? 0 : embeddings.size());
            learnDimension(embeddings);
            return super.addAll(embeddings);
        }
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        synchronized (guard.lock()) {
            admit(embeddings == null ? 0 : embeddings.size());
            learnDimension(embeddings);
            super.addAll(ids, embeddings, embedded);
        }
    }
}
