package ai.labs.resources.impl.config.parser.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.config.parser.IParserStore;
import ai.labs.resources.rest.config.parser.model.ParserConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;

/**
 * @author ginccc
 */
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
    public ParserConfiguration readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return parserResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(ParserConfiguration parserConfiguration) throws ResourceStoreException {
        return parserResourceStore.create(parserConfiguration);
    }

    @Override
    public ParserConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return parserResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, ParserConfiguration content) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        return parserResourceStore.update(id, version, content);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        parserResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        parserResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return parserResourceStore.getCurrentResourceId(id);
    }
}
