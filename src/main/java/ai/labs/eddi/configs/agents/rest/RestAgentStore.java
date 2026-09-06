/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.rest.RestWorkflowStore;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

import jakarta.ws.rs.BadRequestException;
import static ai.labs.eddi.configs.descriptors.ResourceUtilities.*;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestAgentStore implements IRestAgentStore {
    private static final String WORKFLOW_URI = IRestWorkflowStore.resourceURI;
    private final IAgentStore agentStore;
    private final IRestWorkflowStore restWorkflowStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<AgentConfiguration> restVersionInfo;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard resourceAccessGuard;
    private final IScheduleStore scheduleStore;
    private final CapabilityRegistryService capabilityRegistryService;
    private final IDeploymentStore deploymentStore;

    private static final Logger log = Logger.getLogger(RestAgentStore.class);

    @Inject
    public RestAgentStore(IAgentStore agentStore, IRestWorkflowStore restWorkflowStore, IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator, IScheduleStore scheduleStore, CapabilityRegistryService capabilityRegistryService,
            IDeploymentStore deploymentStore,
            ResourceAccessGuard resourceAccessGuard) {
        this.resourceAccessGuard = resourceAccessGuard;
        restVersionInfo = new RestVersionInfo<>(resourceURI, agentStore, documentDescriptorStore, resourceAccessGuard);
        this.documentDescriptorStore = documentDescriptorStore;
        this.agentStore = agentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.scheduleStore = scheduleStore;
        this.capabilityRegistryService = capabilityRegistryService;
        this.deploymentStore = deploymentStore;
    }

    /**
     * Populate the capability registry at startup from all existing agents.
     */
    @PostConstruct
    void populateCapabilityRegistry() {
        try {
            var descriptors = documentDescriptorStore.readDescriptors("ai.labs.agent", null, 0, IDescriptorStore.NO_LIMIT, false);
            int registered = 0;
            for (var descriptor : descriptors) {
                try {
                    var resourceId = RestUtilities.extractResourceId(descriptor.getResource());
                    var config = agentStore.read(resourceId.getId(), resourceId.getVersion());
                    if (config.getCapabilities() != null && !config.getCapabilities().isEmpty()) {
                        capabilityRegistryService.register(resourceId.getId(), config);
                        registered++;
                    }
                } catch (Exception e) {
                    log.debugf("Skipping capability registration for agent: %s", e.getMessage());
                }
            }
            if (registered > 0) {
                log.infof("Capability registry populated: %d agent(s) with capabilities", registered);
            }
        } catch (Exception e) {
            log.warnf("Failed to populate capability registry at startup: %s", e.getMessage());
        }
    }

    /**
     * Drops descriptors the caller may not view. Mirrors
     * {@code ResourceAccessGuard.requireAccess(id, VIEW, …)} exactly, so a caller
     * never lists something they could not then read.
     */
    private List<DocumentDescriptor> visibleOnly(List<DocumentDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return descriptors;
        }
        return descriptors.stream().filter(d -> resourceAccessGuard.canAccess(d, AccessLevel.VIEW))
                .map(resourceAccessGuard::redactForCaller).toList();
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
    public List<DocumentDescriptor> readAgentDescriptors(String filter, Integer index, Integer limit, String space) {
        return restVersionInfo.readDescriptors(filter, index, limit, space);
    }

    @Override
    public List<DocumentDescriptor> readAgentDescriptors(String filter, Integer index, Integer limit, String containingWorkflowUri,
                                                         Boolean includePreviousVersions) {

        IResourceId validatedResourceId = validateUri(containingWorkflowUri);
        if (validatedResourceId == null || !containingWorkflowUri.startsWith(WORKFLOW_URI)) {
            return createMalFormattedResourceUriException(containingWorkflowUri);
        }

        try {
            // Post-filtered rather than query-filtered: this is a reverse-reference lookup
            // in the store, not a descriptor listing, so there is no AccessScope to hand
            // it. Unfiltered it is a cross-workspace enumeration — anyone holding one
            // resource URI could list every resource in the deployment that references it,
            // with the full descriptor payload. The page can come back short; that is the
            // right trade for a diagnostic listing bounded by how many things reference
            // one resource.
            return visibleOnly(agentStore.getAgentDescriptorsContainingWorkflow(validatedResourceId.getId(),
                    validatedResourceId.getVersion(), includePreviousVersions));
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public AgentConfiguration readAgent(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateAgent(String id, Integer version, AgentConfiguration agentConfiguration) {
        validateSecurityFlags(agentConfiguration);
        Response response = restVersionInfo.update(id, version, agentConfiguration);
        capabilityRegistryService.register(id, agentConfiguration);
        return response;
    }

    @Override
    public Response updateResourceInAgent(String id, Integer version, URI resourceURI) {
        // The supplied URI must carry a real version, not merely a '?'. Stored
        // references are matched by "everything before the query" and then REPLACED by
        // this URI, so '...?other=2' would match a versioned reference and overwrite it
        // with a versionless one — silently unpinning the workflow the agent resolves.
        // (Testing lastIndexOf('?') alone also made substring(0, -1) throw, turning
        // malformed input into a 500 on the re-point cascade an approval-gated agent
        // must walk to finish an edit.)
        String resourceURIWithoutVersion = RestUtilities.pathWithoutVersionQuery(resourceURI);
        if (resourceURIWithoutVersion == null) {
            return Response.status(BAD_REQUEST)
                    .entity("resourceURI must carry a version, e.g. '...?version=2'")
                    .type(MediaType.TEXT_PLAIN).build();
        }

        boolean updated = false;
        AgentConfiguration agentConfig = readAgent(id, version);
        List<URI> packages = agentConfig.getWorkflows();
        for (int index = 0; index < packages.size(); index++) {
            URI workflowUri = packages.get(index);
            if (workflowUri.toString().startsWith(resourceURIWithoutVersion)) {
                packages.set(index, resourceURI);
                updated = true;
            }
        }

        if (updated) {
            return updateAgent(id, version, agentConfig);
        } else {
            URI uri = RestUtilities.createURI(RestWorkflowStore.resourceURI, id, versionQueryParam, version);
            return Response.status(BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
        }
    }

    @Override
    public Response createAgent(AgentConfiguration agentConfiguration) {
        validateSecurityFlags(agentConfiguration);

        // Use createDocument() to get IResourceId directly — Response.getLocation()
        // returns null for eddi:// scheme URIs in CDI direct calls
        IResourceId resourceId = restVersionInfo.createDocument(agentConfiguration);
        URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());

        try {
            capabilityRegistryService.register(resourceId.getId(), agentConfiguration);
        } catch (Exception e) {
            log.debugf("Could not register capabilities for new agent: %s", e.getMessage());
        }

        return Response.created(createdUri).location(createdUri)
                .header("X-Resource-URI", createdUri.toString()).build();
    }

    @Override
    public Response duplicateAgent(String id, Integer version, Boolean deepCopy) {
        restVersionInfo.requireViewAccess(id);
        restVersionInfo.validateParameters(id, version);
        try {
            AgentConfiguration agentConfig = agentStore.read(id, version);
            validateSecurityFlags(agentConfig);
            if (deepCopy) {
                List<URI> packages = agentConfig.getWorkflows();
                for (int i = 0; i < packages.size(); i++) {
                    URI workflowUri = packages.get(i);
                    IResourceId wfResId = RestUtilities.extractResourceId(workflowUri);
                    Response duplicateResourceResponse = restWorkflowStore.duplicateWorkflow(wfResId.getId(), wfResId.getVersion(), true);
                    // Extract URI from X-Resource-URI entity fallback since getLocation() fails for
                    // eddi:// URIs
                    URI newResourceLocation = extractCreatedUri(duplicateResourceResponse);
                    if (newResourceLocation == null) {
                        throw new IllegalStateException(String.format(
                                "Could not determine created workflow URI while duplicating workflow '%s' (id=%s, version=%s); response status=%s",
                                workflowUri, wfResId.getId(), wfResId.getVersion(), duplicateResourceResponse.getStatus()));
                    }
                    packages.set(i, newResourceLocation);
                }
            }

            // Use createDocument() — bypasses broken Response.getLocation() for eddi://
            // URIs
            IResourceId newAgentId = restVersionInfo.createDocument(agentConfig);
            URI createdUri = RestUtilities.createURI(resourceURI, newAgentId.getId(), versionQueryParam, newAgentId.getVersion());
            createDocumentDescriptorForDuplicate(documentDescriptorStore, resourceAccessGuard, id, version, createdUri);

            return Response.created(createdUri).location(createdUri)
                    .header("X-Resource-URI", createdUri.toString()).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Extracts the created resource URI from a Response, trying multiple strategies
     * since {@code getLocation()} returns null for {@code eddi://} scheme URIs.
     */
    private URI extractCreatedUri(Response response) {
        URI location = response.getLocation();
        if (location != null)
            return location;

        String header = response.getHeaderString("X-Resource-URI");
        if (header != null && !header.isBlank())
            return URI.create(header);

        if (response.hasEntity()) {
            Object entity = response.getEntity();
            if (entity instanceof String s && !s.isBlank()) {
                try {
                    return URI.create(s);
                } catch (Exception ignored) {
                    /* not a URI */ }
            }
        }
        return null;
    }

    @Override
    public Response deleteAgent(String id, Integer version, Boolean permanent, Boolean cascade) {
        // Before the cascade, not after — see RestVersionInfo.requireOwnAccess.
        restVersionInfo.requireOwnAccess(id);
        if (cascade) {
            // Cascade-delete all schedules for this Agent first
            try {
                int deletedSchedules = scheduleStore.deleteSchedulesByAgentId(id);
                if (deletedSchedules > 0) {
                    log.infof("Cascade-deleted %d schedule(s) for Agent %s", deletedSchedules, id);
                }
            } catch (Exception e) {
                log.warnf("Failed to cascade-delete schedules for Agent %s: %s", id, e.getMessage());
            }

            try {
                AgentConfiguration agentConfig = agentStore.read(id, version);
                for (URI workflowUri : agentConfig.getWorkflows()) {
                    IResourceId resourceId = RestUtilities.extractResourceId(workflowUri);
                    try {
                        // Check if this package is referenced by other agents
                        var referencingAgents = agentStore.getAgentDescriptorsContainingWorkflow(resourceId.getId(), resourceId.getVersion(), false);
                        if (referencingAgents.size() > 1) {
                            log.infof("Skipping cascade-delete of package %s (v%d) — " + "still referenced by %d other agent(s)", resourceId.getId(),
                                    resourceId.getVersion(), referencingAgents.size() - 1);
                            continue;
                        }

                        restWorkflowStore.deleteWorkflow(resourceId.getId(), resourceId.getVersion(), permanent, true);
                        log.infof("Cascade-deleted package %s (v%d) for Agent %s", resourceId.getId(), resourceId.getVersion(), id);
                    } catch (Exception e) {
                        log.warnf("Failed to cascade-delete package %s: %s", resourceId.getId(), e.getMessage());
                    }
                }
            } catch (IResourceStore.ResourceNotFoundException e) {
                log.warnf("Agent %s (v%d) not found for cascade — deleting Agent only", id, version);
            } catch (IResourceStore.ResourceStoreException e) {
                log.warnf("Error reading Agent %s for cascade: %s", id, e.getMessage());
            }
        }
        capabilityRegistryService.unregister(id);

        // Deliberately after the delete, which throws on a stale
        // or unknown version. Clearing first would strip a
        // still-live Agent of what it needs to redeploy.
        Response response = restVersionInfo.delete(id, version, permanent);

        // A record left behind makes the runtime retry a
        // doomed redeploy on every startup.
        try {
            int deletedDeployments = deploymentStore.deleteDeploymentInfos(id);
            if (deletedDeployments > 0) {
                log.infof("Cascade-deleted %d deployment record(s) for Agent %s", deletedDeployments, sanitize(id));
            }
        } catch (Exception e) {
            log.warnf("Failed to delete deployment record(s) for Agent %s: %s", sanitize(id), e.getMessage());
        }

        return response;
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return agentStore.getCurrentResourceId(id);
    }

    /**
     * Validate that cryptographic security flags are backed by a signing keypair.
     * <p>
     * Both {@code signInterAgentMessages} and {@code requirePeerVerification}
     * require an Ed25519 keypair on the agent's identity block. This validation
     * prevents enabling signing without the necessary infrastructure.
     *
     * @throws jakarta.ws.rs.BadRequestException
     *             if crypto is enabled without a keypair
     */
    private void validateSecurityFlags(AgentConfiguration config) {
        if (config.getSecurity() == null) {
            return;
        }
        var security = config.getSecurity();

        boolean anyCryptoEnabled = security.isSignInterAgentMessages()
                || security.isRequirePeerVerification();
        if (!anyCryptoEnabled) {
            return;
        }
        // Crypto is enabled — validate that a public key exists on the agent identity.
        // Key generation is done via POST /agentstore/{id}/signing/keys, which sets
        // the public key on the agent's identity block.
        var identity = config.getIdentity();
        boolean hasLegacyKey = identity != null && identity.getPublicKey() != null
                && !identity.getPublicKey().isBlank();
        boolean hasRotatedKeys = identity != null && identity.getKeys() != null
                && !identity.getKeys().isEmpty();
        if (!hasLegacyKey && !hasRotatedKeys) {
            throw new BadRequestException(
                    "Cryptographic identity features require a signing key. "
                            + "Generate one via POST /agentstore/{agentId}/signing/keys "
                            + "before enabling signInterAgentMessages "
                            + "or requirePeerVerification.");
        }
    }
}
