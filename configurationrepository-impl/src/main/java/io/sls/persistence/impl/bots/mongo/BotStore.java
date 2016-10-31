package io.sls.persistence.impl.bots.mongo;

import com.mongodb.DB;
import io.sls.persistence.impl.mongo.HistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.resources.rest.bots.IBotStore;
import io.sls.resources.rest.bots.model.BotConfiguration;
import io.sls.serialization.IDocumentBuilder;
import io.sls.utilities.RuntimeUtilities;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class BotStore implements IBotStore {
    private HistorizedResourceStore<BotConfiguration> botResourceStore;

    @Inject
    public BotStore(DB database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "bots";
        MongoResourceStorage<BotConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, BotConfiguration.class);
        this.botResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public IResourceId create(BotConfiguration botConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), "packages");
        return botResourceStore.create(botConfiguration);
    }

    @Override
    public BotConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return botResourceStore.read(id, version);
    }

    @Override
    public Integer update(String id, Integer version, BotConfiguration botConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), "packages");
        return botResourceStore.update(id, version, botConfiguration);
    }

    @Override
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        botResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        botResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return botResourceStore.getCurrentResourceId(id);
    }
}
