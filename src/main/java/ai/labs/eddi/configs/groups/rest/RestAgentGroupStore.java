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
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
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
    private static final Logger LOG = Logger.getLogger(RestAgentGroupStore.class);

    private final IAgentGroupStore groupStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<AgentGroupConfiguration> restVersionInfo;

    @Inject
    public RestAgentGroupStore(IAgentGroupStore groupStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, groupStore, documentDescriptorStore);
        this.groupStore = groupStore;
        this.documentDescriptorStore = documentDescriptorStore;
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
        Response response = restVersionInfo.update(id, version, groupConfiguration);
        syncDescriptor(id, groupConfiguration);
        return response;
    }

    @Override
    public Response createGroup(AgentGroupConfiguration groupConfiguration) {
        Response response = restVersionInfo.create(groupConfiguration);
        // Sync name/description from config onto the descriptor
        URI location = response.getLocation();
        if (location != null) {
            try {
                var resourceId = RestUtilities.extractResourceId(location);
                syncDescriptor(resourceId.getId(), groupConfiguration);
            } catch (Exception e) {
                LOG.warn("Failed to sync group descriptor name/description on create", e);
            }
        }
        return response;
    }

    @Override
    public Response duplicateGroup(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        AgentGroupConfiguration config = restVersionInfo.read(id, version);
        Response response = restVersionInfo.create(config);
        // Sync descriptor for the duplicate too
        URI location = response.getLocation();
        if (location != null) {
            try {
                var resourceId = RestUtilities.extractResourceId(location);
                syncDescriptor(resourceId.getId(), config);
            } catch (Exception e) {
                LOG.warn("Failed to sync group descriptor name/description on duplicate", e);
            }
        }
        return response;
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

    /**
     * Sync the group config's name and description onto the DocumentDescriptor so
     * that the descriptors endpoint returns meaningful display information.
     */
    private void syncDescriptor(String resourceId, AgentGroupConfiguration config) {
        try {
            var currentResourceId = groupStore.getCurrentResourceId(resourceId);
            var descriptor = documentDescriptorStore.readDescriptor(resourceId, currentResourceId.getVersion());
            boolean changed = false;

            if (config.getName() != null && !config.getName().equals(descriptor.getName())) {
                descriptor.setName(config.getName());
                changed = true;
            }
            if (config.getDescription() != null && !config.getDescription().equals(descriptor.getDescription())) {
                descriptor.setDescription(config.getDescription());
                changed = true;
            }

            if (changed) {
                documentDescriptorStore.setDescriptor(resourceId, currentResourceId.getVersion(), descriptor);
            }
        } catch (Exception e) {
            LOG.warn("Failed to sync group descriptor name/description for id=" + resourceId, e);
        }
    }
}
