/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.List;
import java.util.UUID;

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
 */
final class CappedInMemoryEmbeddingStore extends InMemoryEmbeddingStore<TextSegment> {

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

    CappedInMemoryEmbeddingStore(String knowledgeBaseId, CapacityGuard guard) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.guard = guard;
    }

    /** This instance's identity — different for every store ever created. */
    String epoch() {
        return epoch;
    }

    @Override
    public String add(Embedding embedding) {
        synchronized (guard.lock()) {
            guard.reserve(knowledgeBaseId, 1);
            return super.add(embedding);
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        synchronized (guard.lock()) {
            guard.reserve(knowledgeBaseId, 1);
            super.add(id, embedding);
        }
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        synchronized (guard.lock()) {
            guard.reserve(knowledgeBaseId, 1);
            return super.add(embedding, embedded);
        }
    }

    @Override
    public void add(String id, Embedding embedding, TextSegment embedded) {
        synchronized (guard.lock()) {
            guard.reserve(knowledgeBaseId, 1);
            super.add(id, embedding, embedded);
        }
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        synchronized (guard.lock()) {
            guard.reserve(knowledgeBaseId, embeddings == null ? 0 : embeddings.size());
            return super.addAll(embeddings);
        }
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        synchronized (guard.lock()) {
            guard.reserve(knowledgeBaseId, embeddings == null ? 0 : embeddings.size());
            super.addAll(ids, embeddings, embedded);
        }
    }
}
