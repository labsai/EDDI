package ai.labs.resources.impl.config.http.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.config.http.IHttpCallsStore;
import ai.labs.resources.rest.config.http.model.HttpCallsConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class HttpCallsStore implements IHttpCallsStore {
    private HistorizedResourceStore<HttpCallsConfiguration> httpCallsResourceStore;

    @Inject
    public HttpCallsStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "httpcalls";
        MongoResourceStorage<HttpCallsConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, HttpCallsConfiguration.class);
        this.httpCallsResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public HttpCallsConfiguration readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return httpCallsResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(HttpCallsConfiguration httpCallsConfiguration) throws ResourceStoreException {
        return httpCallsResourceStore.create(httpCallsConfiguration);
    }

    @Override
    public HttpCallsConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return httpCallsResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, HttpCallsConfiguration httpCallsConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        return httpCallsResourceStore.update(id, version, httpCallsConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        httpCallsResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        httpCallsResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return httpCallsResourceStore.getCurrentResourceId(id);
    }
}
