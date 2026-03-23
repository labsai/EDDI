package ai.labs.eddi.configs.rules.rest;

import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
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
public class RestRuleSetStore implements IRestRuleSetStore {
    private final IRuleSetStore behaviorStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<RuleSetConfiguration> restVersionInfo;

    @Inject
    public RestRuleSetStore(IRuleSetStore behaviorStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, behaviorStore, documentDescriptorStore);
        this.behaviorStore = behaviorStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(RuleSetConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readBehaviorDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.behavior", filter, index, limit);
    }

    @Override
    public RuleSetConfiguration readRuleSet(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateRuleSet(String id, Integer version, RuleSetConfiguration behaviorConfiguration) {
        return restVersionInfo.update(id, version, behaviorConfiguration);
    }

    @Override
    public Response createRuleSet(RuleSetConfiguration behaviorConfiguration) {
        return restVersionInfo.create(behaviorConfiguration);
    }

    @Override
    public Response deleteRuleSet(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateRuleSet(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        var behaviorConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(behaviorConfiguration);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return behaviorStore.getCurrentResourceId(id);
    }
}
