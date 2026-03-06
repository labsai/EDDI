package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;

/**
 * Generic base class for MongoDB-backed configuration stores.
 * <p>
 * Encapsulates the shared constructor pattern (MongoResourceStorage +
 * HistorizedResourceStore)
 * and the 7 CRUD delegation methods that are identical across all configuration
 * stores.
 * <p>
 * Subclasses only need to provide the collection name, document type, and any
 * domain-specific methods (e.g., readActions, filtering, custom queries).
 *
 * @param <T> the configuration document type
 */
public abstract class AbstractMongoResourceStore<T> implements IResourceStore<T> {

    protected final HistorizedResourceStore<T> resourceStore;

    /**
     * No-args constructor required by CDI for proxy creation of
     * {@code @ApplicationScoped} subclasses.
     */
    protected AbstractMongoResourceStore() {
        this.resourceStore = null;
    }

    /**
     * Standard constructor — creates MongoResourceStorage + HistorizedResourceStore
     * internally.
     * Used by most stores (LangChain, Parser, PropertySetter, HttpCalls, Behavior,
     * Output, RegularDictionary).
     */
    protected AbstractMongoResourceStore(MongoDatabase database,
            String collectionName,
            IDocumentBuilder documentBuilder,
            Class<T> documentType) {
        RuntimeUtilities.checkNotNull(database, "database");
        MongoResourceStorage<T> resourceStorage = new MongoResourceStorage<>(database, collectionName, documentBuilder,
                documentType);
        this.resourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    /**
     * Constructor for subclasses that build custom HistorizedResourceStore
     * instances.
     * Used by BotStore and PackageStore which have inner classes extending
     * MongoResourceStorage.
     */
    protected AbstractMongoResourceStore(HistorizedResourceStore<T> resourceStore) {
        this.resourceStore = resourceStore;
    }

    @Override
    public T readIncludingDeleted(String id, Integer version)
            throws ResourceNotFoundException, ResourceStoreException {
        return resourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(T content) throws ResourceStoreException {
        return resourceStore.create(content);
    }

    @Override
    public T read(String id, Integer version)
            throws ResourceNotFoundException, ResourceStoreException {
        return resourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, T content)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        return resourceStore.update(id, version, content);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
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
