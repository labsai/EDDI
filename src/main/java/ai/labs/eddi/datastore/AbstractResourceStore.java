/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.serialization.IDocumentBuilder;

/**
 * Generic, database-agnostic base class for configuration stores.
 * <p>
 * Encapsulates the shared constructor pattern ({@link IResourceStorageFactory}
 * to {@link IResourceStorage} to {@link HistorizedResourceStore}) and the 7
 * CRUD delegation methods that are identical across all configuration stores.
 * <p>
 * Subclasses only need to provide the collection name, document type, and any
 * domain-specific methods (e.g., readActions, filtering, custom queries).
 *
 * @param <T>
 *            the configuration document type
 */
public abstract class AbstractResourceStore<T> implements IResourceStore<T> {

    protected final HistorizedResourceStore<T> resourceStore;
    protected final IResourceStorage<T> resourceStorage;

    /**
     * No-args constructor required by CDI for proxy creation of
     * {@code @ApplicationScoped} subclasses.
     */
    protected AbstractResourceStore() {
        this.resourceStore = null;
        this.resourceStorage = null;
    }

    /**
     * Standard constructor - creates storage via factory, wraps in
     * HistorizedResourceStore. Used by most stores (LangChain, Parser,
     * PropertySetter, ApiCalls, Behavior, Output, RegularDictionary, Agent,
     * Workflow).
     */
    protected AbstractResourceStore(IResourceStorageFactory storageFactory, String collectionName, IDocumentBuilder documentBuilder,
            Class<T> documentType, String... indexes) {
        this.resourceStorage = storageFactory.create(collectionName, documentBuilder, documentType, indexes);
        this.resourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    /**
     * Constructor for subclasses that build custom HistorizedResourceStore
     * instances.
     */
    protected AbstractResourceStore(HistorizedResourceStore<T> resourceStore) {
        this.resourceStore = resourceStore;
        this.resourceStorage = null;
    }

    @Override
    public T readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return resourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(T content) throws ResourceStoreException {
        return resourceStore.create(content);
    }

    @Override
    public T read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return resourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, T content) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        return resourceStore.update(id, version, content);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        resourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        resourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return resourceStore.getCurrentResourceId(id);
    }
}
