/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion.mongo;

import ai.labs.eddi.configs.ingestion.IRagIngestionSourceStore;
import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MongoDB implementation of {@link IRagIngestionSourceStore}.
 * <p>
 * Stores ingestion source configurations in the {@code ingestionSources}
 * collection with full versioning support.
 */
@ApplicationScoped
public class MongoRagIngestionSourceStore extends AbstractResourceStore<RagIngestionSource>
        implements
            IRagIngestionSourceStore {

    @Inject
    public MongoRagIngestionSourceStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "ingestionSources", documentBuilder, RagIngestionSource.class, "name");
    }
}
