package ai.labs.resources.impl.bots.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class BotStore implements IBotStore {
    private HistorizedResourceStore<BotConfiguration> botResourceStore;

    @Inject
    public BotStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
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
