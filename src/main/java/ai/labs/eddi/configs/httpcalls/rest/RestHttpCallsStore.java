package ai.labs.eddi.configs.httpcalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.httpcalls.IHttpCallsStore;
import ai.labs.eddi.configs.httpcalls.IRestHttpCallsStore;
import ai.labs.eddi.configs.httpcalls.model.HttpCallsConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestHttpCallsStore implements IRestHttpCallsStore {
    private final IHttpCallsStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<HttpCallsConfiguration> restVersionInfo;

    @Inject
    public RestHttpCallsStore(IHttpCallsStore httpCallsStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, httpCallsStore, documentDescriptorStore);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(HttpCallsConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readHttpCallsDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.httpcalls", filter, index, limit);
    }

    @Override
    public HttpCallsConfiguration readHttpCalls(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateHttpCalls(String id, Integer version, HttpCallsConfiguration httpCallsConfiguration) {
        return restVersionInfo.update(id, version, httpCallsConfiguration);
    }

    @Override
    public Response createHttpCalls(HttpCallsConfiguration httpCallsConfiguration) {
        return restVersionInfo.create(httpCallsConfiguration);
    }

    @Override
    public Response deleteHttpCalls(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateHttpCalls(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        HttpCallsConfiguration httpCallsConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(httpCallsConfiguration);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return httpCallsStore.getCurrentResourceId(id);
    }
}
