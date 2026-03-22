package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestLlmStore implements IRestLlmStore {
    private final ILlmStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<LlmConfiguration> restVersionInfo;



    @Inject
    public RestLlmStore(ILlmStore httpCallsStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, httpCallsStore, documentDescriptorStore);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(LlmConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readLlmDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.langchain", filter, index, limit);
    }

    @Override
    public LlmConfiguration readLlm(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateLlm(String id, Integer version, LlmConfiguration llmConfiguration) {
        return restVersionInfo.update(id, version, llmConfiguration);
    }

    @Override
    public Response createLlm(LlmConfiguration llmConfiguration) {
        return restVersionInfo.create(llmConfiguration);
    }

    @Override
    public Response deleteLlm(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateLlm(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        LlmConfiguration llmConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(llmConfiguration);
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
