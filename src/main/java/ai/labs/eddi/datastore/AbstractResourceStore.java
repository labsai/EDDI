/*
 * Copyright EDDI contributors
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

    /**
     * Write-time validation hook, invoked by {@link #create(Object)} and
     * {@link #update(String, Integer, Object)} before anything is persisted.
     * <p>
     * The default implementation does nothing, so no existing store changes
     * behaviour unless it opts in by overriding this method.
     * <p>
     * Overrides exist to make a structurally broken configuration fail at
     * <em>save</em> time rather than at agent-deploy time or, worse, mid
     * conversation. Throw {@link IllegalArgumentException} with a message that
     * names the offending field (and the rule/entry it came from) and lists the
     * legal values — {@code IllegalArgumentExceptionMapper} turns that into a 400
     * carrying the message, which is what the agent author actually needs.
     * <p>
     * Read paths deliberately do <b>not</b> call this: a document already in the
     * database must keep loading even if the rules tightened since it was written.
     *
     * @param content
     *            the document about to be written
     * @throws IllegalArgumentException
     *             if the document cannot be honoured as written
     */
    protected void validate(T content) {
        // no constraints by default
    }

    @Override
    public IResourceId create(T content) throws ResourceStoreException {
        validate(content);
        return resourceStore.create(content);
    }

    @Override
    public T read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return resourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, T content) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        validate(content);
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

    /**
     * Whether a hit from a reverse-reference lookup should be ignored because it
     * does not describe the resource as it stands today.
     * <p>
     * Reverse lookups union the current collection with history, so they return two
     * kinds of stale hit: an older version of a resource that still exists, and a
     * version of a resource that has since been soft-deleted (the current row is
     * gone, the history row still matches). Only the first is a comparison —
     * {@link #getCurrentResourceId(String)} <em>throws</em> for the second, and an
     * unguarded call turns one soft-deleted referrer into a 404 for the whole
     * listing and a permanently swallowed cascade-delete.
     *
     * @return true when {@code id} has no current version, or when {@code version}
     *         is older than it
     */
    protected boolean isStaleReference(String id, Integer version) {
        try {
            return version < getCurrentResourceId(id).getVersion();
        } catch (ResourceNotFoundException e) {
            // No current row: the resource was soft-deleted, so every history hit
            // for it is stale by definition.
            return true;
        }
    }
}
