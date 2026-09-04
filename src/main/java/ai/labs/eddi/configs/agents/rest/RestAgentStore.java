/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

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
    private final AgentSigningService agentSigningService;
    private final String defaultTenantId;

    private static final Logger log = Logger.getLogger(RestAgentStore.class);

    @Inject
    public RestAgentStore(IAgentStore agentStore, IRestWorkflowStore restWorkflowStore, IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator, IScheduleStore scheduleStore, CapabilityRegistryService capabilityRegistryService,
            IDeploymentStore deploymentStore,
            ResourceAccessGuard resourceAccessGuard,
            AgentSigningService agentSigningService,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId) {
        this.resourceAccessGuard = resourceAccessGuard;
        restVersionInfo = new RestVersionInfo<>(resourceURI, agentStore, documentDescriptorStore, resourceAccessGuard);
        this.documentDescriptorStore = documentDescriptorStore;
        this.agentStore = agentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.scheduleStore = scheduleStore;
        this.capabilityRegistryService = capabilityRegistryService;
        this.deploymentStore = deploymentStore;
        this.agentSigningService = agentSigningService;
        this.defaultTenantId = defaultTenantId;
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
            throw malformedResourceUri(containingWorkflowUri);
        }

        try {
            // Post-filtered rather than query-filtered: this is a reverse-reference lookup
            // in the store, not a descriptor listing, so there is no AccessScope to hand
            // it. Unfiltered it is a cross-workspace enumeration — anyone holding one
            // resource URI could list every resource in the deployment that references it,
            // with the full descriptor payload. The page can come back short; that is the
            // right trade for a diagnostic listing bounded by how many things reference
            // one resource.
            //
            // filter/index/limit are applied here for the same reason, and used to be
            // accepted and then dropped: paging clients got the whole list back on every
            // page, and a resource referenced by thousands of workflows returned all of
            // them at once.
            return filterAndPage(visibleOnly(agentStore.getAgentDescriptorsContainingWorkflow(validatedResourceId.getId(),
                    validatedResourceId.getVersion(), includePreviousVersions)), filter, index, limit);
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
            // This store's own constant, qualified because the method parameter shadows
            // it. It was RestWorkflowStore.resourceURI — copied from the workflow-store
            // variant where it is correct — so the 400 body named a workflow URI built
            // from an AGENT id, which no resource has.
            URI uri = RestUtilities.createURI(IRestAgentStore.resourceURI, id, versionQueryParam, version);
            return Response.status(BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
        }
    }

    @Override
    public Response createAgent(AgentConfiguration agentConfiguration) {
        validateSecurityFlags(agentConfiguration);

        // createDocument() because the id and version are what this method needs.
        // NOT because Response.getLocation() is broken for eddi:// URIs — it is not;
        // RestWorkflowStoreCrudTest.duplicateDeepCopyWithParserDictionaries proves it
        // works with the real JAX-RS RuntimeDelegate.
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
        // Keep the normalised version: validateParameters maps 0 -> current, and
        // discarding the return value left agentStore.read(id, 0) matching nothing,
        // so the '0 means current' shorthand that PUT and DELETE accept 404'd here.
        version = restVersionInfo.validateParameters(id, version);
        try {
            AgentConfiguration agentConfig = agentStore.read(id, version);
            validateSecurityFlags(agentConfig);
            if (deepCopy) {
                List<URI> packages = agentConfig.getWorkflows();
                for (int i = 0; i < packages.size(); i++) {
                    URI workflowUri = packages.get(i);
                    IResourceId wfResId = RestUtilities.extractResourceId(workflowUri);
                    Response duplicateResourceResponse = restWorkflowStore.duplicateWorkflow(wfResId.getId(), wfResId.getVersion(), true);
                    URI newResourceLocation = extractCreatedUri(duplicateResourceResponse);
                    if (newResourceLocation == null) {
                        throw new IllegalStateException(String.format(
                                "Could not determine created workflow URI while duplicating workflow '%s' (id=%s, version=%s); response status=%s",
                                workflowUri, wfResId.getId(), wfResId.getVersion(), duplicateResourceResponse.getStatus()));
                    }
                    packages.set(i, newResourceLocation);
                }
            }

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
     * Extracts the created resource URI from a create/duplicate response.
     *
     * <p>
     * {@code getLocation()} is the answer, and it works fine for {@code eddi://}
     * URIs — contrary to what the comments here used to claim, and as
     * {@code RestWorkflowStoreCrudTest.duplicateDeepCopyWithParserDictionaries}
     * shows against the real JAX-RS {@code RuntimeDelegate}. Every production
     * caller reaches this with a {@code Response} built by
     * {@code RestWorkflowStore.duplicateWorkflow}, which sets it.
     * </p>
     *
     * <p>
     * The header and entity fallbacks are kept as defence for a response built
     * without a {@code Location} — they cost nothing and this runs on a path that
     * creates resources before it needs the answer — but they are not a workaround
     * for a broken JAX-RS, and nothing should be written to depend on them. Note
     * {@code RestImportService} is NOT such a caller: it uses direct CDI store
     * calls, so no proxy ever strips anything from its responses.
     * </p>
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

        // Resolve '0' to the current version up front. restVersionInfo.delete() does
        // it too, but only at the very END — so with ?version=0 the cascade read
        // version 0, found nothing, and skipped itself with a WARN while the delete
        // went through: cascade=true silently did not cascade.
        version = restVersionInfo.validateParameters(id, version);

        // Read BEFORE the delete — afterwards there is no current row to ask whether
        // this Agent had signing key material to clean out of the vault. Only asked
        // on the permanent path; see the vault cleanup below for why.
        boolean deleteSigningKeys = Boolean.TRUE.equals(permanent) && hasSigningIdentity(id, version);

        if (cascade && isCurrentVersion(id, version)) {
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

                        // NEVER permanent down a cascade, whatever the request asked for.
                        // The guard above answers a VERSION-scoped question ("who references
                        // W?version=1?") while permanent=true performs an ID-scoped delete —
                        // deleteAllPermanently drops every version and every history row. An
                        // agent pinning W?version=2 is invisible to the check and loses its
                        // workflow with nothing to recover from. Soft-deleting the pinned
                        // version keeps the two scopes in agreement; permanently removing a
                        // shared resource stays an explicit, non-cascading request against
                        // that resource.
                        restWorkflowStore.deleteWorkflow(resourceId.getId(), resourceId.getVersion(), false, true);
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

        Response response = restVersionInfo.delete(id, version, permanent);

        // Deliberately after the delete, which throws on a stale or unknown version.
        // Clearing first would strip a still-live Agent of the capabilities that
        // capabilityMatch rules and A2A discovery route on, with no error anywhere.
        capabilityRegistryService.unregister(id);

        // Permanent deletes ONLY, and only for Agents that actually declare key
        // material.
        //
        // Deleting an Agent used to leave its Ed25519 private key in the vault
        // forever — nothing else ever removes it, and the entry outlives the config
        // that documented what it was for. But destroying it is irreversible in a way
        // the config itself is not: there is no key-generation endpoint (see
        // validateSecurityFlags), so a private key that is gone cannot be recreated,
        // and an Agent restored from a ZIP backup would come back with a public key
        // whose private half no longer exists — signInterAgentMessages permanently
        // broken. A soft delete is the deliberately recoverable path and must stay
        // recoverable, by the same rule the cascade above follows: destroying shared
        // or unrecreatable material is an explicit request, never a side effect.
        // The residual leak (soft-delete then never purge) is the recoverable failure
        // of the two.
        if (deleteSigningKeys) {
            agentSigningService.deleteKeyPair(defaultTenantId, id);
        }

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

    /**
     * Whether this Agent declares signing key material, and therefore has a private
     * key in the vault that its deletion must also remove.
     *
     * <p>
     * Best-effort: an unreadable Agent simply reports {@code false}. Failing the
     * delete because the key-cleanup probe could not run would be the wrong trade —
     * a leaked vault entry is recoverable, a config that cannot be deleted is not.
     * </p>
     */
    private boolean hasSigningIdentity(String id, Integer version) {
        try {
            AgentConfiguration config = agentStore.read(id, version);
            var identity = config == null ? null : config.getIdentity();
            return identity != null && ((identity.getPublicKey() != null && !identity.getPublicKey().isBlank())
                    || (identity.getKeys() != null && !identity.getKeys().isEmpty()));
        } catch (Exception e) {
            // Every failure mode reports "no key material", deliberately: not found,
            // store unreachable, an already soft-deleted Agent whose read answers null.
            // The alternative is failing the delete on a probe, and a leaked vault entry
            // is recoverable where a config that cannot be deleted is not.
            log.debugf("Could not read Agent %s (v%s) to check for signing key material: %s", sanitize(id), version, e.getMessage());
            return false;
        }
    }

    /**
     * Whether {@code version} is the Agent's live version, i.e. whether a cascade
     * addressed at it may run.
     *
     * <p>
     * The cascade tears down schedules and workflows <em>before</em>
     * {@code restVersionInfo.delete()} — the only place the version is checked —
     * has run. {@code agentStore.read(id, staleVersion)} succeeds through the
     * history fallback, so {@code DELETE ?version=1&cascade=true} against an Agent
     * that is at v2 (a stale browser tab, an optimistic-lock race) deleted v1's
     * workflows and schedules and only then answered 409: the live Agent kept its
     * config and lost everything it pointed at. So a stale version is refused here,
     * with nothing touched.
     * </p>
     *
     * @return true when the versions match; false when the Agent has no live
     *         version at all (already soft-deleted — the cascade is skipped so a
     *         {@code permanent=true} purge of the remaining history still works)
     * @throws WebApplicationException
     *             409, carrying the current resource URI, when the Agent is live at
     *             a different version
     */
    private boolean isCurrentVersion(String id, Integer version) {
        IResourceId current;
        try {
            current = agentStore.getCurrentResourceId(id);
        } catch (IResourceStore.ResourceNotFoundException e) {
            // No current row at all: the Agent is already soft-deleted. Skip the
            // cascade — nothing live is left to protect — and let the delete below
            // purge the remaining history. Dereferencing the missing id instead would
            // NPE partway through a destructive operation.
            return false;
        }

        if (!current.getVersion().equals(version)) {
            throw RestUtilities.createConflictException(resourceURI, current);
        }
        return true;
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
     * @throws BadRequestException
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
        // There is deliberately no endpoint named here: none exists.
        // AgentSigningService.generateKeyPair has no production caller, so the only way
        // to satisfy this check today is to write the key material into the config
        // yourself. Pointing at POST /agentstore/{agentId}/signing/keys — which returns
        // 404 — sent operators looking for a route that was never built.
        var identity = config.getIdentity();
        boolean hasLegacyKey = identity != null && identity.getPublicKey() != null
                && !identity.getPublicKey().isBlank();
        boolean hasRotatedKeys = identity != null && identity.getKeys() != null
                && !identity.getKeys().isEmpty();
        if (!hasLegacyKey && !hasRotatedKeys) {
            throw new BadRequestException(
                    "Cryptographic identity features require a signing key. "
                            + "Set identity.publicKey (or identity.keys) on this agent before enabling "
                            + "signInterAgentMessages or requirePeerVerification. "
                            + "Note that the matching private key must also be in the secrets vault under "
                            + "'agent-signing-key:{agentId}', or signing will fail at runtime.");
        }
    }
}
