package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
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
public class RestApiCallsStore implements IRestApiCallsStore {
    private final IApiCallsStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<ApiCallsConfiguration> restVersionInfo;

    @Inject
    public RestApiCallsStore(IApiCallsStore httpCallsStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, httpCallsStore, documentDescriptorStore);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(ApiCallsConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readApiCallsDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.httpcalls", filter, index, limit);
    }

    @Override
    public ApiCallsConfiguration readApiCalls(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateApiCalls(String id, Integer version, ApiCallsConfiguration httpCallsConfiguration) {
        return restVersionInfo.update(id, version, httpCallsConfiguration);
    }

    @Override
    public Response createApiCalls(ApiCallsConfiguration httpCallsConfiguration) {
        return restVersionInfo.create(httpCallsConfiguration);
    }

    @Override
    public Response deleteApiCalls(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateApiCalls(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        ApiCallsConfiguration httpCallsConfiguration = restVersionInfo.read(id, version);
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
