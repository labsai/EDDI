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
 * <p>
 * A store the factory replaces or drops is {@linkplain #retire() retired}: it
 * accepts no further writes. An ingestion run that took the store before the
 * replacement still holds it, and without this its writes would land in a store
 * nothing reads any more, outside the cap, which counts only the stores still
 * registered. Refusing them keeps the cap exact and stops the run with a
 * reason, instead of letting it fill an orphan. The check runs under the same
 * lock as the cap, so no write slips in between the replacement and the flag.
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

    @Override
    public String add(Embedding embedding) {
        synchronized (guard.lock()) {
            admit(1);
            return super.add(embedding);
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        synchronized (guard.lock()) {
            admit(1);
            super.add(id, embedding);
        }
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        synchronized (guard.lock()) {
            admit(1);
            return super.add(embedding, embedded);
        }
    }

    @Override
    public void add(String id, Embedding embedding, TextSegment embedded) {
        synchronized (guard.lock()) {
            admit(1);
            super.add(id, embedding, embedded);
        }
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        synchronized (guard.lock()) {
            admit(embeddings == null ? 0 : embeddings.size());
            return super.addAll(embeddings);
        }
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        synchronized (guard.lock()) {
            admit(embeddings == null ? 0 : embeddings.size());
            super.addAll(ids, embeddings, embedded);
        }
    }
}
