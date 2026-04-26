/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.HistorizedResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

/**
 * MongoDB-specific base class for configuration stores.
 * <p>
 * Extends the database-agnostic {@link AbstractResourceStore} and provides a
 * constructor that accepts {@link MongoDatabase} directly for backward
 * compatibility with existing stores during the migration to the factory
 * pattern.
 * <p>
 * <b>New stores should use {@link AbstractResourceStore} directly</b> with
 * {@link ai.labs.eddi.datastore.IResourceStorageFactory} injection instead.
 *
 * @param <T>
 *            the configuration document type
 * @deprecated Use {@link AbstractResourceStore} with
 *             {@link ai.labs.eddi.datastore.IResourceStorageFactory} instead
 */
@Deprecated
public abstract class AbstractMongoResourceStore<T> extends AbstractResourceStore<T> {

    /**
     * No-args constructor required by CDI for proxy creation of
     * {@code @ApplicationScoped} subclasses.
     */
    @Deprecated
    protected AbstractMongoResourceStore() {
        super();
    }

    /**
     * Standard constructor - creates MongoResourceStorage + HistorizedResourceStore
     * internally.
     *
     * @deprecated Use {@link AbstractResourceStore} with IResourceStorageFactory
     *             instead
     */
    @Deprecated
    protected AbstractMongoResourceStore(MongoDatabase database, String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType) {
        super(createHistorizedStore(database, collectionName, documentBuilder, documentType));
    }

    /**
     * Constructor for subclasses that build custom HistorizedResourceStore
     * instances. Used by AgentStore and WorkflowStore which have inner classes
     * extending MongoResourceStorage.
     */
    @Deprecated
    protected AbstractMongoResourceStore(HistorizedResourceStore<T> resourceStore) {
        super(resourceStore);
    }

    private static <T> HistorizedResourceStore<T> createHistorizedStore(MongoDatabase database, String collectionName,
                                                                        IDocumentBuilder documentBuilder, Class<T> documentType) {
        RuntimeUtilities.checkNotNull(database, "database");
        MongoResourceStorage<T> resourceStorage = new MongoResourceStorage<>(database, collectionName, documentBuilder, documentType);
        return new HistorizedResourceStore<>(resourceStorage);
    }
}
