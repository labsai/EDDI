package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.pipelines.IRestPipelineStore;
import ai.labs.eddi.configs.pipelines.rest.RestPipelineStore;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.*;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestAgentStore implements IRestAgentStore {
    private static final String PACKAGE_URI = IRestPipelineStore.resourceURI;
    private final IAgentStore AgentStore;
    private final IRestPipelineStore restPipelineStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<AgentConfiguration> restVersionInfo;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IScheduleStore scheduleStore;

    private static final Logger log = Logger.getLogger(RestAgentStore.class);

    @Inject
    public RestAgentStore(IAgentStore AgentStore,
            IRestPipelineStore restPipelineStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator,
            IScheduleStore scheduleStore) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, AgentStore, documentDescriptorStore);
        this.documentDescriptorStore = documentDescriptorStore;
        this.AgentStore = AgentStore;
        this.restPipelineStore = restPipelineStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.scheduleStore = scheduleStore;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(AgentConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.bot", filter, index, limit);
    }

    @Override
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit,
            String containingPackageUri, Boolean includePreviousVersions) {

        IResourceId validatedResourceId = validateUri(containingPackageUri);
        if (validatedResourceId == null || !containingPackageUri.startsWith(PACKAGE_URI)) {
            return createMalFormattedResourceUriException(containingPackageUri);
        }

        try {
            return AgentStore.getBotDescriptorsContainingPackage(
                    validatedResourceId.getId(), validatedResourceId.getVersion(), includePreviousVersions);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public AgentConfiguration readBot(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateBot(String id, Integer version, AgentConfiguration AgentConfiguration) {
        return restVersionInfo.update(id, version, AgentConfiguration);
    }

    @Override
    public Response updateResourceInBot(String id, Integer version, URI resourceURI) {
        String resourceURIString = resourceURI.toString();
        String resourceURIWithoutVersion = resourceURIString.substring(0, resourceURIString.lastIndexOf("?"));

        boolean updated = false;
        AgentConfiguration AgentConfiguration = readBot(id, version);
        List<URI> packages = AgentConfiguration.getPipelines();
        for (int index = 0; index < packages.size(); index++) {
            URI pipelineUri = packages.get(index);
            if (pipelineUri.toString().startsWith(resourceURIWithoutVersion)) {
                packages.set(index, resourceURI);
                updated = true;
            }
        }

        if (updated) {
            return updateBot(id, version, AgentConfiguration);
        } else {
            URI uri = RestUtilities.createURI(RestPipelineStore.resourceURI, id, versionQueryParam, version);
            return Response.status(BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
        }
    }

    @Override
    public Response createAgent(AgentConfiguration AgentConfiguration) {
        return restVersionInfo.create(AgentConfiguration);
    }

    @Override
    public Response duplicateBot(String id, Integer version, Boolean deepCopy) {
        restVersionInfo.validateParameters(id, version);
        try {
            AgentConfiguration AgentConfiguration = AgentStore.read(id, version);
            if (deepCopy) {
                List<URI> packages = AgentConfiguration.getPipelines();
                for (int i = 0; i < packages.size(); i++) {
                    URI pipelineUri = packages.get(i);
                    IResourceId resourceId = RestUtilities.extractResourceId(pipelineUri);
                    Response duplicateResourceResponse = restPipelineStore.duplicatePackage(resourceId.getId(),
                            resourceId.getVersion(), true);
                    URI newResourceLocation = duplicateResourceResponse.getLocation();
                    packages.set(i, newResourceLocation);
                }
            }

            Response createAgentResponse = restVersionInfo.create(AgentConfiguration);
            createDocumentDescriptorForDuplicate(documentDescriptorStore, id, version, createAgentResponse.getLocation());

            return createAgentResponse;
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response deleteBot(String id, Integer version, Boolean permanent, Boolean cascade) {
        if (cascade) {
            // Cascade-delete all schedules for this Agent first
            try {
                int deletedSchedules = scheduleStore.deleteSchedulesByBotId(id);
                if (deletedSchedules > 0) {
                    log.infof("Cascade-deleted %d schedule(s) for Agent %s", deletedSchedules, id);
                }
            } catch (Exception e) {
                log.warnf("Failed to cascade-delete schedules for Agent %s: %s", id, e.getMessage());
            }

            try {
                AgentConfiguration AgentConfiguration = AgentStore.read(id, version);
                for (URI pipelineUri : AgentConfiguration.getPipelines()) {
                    IResourceId resourceId = RestUtilities.extractResourceId(pipelineUri);
                    try {
                        // Check if this package is referenced by other bots
                        var referencingBots = AgentStore.getBotDescriptorsContainingPackage(
                                resourceId.getId(), resourceId.getVersion(), false);
                        if (referencingBots.size() > 1) {
                            log.infof("Skipping cascade-delete of package %s (v%d) — " +
                                            "still referenced by %d other bot(s)",
                                    resourceId.getId(), resourceId.getVersion(),
                                    referencingBots.size() - 1);
                            continue;
                        }

                        restPipelineStore.deletePackage(
                                resourceId.getId(), resourceId.getVersion(), permanent, true);
                        log.infof("Cascade-deleted package %s (v%d) for Agent %s",
                                resourceId.getId(), resourceId.getVersion(), id);
                    } catch (Exception e) {
                        log.warnf("Failed to cascade-delete package %s: %s",
                                resourceId.getId(), e.getMessage());
                    }
                }
            } catch (IResourceStore.ResourceNotFoundException e) {
                log.warnf("Bot %s (v%d) not found for cascade — deleting Agent only", id, version);
            } catch (IResourceStore.ResourceStoreException e) {
                log.warnf("Error reading Agent %s for cascade: %s", id, e.getMessage());
            }
        }
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return AgentStore.getCurrentResourceId(id);
    }
}
