/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

/**
 * Refuses a write to an {@code in-memory} vector store because the deployment's
 * in-memory segment cap
 * ({@value EmbeddingStoreFactory#MAX_IN_MEMORY_SEGMENTS_PROPERTY}) has been
 * reached.
 * <p>
 * Thrown rather than evicting: an in-memory store <em>is</em> its data, so
 * making room by dropping another knowledge base would silently empty it — the
 * failure this cap replaces. Nothing is written when it is thrown.
 */
public class InMemoryStoreFullException extends IllegalStateException {

    public InMemoryStoreFullException(String message) {
        super(message);
    }
}
