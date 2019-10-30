package ai.labs.resources.impl.config.http.rest;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.config.http.IHttpCallsStore;
import ai.labs.resources.rest.config.http.IRestHttpCallsStore;
import ai.labs.resources.rest.config.http.model.HttpCallsConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
import ai.labs.schema.IJsonSchemaCreator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestHttpCallsStore extends RestVersionInfo<HttpCallsConfiguration> implements IRestHttpCallsStore {
    private final IHttpCallsStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private IRestHttpCallsStore restHttpCallsStore;

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
