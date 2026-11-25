/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.index;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.documents.index.DocumentIndexEligibility.Reference;
import jakarta.enterprise.inject.spi.CDI;

import java.util.function.Function;

/**
 * Looks up the RAG configuration a document-index reference names.
 *
 * <p>
 * Static access to the container exists for one caller: the save-time
 * validators are static and run inside {@code LlmStore}, which no later package
 * edits (the precedent for static reads there is {@code DocumentBounds}).
 * Everything else is handed an {@link IRagStore}.
 */
public final class DocumentIndexLookup {

    private DocumentIndexLookup() {
    }

    /** A lookup reading {@code store}: the version named, else the current one. */
    public static Function<Reference, RagConfiguration> of(IRagStore store) {
        return reference -> {
            try {
                Integer version = reference.version() != null
                        ? reference.version()
                        : store.getCurrentResourceId(reference.id()).getVersion();
                return store.read(reference.id(), version);
            } catch (IResourceStore.ResourceNotFoundException e) {
                return null;
            } catch (IResourceStore.ResourceStoreException e) {
                throw new IllegalStateException("Could not read RAG configuration " + reference.id() + " to check it is a document index", e);
            }
        };
    }

    /**
     * A lookup through the running container's {@link IRagStore}.
     *
     * @throws IllegalStateException
     *             when there is no container (a save is then refused, never passed
     *             unchecked)
     */
    public static Function<Reference, RagConfiguration> fromContainer() {
        IRagStore store;
        try {
            store = CDI.current().select(IRagStore.class).get();
        } catch (RuntimeException e) {
            throw new IllegalStateException("The RAG configuration a document index names cannot be checked here", e);
        }
        return of(store);
    }
}
