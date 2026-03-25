package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
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
 * REST implementation for group configuration CRUD.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestAgentGroupStore implements IRestAgentGroupStore {
    private final IAgentGroupStore groupStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<AgentGroupConfiguration> restVersionInfo;

    @Inject
    public RestAgentGroupStore(IAgentGroupStore groupStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, groupStore, documentDescriptorStore);
        this.groupStore = groupStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(AgentGroupConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readGroupDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.group", filter, index, limit);
    }

    @Override
    public AgentGroupConfiguration readGroup(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateGroup(String id, Integer version, AgentGroupConfiguration groupConfiguration) {
        return restVersionInfo.update(id, version, groupConfiguration);
    }

    @Override
    public Response createGroup(AgentGroupConfiguration groupConfiguration) {
        return restVersionInfo.create(groupConfiguration);
    }

    @Override
    public Response duplicateGroup(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        AgentGroupConfiguration config = restVersionInfo.read(id, version);
        return restVersionInfo.create(config);
    }

    @Override
    public Response deleteGroup(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return groupStore.getCurrentResourceId(id);
    }
}
