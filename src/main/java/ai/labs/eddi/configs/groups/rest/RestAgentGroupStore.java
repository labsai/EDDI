package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    public Response readDiscussionStyles() {
        var styles = Arrays.stream(DiscussionStyle.values()).map(s -> {
            var phases = DiscussionStylePresets.expand(s, 2);
            var phaseNames = phases.stream().map(p -> p.name()).toList();
            return Map.of("style", s.name(), "phases", phaseNames, "description", describeStyle(s));
        }).toList();
        return Response.ok(styles).build();
    }

    private static String describeStyle(DiscussionStyle style) {
        return switch (style) {
            case ROUND_TABLE -> "Open discussion with multiple opinion rounds and moderator synthesis";
            case PEER_REVIEW -> "Each member gives an opinion, then critiques every peer, then revises";
            case DEVIL_ADVOCATE -> "One designated challenger argues against the group consensus";
            case DELPHI -> "Anonymous opinion rounds to reduce groupthink and achieve convergence";
            case DEBATE -> "Structured pro/con argumentation with rebuttal and judge";
            case CUSTOM -> "User-defined phases for full control over the discussion flow";
        };
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
