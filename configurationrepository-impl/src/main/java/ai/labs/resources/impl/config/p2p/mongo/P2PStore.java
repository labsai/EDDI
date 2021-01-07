package ai.labs.resources.impl.config.p2p.mongo;

import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.config.p2p.IP2PStore;
import ai.labs.resources.rest.config.p2p.model.P2PConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;


public class P2PStore implements IP2PStore {

    private HistorizedResourceStore<P2PConfiguration> p2PConfigurationHistorizedResourceStore;


    @Inject
    public P2PStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "p2p";
        MongoResourceStorage<P2PConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, P2PConfiguration.class);
        this.p2PConfigurationHistorizedResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }


    @Override
    public P2PConfiguration readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return p2PConfigurationHistorizedResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(P2PConfiguration content) throws ResourceStoreException {
        return p2PConfigurationHistorizedResourceStore.create(content);
    }

    @Override
    public P2PConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return p2PConfigurationHistorizedResourceStore.read(id, version);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, P2PConfiguration content) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        return p2PConfigurationHistorizedResourceStore.update(id, version, content);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        p2PConfigurationHistorizedResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        p2PConfigurationHistorizedResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return p2PConfigurationHistorizedResourceStore.getCurrentResourceId(id);
    }
}
