package ai.labs.eddi.configs.http.mongo;

import ai.labs.eddi.configs.http.IHttpCallsStore;
import ai.labs.eddi.configs.http.model.HttpCall;
import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@ApplicationScoped
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
    public HttpCallsConfiguration readIncludingDeleted(String id, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        return httpCallsResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(HttpCallsConfiguration httpCallsConfiguration) throws IResourceStore.ResourceStoreException {
        return httpCallsResourceStore.create(httpCallsConfiguration);
    }

    @Override
    public HttpCallsConfiguration read(String id, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        return httpCallsResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, HttpCallsConfiguration httpCallsConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {

        return httpCallsResourceStore.update(id, version, httpCallsConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        httpCallsResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        httpCallsResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return httpCallsResourceStore.getCurrentResourceId(id);
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        List<String> actions = read(id, version).
                getHttpCalls().stream().
                map(HttpCall::getActions).
                flatMap(Collection::stream).
                collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, limit) : actions;
    }
}
