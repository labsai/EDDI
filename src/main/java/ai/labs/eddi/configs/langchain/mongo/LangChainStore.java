package ai.labs.eddi.configs.langchain.mongo;

import ai.labs.eddi.configs.langchain.ILangChainStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class LangChainStore implements ILangChainStore {
    private final HistorizedResourceStore<LangChainConfiguration> langChainResourceStore;

    @Inject
    public LangChainStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "langchain";
        MongoResourceStorage<LangChainConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, LangChainConfiguration.class);
        this.langChainResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public LangChainConfiguration readIncludingDeleted(String id, Integer version)
            throws ResourceNotFoundException, ResourceStoreException {

        return langChainResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(LangChainConfiguration langChainConfiguration) throws ResourceStoreException {
        return langChainResourceStore.create(langChainConfiguration);
    }

    @Override
    public LangChainConfiguration read(String id, Integer version)
            throws ResourceNotFoundException, ResourceStoreException {

        return langChainResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, LangChainConfiguration langChainConfiguration)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {

        return langChainResourceStore.update(id, version, langChainConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceModifiedException, ResourceNotFoundException {
        langChainResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        langChainResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return langChainResourceStore.getCurrentResourceId(id);
    }
}
