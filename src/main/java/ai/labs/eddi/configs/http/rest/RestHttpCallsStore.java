package ai.labs.eddi.configs.http.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.http.IHttpCallsStore;
import ai.labs.eddi.configs.http.IRestHttpCallsStore;
import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.IRestInterfaceFactory;
import ai.labs.eddi.engine.RestInterfaceFactory;
import ai.labs.eddi.models.DocumentDescriptor;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestHttpCallsStore extends RestVersionInfo<HttpCallsConfiguration> implements IRestHttpCallsStore {
    private final IHttpCallsStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private IRestHttpCallsStore restHttpCallsStore;

    @Inject
    Logger log;

    @Inject
    public RestHttpCallsStore(IHttpCallsStore httpCallsStore,
                              IRestInterfaceFactory restInterfaceFactory,
                              IDocumentDescriptorStore documentDescriptorStore,
                              IJsonSchemaCreator jsonSchemaCreator) {
        super(resourceURI, httpCallsStore, documentDescriptorStore);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        initRestClient(restInterfaceFactory);
    }

    private void initRestClient(IRestInterfaceFactory restInterfaceFactory) {
        try {
            restHttpCallsStore = restInterfaceFactory.get(IRestHttpCallsStore.class);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            restHttpCallsStore = null;
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response readJsonSchema() {
        return Response.ok(jsonSchemaCreator.generateSchema(HttpCallsConfiguration.class)).build();
    }

    @Override
    public List<DocumentDescriptor> readHttpCallsDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.httpcalls", filter, index, limit);
    }

    @Override
    public HttpCallsConfiguration readHttpCalls(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public Response updateHttpCalls(String id, Integer version, HttpCallsConfiguration httpCallsConfiguration) {
        return update(id, version, httpCallsConfiguration);
    }

    @Override
    public Response createHttpCalls(HttpCallsConfiguration httpCallsConfiguration) {
        return create(httpCallsConfiguration);
    }

    @Override
    public Response deleteHttpCalls(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    public Response duplicateHttpCalls(String id, Integer version) {
        validateParameters(id, version);
        HttpCallsConfiguration httpCallsConfiguration = restHttpCallsStore.readHttpCalls(id, version);
        return restHttpCallsStore.createHttpCalls(httpCallsConfiguration);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return httpCallsStore.getCurrentResourceId(id);
    }
}
