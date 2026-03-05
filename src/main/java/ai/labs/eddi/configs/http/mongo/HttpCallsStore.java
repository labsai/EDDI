package ai.labs.eddi.configs.http.mongo;

import ai.labs.eddi.configs.http.IHttpCallsStore;
import ai.labs.eddi.configs.http.model.HttpCall;
import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.datastore.mongo.AbstractMongoResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
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
public class HttpCallsStore extends AbstractMongoResourceStore<HttpCallsConfiguration>
        implements IHttpCallsStore {

    @Inject
    public HttpCallsStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        super(database, "httpcalls", documentBuilder, HttpCallsConfiguration.class);
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws ResourceNotFoundException, ResourceStoreException {

        List<String> actions = read(id, version).getHttpCalls().stream().map(HttpCall::getActions)
                .flatMap(Collection::stream).collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, limit) : actions;
    }
}
