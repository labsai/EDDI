/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.mongo;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MongoDB-backed store for RAG (Knowledge Base) configurations.
 */
@ApplicationScoped
public class RagStore extends AbstractResourceStore<RagConfiguration> implements IRagStore {

    @Inject
    public RagStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "rags", documentBuilder, RagConfiguration.class);
    }

    /**
     * Finding I3: {@code chunkStrategy} has no reader — ingestion always builds a
     * {@code DocumentSplitters.recursive} splitter — so any other value was
     * accepted and then silently ignored. {@link RagConfiguration#validate()}
     * already knows which strategies exist but only ran at retrieval time, long
     * after the author had been told the save succeeded. Running it here rejects
     * the unimplemented value at save time and names the supported ones.
     */
    @Override
    protected void validate(RagConfiguration content) {
        if (content != null) {
            content.validate();
        }
    }
}
