package io.sls.persistence.impl.bots.mongo;

import com.mongodb.DB;
import io.sls.persistence.impl.mongo.HistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.resources.rest.bots.IBotStore;
import io.sls.resources.rest.bots.model.BotConfiguration;
import io.sls.serialization.IDocumentBuilder;
import io.sls.serialization.JSONSerialization;
import io.sls.utilities.RuntimeUtilities;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;

/**
 * @author ginccc
 */
public class BotStore implements IBotStore {
    private final String collectionName = "bots";
    private HistorizedResourceStore<BotConfiguration> botResourceStore;

    @Inject
    public BotStore(DB database) {
        RuntimeUtilities.checkNotNull(database, "database");
        MongoResourceStorage<BotConfiguration> resourceStorage = new MongoResourceStorage<BotConfiguration>(database, collectionName, new IDocumentBuilder<BotConfiguration>() {
            @Override
            public BotConfiguration build(String doc) throws IOException {
                return JSONSerialization.deserialize(doc, new TypeReference<BotConfiguration>() {});
            }
        });
        this.botResourceStore = new HistorizedResourceStore<BotConfiguration>(resourceStorage);
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
