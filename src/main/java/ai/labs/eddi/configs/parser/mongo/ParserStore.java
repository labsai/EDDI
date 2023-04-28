package ai.labs.eddi.configs.parser.mongo;

import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ParserStore implements IParserStore {
    private HistorizedResourceStore<ParserConfiguration> parserResourceStore;

    @Inject
    public ParserStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");

        final String collectionName = "parsers";
        MongoResourceStorage<ParserConfiguration> mongoResourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, ParserConfiguration.class);
        parserResourceStore = new HistorizedResourceStore<>(mongoResourceStorage);
    }

    @Override
    public ParserConfiguration readIncludingDeleted(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return parserResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(ParserConfiguration parserConfiguration) throws IResourceStore.ResourceStoreException {
        return parserResourceStore.create(parserConfiguration);
    }

    @Override
    public ParserConfiguration read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return parserResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, ParserConfiguration content) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        return parserResourceStore.update(id, version, content);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        parserResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        parserResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return parserResourceStore.getCurrentResourceId(id);
    }
}
