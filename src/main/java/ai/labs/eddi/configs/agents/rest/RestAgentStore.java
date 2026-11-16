/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.model.AgentCascadeRequest;
import ai.labs.eddi.configs.agents.model.AgentCascadeResult;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.exception.ErrorResponses;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.RatingConfigValidator;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

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
    private static final String SIGNING_KEYS_PATH = "/agentstore/agents/{id}/signing-keys";
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
    private final AgentCascadeService agentCascadeService;
    private final IAgentFactory agentFactory;
    private final String defaultTenantId;

    private static final Logger log = Logger.getLogger(RestAgentStore.class);

    @Inject
    public RestAgentStore(IAgentStore agentStore, IRestWorkflowStore restWorkflowStore, IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator, IScheduleStore scheduleStore, CapabilityRegistryService capabilityRegistryService,
            IDeploymentStore deploymentStore,
            ResourceAccessGuard resourceAccessGuard,
            AgentSigningService agentSigningService,
            IAgentFactory agentFactory,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            AgentCascadeService agentCascadeService) {
        this.agentCascadeService = agentCascadeService;
        this.agentFactory = agentFactory;
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
    public List<DocumentDescriptor> readAgentDescriptors(String filter, Integer index, Integer limit, String space, String ownership) {
        return restVersionInfo.readDescriptors(filter, index, limit, space, ownership);
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
    public Response updateAgent(String id, Integer version, AgentConfiguration agentConfiguration, Boolean compatible) {
        RatingConfigValidator.validate(agentConfiguration);
        // The agent's own EDIT check first: the workflow lookup below answers "exists
        // / does not exist", which a caller with no rights to this agent must not be
        // able to use as an oracle for arbitrary workflow ids — and the signing-key
        // check asks the vault about this agent's key.
        restVersionInfo.requireEditAccess(id);
        // Engine-owned fields: keep what is stored, never what the body says, for
        // every caller — a person's PUT, an import merge, a sync, the cascade and
        // autonomy all arrive here.
        // - dynamicOrigin is the provenance teardown_agent and group cleanup rely on
        // before they delete an agent: an editor must not be able to erase the
        // marker (the sub-agent would then outlive its discussion) or plant one.
        // - identity holds the public keys peers verify this agent's signatures
        // with. Whoever could write it could plant a key whose private half they
        // hold, delete the revocation an admin made, or shrink the list a permanent
        // delete sizes its vault sweep from. It changes only through the admin-only
        // signing-key endpoints; an import or sync keeps this instance's identity
        // too, since its private keys are in this instance's vault.
        AgentConfiguration stored = storedCurrentVersion(id);
        agentConfiguration.setDynamicOrigin(stored != null ? stored.getDynamicOrigin() : null);
        agentConfiguration.setIdentity(stored != null ? stored.getIdentity() : null);
        validateSecurityFlags(id, agentConfiguration);
        requireWorkflowsExist(agentConfiguration);
        Response response = updateWithCompatibility(id, version, agentConfiguration, Boolean.TRUE.equals(compatible));
        capabilityRegistryService.register(id, agentConfiguration);
        return response;
    }

    /**
     * {@code RestVersionInfo.update}, calling the agent store's update that takes
     * the compatibility declaration — the generic one cannot carry it.
     */
    private Response updateWithCompatibility(String id, Integer version, AgentConfiguration agentConfiguration, boolean compatible) {
        version = restVersionInfo.validateParameters(id, version);
        RuntimeUtilities.checkNotNull(agentConfiguration, "document");
        try {
            Integer newVersion = agentStore.update(id, version, agentConfiguration, compatible);
            URI newResourceUri = RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
            return Response.ok().location(newResourceUri).build();
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceModifiedException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response updateResourceInAgent(String id, Integer version, URI resourceURI, Boolean compatible) {
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
            return updateAgent(id, version, agentConfig, compatible);
        } else {
            // This store's own constant, qualified because the method parameter shadows
            // it. It was RestWorkflowStore.resourceURI — copied from the workflow-store
            // variant where it is correct — so the 400 body named a workflow URI built
            // from an AGENT id, which no resource has.
            URI uri = RestUtilities.createURI(IRestAgentStore.resourceURI, id, versionQueryParam, version);
            return Response.status(BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
        }
    }

    /**
     * A 400 in the JSON shape the other client-error mappers use
     * ({@code {"error":"bad_request","message":…}}), so the operator and the
     * Manager read a cascade refusal the same way as any other malformed request.
     * Carrying an entity, it passes through {@code ClientErrorExceptionMapper}
     * untouched.
     */
    private static BadRequestException cascadeBadRequest(String message) {
        return new BadRequestException(message, ErrorResponses.badRequest(message));
    }

    @Override
    public AgentCascadeResult cascadeAgentChanges(String id, Integer version, AgentCascadeRequest request) {
        if (request == null) {
            throw cascadeBadRequest("A request body with resourceMappings is required");
        }
        if (version == null) {
            throw cascadeBadRequest("version is required");
        }
        // The agent's own EDIT check first, for the reason updateAgent gives: what
        // follows answers "exists / does not exist" for the agent and its workflows.
        restVersionInfo.requireEditAccess(id);
        Deployment.Environment environment;
        try {
            environment = Deployment.Environment.parseStrict(request.environment());
        } catch (IllegalArgumentException e) {
            throw cascadeBadRequest(e.getMessage());
        }
        try {
            return agentCascadeService.cascade(id, version, request.resourceMappings(), Boolean.TRUE.equals(request.redeploy()), environment,
                    Boolean.TRUE.equals(request.compatible()));
        } catch (AgentCascadeService.CascadeException e) {
            switch (e.kind()) {
                case NOT_FOUND :
                    throw new NotFoundException(e.getMessage());
                case CONFLICT :
                    throw new ClientErrorException(e.getMessage(), Response.Status.CONFLICT);
                case UNAVAILABLE :
                    throw new WebApplicationException(e.getMessage(), e, Response.status(Response.Status.SERVICE_UNAVAILABLE)
                            .type(MediaType.APPLICATION_JSON_TYPE).entity(ErrorResponses.body("service_unavailable", e.getMessage())).build());
                case FAILED :
                    throw new InternalServerErrorException(e.getMessage(), e);
                default :
                    throw cascadeBadRequest(e.getMessage());
            }
        }
    }

    /**
     * The agent's current stored version, the source of its engine-owned fields
     * ({@code dynamicOrigin}, {@code identity}), or {@code null} when it does not
     * exist (the update then fails on its own). A store failure propagates rather
     * than being read as "nothing stored": silently dropping the fields would turn
     * a transient error into a sub-agent that group cleanup can no longer delete,
     * or an agent whose keys are gone.
     */
    private AgentConfiguration storedCurrentVersion(String id) {
        try {
            IResourceId current = agentStore.getCurrentResourceId(id);
            if (current == null) {
                return null;
            }
            return agentStore.read(id, current.getVersion());
        } catch (IResourceStore.ResourceNotFoundException e) {
            return null;
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response createAgent(AgentConfiguration agentConfiguration) {
        validateSecurityFlags(null, agentConfiguration);
        RatingConfigValidator.validate(agentConfiguration);
        requireWorkflowsExist(agentConfiguration);
        // A new agent's identity is not the caller's to declare either: no vault key
        // can exist for an id that does not exist yet, so any key in the body is one
        // whose private half someone else holds. See updateAgent.
        agentConfiguration.setIdentity(null);

        // createDocument() because the id and version are what this method needs.
        // NOT because Response.getLocation() is broken for eddi:// URIs — it is not;
        // RestWorkflowStoreCrudTest.duplicateDeepCopyWithParserDictionaries proves it
        // works with the real JAX-RS RuntimeDelegate.
        IResourceId resourceId = restVersionInfo.createDocument(agentConfiguration);
        URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());

        try {
            capabilityRegistryService.register(resourceId.getId(), agentConfiguration);
        } catch (Exception e) {
            log.debugf("Could not register capabilities for new agent: %s", sanitize(e.getMessage()));
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
            RatingConfigValidator.validate(agentConfig);
            // The copy is an agent a person made, not one create_sub_agent
            // provisioned: it must not inherit the original's provenance.
            agentConfig.setDynamicOrigin(null);
            // Nor the original's identity. Its private keys are in the vault under the
            // ORIGINAL's id, so the copy could never sign with them, and verifiers would
            // take the copy's public keys for the original's. The copy signs once an
            // admin generates a key of its own and turns signing back on.
            agentConfig.setIdentity(null);
            if (agentConfig.getSecurity() != null) {
                agentConfig.getSecurity().setSignInterAgentMessages(false);
            }
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

        // Read BEFORE the delete — afterwards there is no row at all to ask whether
        // this Agent had signing key material to clean out of the vault, nor which
        // rotated versions it declares. Only asked on the permanent path; see the
        // vault cleanup below for why. null means "no key material".
        List<Integer> signingKeyVersions = Boolean.TRUE.equals(permanent) ? signingKeyVersions(id, version) : null;

        // Collected BEFORE the delete for the same reason: the deployment records are
        // removed below, and afterwards nothing names the versions still running.
        List<DeploymentInfo> liveDeployments = liveDeploymentsOf(id);

        // DECIDED before the delete, EXECUTED after it — the same split, and for the
        // same reason, as RestWorkflowStore.deleteWorkflow.
        //
        // isCurrentVersion() is a check-then-act. A concurrent update committing
        // between it and restVersionInfo.delete() — a PUT, or the 10-second
        // deployment sweep touching this Agent — left the schedules deleted and every
        // exclusively owned workflow (plus, through deleteWorkflow's own cascade, its
        // extensions) torn down while the delete answered 409 "nothing was deleted":
        // the live Agent kept its config and lost everything it pointed at, and the
        // 409 contract IRestAgentStore documents was false on this path. The store's
        // own delete is version-checked (HistorizedResourceStore.delete raises
        // ResourceModifiedException), so making it the gate means an Agent that moved
        // on raises before anything it references has been touched.
        //
        // Deciding first is what keeps the reference guard honest: the "> 1" below
        // counts this Agent among a workflow's referrers, which it only is while it
        // still exists.
        List<IResourceId> cascadeTargets = cascade && isCurrentVersion(id, version) ? planCascade(id, version) : null;

        Response response = restVersionInfo.delete(id, version, permanent);

        // After the delete, which throws on a stale or unknown version: a refused
        // delete must leave a running Agent running.
        undeployLiveDeployments(id, liveDeployments);

        if (cascadeTargets != null) {
            try {
                int deletedSchedules = scheduleStore.deleteSchedulesByAgentId(id);
                if (deletedSchedules > 0) {
                    log.infof("Cascade-deleted %d schedule(s) for Agent %s", deletedSchedules, sanitize(id));
                }
            } catch (Exception e) {
                log.warnf("Failed to cascade-delete schedules for Agent %s: %s", sanitize(id), sanitize(e.getMessage()));
            }

            for (IResourceId target : cascadeTargets) {
                if (stillReferencedAfterAgentDelete(target)) {
                    continue;
                }
                try {
                    // NEVER permanent down a cascade, whatever the request asked for.
                    // The guard in planCascade answers a VERSION-scoped question ("who
                    // references W?version=2?") while permanent=true performs an
                    // ID-scoped delete — deleteAllPermanently drops every version and
                    // every history row. Soft-deleting the current version keeps the two
                    // scopes in agreement; permanently removing a shared resource stays
                    // an explicit, non-cascading request against that resource.
                    restWorkflowStore.deleteWorkflow(target.getId(), target.getVersion(), false, true);
                    log.infof("Cascade-deleted package %s (v%d) for Agent %s", sanitize(target.getId()), target.getVersion(),
                            sanitize(id));
                } catch (Exception e) {
                    log.warnf("Failed to cascade-delete package %s: %s", sanitize(target.getId()), sanitize(e.getMessage()));
                }
            }
        }

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
        // the config itself is not: a private key that is gone cannot be recreated
        // (createSigningKey makes a NEW one), and an Agent restored from a ZIP backup
        // would come back with a public key whose private half no longer exists —
        // unable to sign until an admin generates it a new key. A soft delete is the
        // deliberately recoverable path and must stay
        // recoverable, by the same rule the cascade above follows: destroying shared
        // or unrecreatable material is an explicit request, never a side effect.
        // The residual leak (soft-delete then never purge) is the recoverable failure
        // of the two.
        if (signingKeyVersions != null) {
            // Bounded BY the declared versions rather than a blind 1..100 sweep — but
            // not narrowed to exactly them: a rotation whose follow-up config write
            // failed leaves a vault entry that no keys[] entry names. See
            // AgentSigningService.versionsToSweep.
            agentSigningService.deleteKeyPair(defaultTenantId, id, signingKeyVersions);
        }

        // A record left behind makes the runtime retry a
        // doomed redeploy on every startup.
        try {
            int deletedDeployments = deploymentStore.deleteDeploymentInfos(id);
            if (deletedDeployments > 0) {
                log.infof("Cascade-deleted %d deployment record(s) for Agent %s", deletedDeployments, sanitize(id));
            }
        } catch (Exception e) {
            log.warnf("Failed to delete deployment record(s) for Agent %s: %s", sanitize(id), sanitize(e.getMessage()));
        }

        return response;
    }

    /**
     * Every deployment of {@code id} that is still live, from the deployment
     * records and from the runtime itself.
     * <p>
     * Deleting an Agent removed its deployment records but never undeployed it, so
     * a deleted Agent kept answering in its environment until the process restarted
     * — and nothing could stop it, because every undeploy path starts by reading
     * the configuration that was just deleted. The runtime is asked as well as the
     * records because a version can be running without a record (a record write
     * that failed, or one a previous delete already removed).
     */
    private List<DeploymentInfo> liveDeploymentsOf(String id) {
        List<DeploymentInfo> live = new ArrayList<>();
        try {
            for (DeploymentInfo info : deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)) {
                if (id.equals(info.getAgentId())) {
                    live.add(info);
                }
            }
        } catch (Exception e) {
            log.warnf("Could not read deployment records for Agent %s before deleting it: %s", sanitize(id), sanitize(e.getMessage()));
        }
        for (Deployment.Environment environment : Deployment.Environment.values()) {
            try {
                for (IAgent agent : agentFactory.getAllLatestAgents(environment)) {
                    if (id.equals(agent.getAgentId()) && live.stream().noneMatch(
                            info -> info.getEnvironment() == environment && Objects.equals(info.getAgentVersion(), agent.getAgentVersion()))) {
                        DeploymentInfo info = new DeploymentInfo();
                        info.setAgentId(id);
                        info.setAgentVersion(agent.getAgentVersion());
                        info.setEnvironment(environment);
                        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
                        live.add(info);
                    }
                }
            } catch (Exception e) {
                log.warnf("Could not list running Agents in %s before deleting Agent %s: %s", environment, sanitize(id), sanitize(e.getMessage()));
            }
        }
        return live;
    }

    private void undeployLiveDeployments(String id, List<DeploymentInfo> liveDeployments) {
        for (DeploymentInfo info : liveDeployments) {
            try {
                agentFactory.undeployAgent(info.getEnvironment(), id, info.getAgentVersion());
                log.infof("Undeployed Agent %s v%d from %s because the Agent was deleted", sanitize(id), info.getAgentVersion(),
                        info.getEnvironment());
            } catch (Exception e) {
                log.warnf("Failed to undeploy deleted Agent %s v%d from %s: %s", sanitize(id), info.getAgentVersion(), info.getEnvironment(),
                        sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Rejects a create or update that names a workflow which does not exist.
     * <p>
     * {@code AgentStore} checks the shape of each URI; this checks that it
     * resolves. Accepting a dangling reference with 201 moved the failure to deploy
     * time, where it surfaced as an opaque deployment ERROR with nothing pointing
     * back at the typo. It lives here rather than in the store because ZIP import
     * and sync write through the store directly, after creating the workflows they
     * reference.
     * <p>
     * The lookup goes through the access-guarded REST store on purpose: pointing an
     * agent at a workflow the caller cannot view is refused (403) rather than
     * accepted — assembling an agent from someone else's private workflow is not a
     * reference the caller is entitled to make.
     */
    private void requireWorkflowsExist(AgentConfiguration agentConfiguration) {
        List<URI> workflows = agentConfiguration.getWorkflows();
        if (workflows == null) {
            return;
        }
        for (URI workflowUri : workflows) {
            IResourceId resourceId = workflowUri == null ? null : validateUri(workflowUri.toString());
            if (resourceId == null) {
                // Malformed: AgentStore rejects it with the precise message.
                continue;
            }
            try {
                restWorkflowStore.readWorkflow(resourceId.getId(), resourceId.getVersion());
            } catch (Exception e) {
                if (e instanceof IResourceStore.ResourceNotFoundException || e instanceof NotFoundException) {
                    throw new BadRequestException(Response.status(BAD_REQUEST).type(MediaType.TEXT_PLAIN)
                            .entity("workflows references a workflow that does not exist: " + workflowUri).build());
                }
                throw sneakyThrow(e);
            }
        }
    }

    /**
     * Re-asks "does any Agent still reference this workflow?" immediately before
     * the irreversible cascade delete.
     *
     * <p>
     * {@link #planCascade} answers that question BEFORE this Agent is deleted — it
     * has to, so this Agent still counts among the referrers — and an Agent
     * created, or re-pointed at this workflow, in the window between the two would
     * then have its newly shared workflow (and, through {@code deleteWorkflow}'s
     * own cascade, that workflow's extensions) torn down underneath it. This Agent
     * is gone by the time this runs, so the honest expectation is ZERO referrers
     * rather than the {@code > 1} the plan phase used: the reverse lookup drops a
     * soft-deleted or erased referrer (see
     * {@code AbstractResourceStore.isStaleReference}).
     * </p>
     *
     * <p>
     * It narrows the window to the instants between this query and the delete
     * rather than closing it, and FAILS CLOSED — a lookup that cannot answer is not
     * a licence to delete.
     * </p>
     *
     * @return true when the workflow must NOT be cascade-deleted
     */
    private boolean stillReferencedAfterAgentDelete(IResourceId target) {
        List<DocumentDescriptor> referencingAgents;
        try {
            referencingAgents = agentStore.getAgentDescriptorsContainingWorkflow(target.getId(), target.getVersion(), true);
        } catch (Exception e) {
            log.warnf("Re-check of package %s after the Agent delete failed — NOT cascade-deleting it: %s",
                    sanitize(target.getId()), sanitize(e.getMessage()));
            return true;
        }
        if (referencingAgents == null) {
            log.warnf("Re-check of package %s after the Agent delete returned no answer — NOT cascade-deleting it",
                    sanitize(target.getId()));
            return true;
        }
        if (!referencingAgents.isEmpty()) {
            log.infof("Skipping cascade-delete of package %s (v%d) — it became referenced by %d Agent(s) after the cascade was planned",
                    sanitize(target.getId()), target.getVersion(), referencingAgents.size());
            return true;
        }
        return false;
    }

    /**
     * Decides — before the Agent is deleted — which of its workflows the cascade
     * may remove.
     *
     * <p>
     * Deciding and deleting are separate steps so the reference guard still counts
     * this Agent among the referrers (hence {@code > 1}) while the Agent itself is
     * deleted first; see {@link #deleteAgent}.
     * </p>
     *
     * @return the workflows to delete, addressed at their CURRENT version — never
     *         null, so "nothing to cascade" and "no cascade requested" stay
     *         distinct at the call site
     */
    private List<IResourceId> planCascade(String id, Integer version) {
        List<IResourceId> targets = new ArrayList<>();
        try {
            AgentConfiguration agentConfig = agentStore.read(id, version);
            for (URI workflowUri : agentConfig.getWorkflows()) {
                IResourceId pinned = RestUtilities.extractResourceId(workflowUri);
                try {
                    // Resolve the reference to the version that EXISTS. An agent's
                    // workflow reference is version-pinned and is NOT re-pointed when the
                    // workflow is edited, so an agent pinning W?version=1 while W is at v2
                    // is the normal state. Against the pinned version both steps here were
                    // wrong at once: the reference check asked who else pins a version
                    // nobody may still pin, and deleteWorkflow's own isCurrentVersion()
                    // then answered 409 — swallowed as a bare WARN. cascade=true
                    // consequently deleted nothing for any workflow that had ever been
                    // edited.
                    IResourceId target = restWorkflowStore.getCurrentResourceId(pinned.getId());

                    // includePreviousVersions=true, starting from the CURRENT version:
                    // the reverse lookup walks that version down to 1, so an agent still
                    // pinning an older version protects the workflow, and there is no
                    // newer version above for the walk to miss.
                    var referencingAgents = agentStore.getAgentDescriptorsContainingWorkflow(target.getId(), target.getVersion(), true);
                    if (referencingAgents.size() > 1) {
                        log.infof("Skipping cascade-delete of package %s (v%d) — still referenced by %d other agent(s)",
                                sanitize(target.getId()), target.getVersion(), referencingAgents.size() - 1);
                        continue;
                    }
                    targets.add(target);
                } catch (IResourceStore.ResourceNotFoundException e) {
                    log.infof("Skipping cascade-delete of package %s — it has no live version left", sanitize(pinned.getId()));
                } catch (Exception e) {
                    // FAIL CLOSED per workflow, exactly as the workflow store's own
                    // cascade does: a reference check that cannot answer is not a licence
                    // to delete, and letting the throwable out here would abort the whole
                    // request before the Agent itself had been deleted.
                    log.warnf("Failed to plan cascade-delete of package %s: %s", sanitize(pinned.getId()), sanitize(e.getMessage()));
                }
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.warnf("Agent %s (v%d) not found for cascade — deleting Agent only", sanitize(id), version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.warnf("Error reading Agent %s for cascade: %s", sanitize(id), sanitize(e.getMessage()));
        }
        return targets;
    }

    /**
     * The rotated signing key versions this Agent declares, or {@code null} when it
     * declares no key material at all and its deletion has no vault entry to clean
     * up.
     *
     * <p>
     * Read through {@code readIncludingDeleted}, NOT {@code read}.
     * {@code HistorizedResourceStore.read} throws {@code ResourceNotFoundException}
     * for a history row flagged deleted — that is, for every soft-deleted Agent —
     * and "soft-delete first, then purge with {@code permanent=true}" is exactly
     * the two-step flow {@link #isCurrentVersion} documents as ordinary. On that
     * flow the probe therefore answered "no key material", {@code deleteKeyPair}
     * never ran, and the Ed25519 private key stayed in the vault after the config
     * and its whole history had been erased: the leak this cleanup exists to close,
     * surviving on the only recommended path to closing it.
     * </p>
     *
     * <p>
     * An empty list means "key material, but no rotated versions" — the legacy
     * unversioned key — and is deliberately distinct from {@code null}.
     * </p>
     *
     * <p>
     * Best-effort: an unreadable Agent reports {@code null}. Failing the delete
     * because the key-cleanup probe could not run would be the wrong trade — a
     * leaked vault entry is recoverable, a config that cannot be deleted is not.
     * </p>
     */
    private List<Integer> signingKeyVersions(String id, Integer version) {
        try {
            AgentConfiguration config = agentStore.readIncludingDeleted(id, version);
            var identity = config == null ? null : config.getIdentity();
            if (identity == null) {
                return null;
            }
            boolean hasLegacyKey = identity.getPublicKey() != null && !identity.getPublicKey().isBlank();
            List<AgentPublicKey> keys = identity.getKeys();
            boolean hasRotatedKeys = keys != null && !keys.isEmpty();
            if (!hasLegacyKey && !hasRotatedKeys) {
                return null;
            }
            return hasRotatedKeys ? keys.stream().map(AgentPublicKey::version).toList() : List.of();
        } catch (Exception e) {
            // Every remaining failure mode reports "no key material", deliberately:
            // not found, store unreachable, a document that will not deserialize. The
            // alternative is failing the delete on a probe, and a leaked vault entry
            // is recoverable where a config that cannot be deleted is not.
            log.debugf("Could not read Agent %s (v%s) to check for signing key material: %s", sanitize(id), version,
                    sanitize(e.getMessage()));
            return null;
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
    public void requireCurrentVersionAccess(String id) {
        restVersionInfo.requireViewAccess(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return agentStore.getCurrentResourceId(id);
    }

    @Override
    public Response createSigningKey(String id, Integer version) {
        restVersionInfo.requireEditAccess(id);
        version = restVersionInfo.validateParameters(id, version);
        // Refused BEFORE the vault is touched. The next key version is computed from
        // the configuration read here, so a stale one would number the new key from
        // an outdated list.
        AgentConfiguration config = currentVersionForKeyChange(id, version);

        var identity = config.getIdentity() != null ? config.getIdentity() : new AgentConfiguration.AgentIdentity();
        int nextVersion = identity.signingKeyVersion() + 1;
        if (nextVersion > AgentPublicKey.MAX_VERSION) {
            throw new ClientErrorException("Agent " + id + " has used every signing key version up to " + AgentPublicKey.MAX_VERSION
                    + "; no further key can be generated for it.", Response.Status.CONFLICT);
        }
        AgentSigningService.GeneratedKey generated;
        try {
            generated = agentSigningService.generateKey(defaultTenantId, id, nextVersion);
        } catch (AgentSigningService.AgentSigningException e) {
            log.warnf("Could not generate a signing key for Agent %s: %s", sanitize(id), sanitize(e.getMessage()));
            throw vaultUnavailable("Could not generate a signing key: " + e.getMessage());
        }

        // Older keys stay, unexpired: entries they signed are verified by the exact
        // version they declare, and only the newest key signs from now on.
        AgentPublicKey key = AgentPublicKey.createCurrent(generated.version(), generated.publicKeyB64());
        List<AgentPublicKey> keys = new ArrayList<>(identity.getKeys() != null ? identity.getKeys() : List.of());
        keys.add(key);
        identity.setKeys(keys);
        config.setIdentity(identity);
        Integer newVersion;
        try {
            newVersion = agentStore.update(id, version, config, true);
        } catch (IResourceStore.ResourceModifiedException | IResourceStore.ResourceNotFoundException e) {
            // Refused before anything was written: nothing declares the stored key, so
            // take it back out of the vault.
            agentSigningService.discardKey(defaultTenantId, id, generated.version());
            throw sneakyThrow(e);
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            // The outcome is unknown: a socket error or a lost commit acknowledgement can
            // arrive AFTER the write was applied. Discarding the key then would leave an
            // agent that declares a key its vault no longer holds — it could not sign,
            // and every later save would be refused — so look before discarding.
            newVersion = versionIfSaved(id, "Saving signing key v" + key.version(), saved -> declaresKey(saved, key), e);
            if (newVersion == null) {
                agentSigningService.discardKey(defaultTenantId, id, generated.version());
                throw sneakyThrow(e);
            }
        }
        advanceDescriptor(id, version, newVersion);
        log.infof("Generated signing key v%d for Agent %s (now at version %d)", generated.version(), sanitize(id), newVersion);
        return keyChangeResponse(id, newVersion, key);
    }

    @Override
    public Response revokeSigningKey(String id, Integer keyVersion, Integer version) {
        restVersionInfo.requireEditAccess(id);
        version = restVersionInfo.validateParameters(id, version);
        if (keyVersion == null) {
            throw new BadRequestException("keyVersion is required");
        }
        AgentConfiguration config = currentVersionForKeyChange(id, version);
        var identity = config.getIdentity();
        String notDeclared = "Agent " + id + " declares no signing key v" + keyVersion
                + (keyVersion == 0 ? " and no legacy signing key (identity.publicKey)" : "");
        if (identity == null) {
            // No identity block declares no key at all: answered before anything below
            // reads or writes the identity.
            throw new NotFoundException(notDeclared);
        }

        // The legacy unversioned key (keyVersion 0) has no revoked flag to set, so
        // revoking it removes it: an entry signed as v0 then names no key and is not
        // trusted. An identity.keys entry that is itself declared as v0 — malformed,
        // but what a peer checks a v0 signature against — is revoked with it.
        boolean legacy = keyVersion == 0 && identity.getPublicKey() != null && !identity.getPublicKey().isBlank();
        List<AgentPublicKey> keys = identity.getKeys() != null ? identity.getKeys() : List.of();
        List<AgentPublicKey> entries = keys.stream().filter(k -> k.version() == keyVersion).toList();
        if (!legacy && entries.isEmpty()) {
            throw new NotFoundException(notDeclared);
        }
        AgentPublicKey revoked = legacy ? new AgentPublicKey(0, identity.getPublicKey(), 0, 0, true) : entries.get(0).revoke();
        if (!legacy && entries.stream().allMatch(AgentPublicKey::revoked)) {
            // Already revoked: nothing to write, and the answer names the version that
            // says so. The private key is still deleted: that delete is best effort
            // below, so revoking again is how a key the vault kept is retried.
            agentSigningService.discardKey(defaultTenantId, id, keyVersion);
            return keyChangeResponse(id, version, revoked);
        }
        String legacyKey = legacy ? identity.getPublicKey() : null;
        if (legacy) {
            identity.setPublicKey(null);
        }
        // Every entry of that version: a list that names a version twice must not
        // keep a trusted copy of it.
        identity.setKeys(keys.stream().map(k -> k.version() == keyVersion ? k.revoke() : k).toList());

        Integer newVersion;
        try {
            newVersion = agentStore.update(id, version, config, true);
        } catch (IResourceStore.ResourceModifiedException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            // The outcome is unknown, as when generating a key. Rethrowing a write that
            // landed would leave the descriptor behind, and a retry could not repair it
            // (the old version is refused as stale, the new one is already revoked). If
            // it did not land, the key is still declared, so its private half stays.
            newVersion = versionIfSaved(id, "Revoking signing key v" + keyVersion,
                    saved -> !trustsKey(saved, keyVersion, legacyKey), e);
            if (newVersion == null) {
                throw sneakyThrow(e);
            }
        }
        advanceDescriptor(id, version, newVersion);
        // The private half can sign nothing anyone will accept any more; it goes
        // after the configuration that says so is written, so a failed write leaves
        // a key that is still declared and still usable. Best effort: a key left
        // behind is swept by the agent's permanent delete.
        agentSigningService.discardKey(defaultTenantId, id, keyVersion);
        log.infof("Revoked signing key v%d of Agent %s (now at version %d)", keyVersion, sanitize(id), newVersion);
        return keyChangeResponse(id, newVersion, revoked);
    }

    /**
     * The agent's configuration at {@code version}, refused with 409 unless that is
     * its current version: a key change computed from a stale configuration would
     * write over whatever changed since.
     */
    private AgentConfiguration currentVersionForKeyChange(String id, Integer version) {
        try {
            IResourceId current = agentStore.getCurrentResourceId(id);
            if (current == null) {
                throw new NotFoundException("Agent " + id + " has no current version");
            }
            if (!current.getVersion().equals(version)) {
                throw RestUtilities.createConflictException(resourceURI, current);
            }
            catchUpDescriptor(id, version);
            return agentStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Repairs what an earlier key change left behind when its agent write landed
     * but {@link #advanceDescriptor} did not: the agent at {@code agentVersion},
     * its descriptor still one version back. The 500 that change answered names the
     * new version, so retrying there (or at the version a 409 hands back) is the
     * way out — it moves the descriptor first, before this change writes the agent
     * again on top of a descriptor that would then be two versions behind. Only
     * that one-behind shape is repaired; a descriptor that is missing or further
     * off is left as it is, for the change to fail on as before.
     */
    private void catchUpDescriptor(String id, int agentVersion) {
        IResourceId descriptorId;
        try {
            descriptorId = documentDescriptorStore.getCurrentResourceId(id);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return;
        }
        if (descriptorId == null || descriptorId.getVersion() == null || descriptorId.getVersion() != agentVersion - 1) {
            return;
        }
        log.warnf("Agent %s is at version %d but its descriptor names version %d (an earlier key change did not finish): moving it",
                sanitize(id), agentVersion, agentVersion - 1);
        advanceDescriptor(id, agentVersion - 1, agentVersion);
    }

    /**
     * After a key change whose write failed with an outcome that is unknown: the
     * agent's current version if it shows the change ({@code saved}), so the caller
     * can carry on as if the write had answered; {@code null} if it did not land,
     * for the caller to decide what happens to the private key. If the agent cannot
     * be read to find out, the vault is left as it is — a key left behind is swept
     * by the agent's permanent delete, where a declared key that is gone breaks
     * signing — and {@code failure} is rethrown.
     */
    private Integer versionIfSaved(String id, String change, Predicate<AgentConfiguration> saved, Exception failure) {
        AgentConfiguration current;
        IResourceId currentId;
        try {
            currentId = agentStore.getCurrentResourceId(id);
            current = currentId == null ? null : agentStore.read(id, currentId.getVersion());
        } catch (Exception readFailure) {
            log.errorf("%s of Agent %s failed (%s) and the agent cannot be read to tell whether it was saved (%s). The vault is left as it is.",
                    change, sanitize(id), sanitize(failure.getMessage()), sanitize(readFailure.getMessage()));
            throw sneakyThrow(failure);
        }
        if (current == null || !saved.test(current)) {
            return null;
        }
        log.warnf("%s of Agent %s reported a failure (%s), but version %d shows it: the save landed.", change, sanitize(id),
                sanitize(failure.getMessage()), currentId.getVersion());
        return currentId.getVersion();
    }

    private static boolean declaresKey(AgentConfiguration config, AgentPublicKey key) {
        var identity = config.getIdentity();
        return identity != null && identity.getKeys() != null && identity.getKeys().stream()
                .anyMatch(k -> k.version() == key.version() && Objects.equals(k.publicKeyB64(), key.publicKeyB64()));
    }

    /**
     * Whether {@code config} still trusts key {@code keyVersion}: an entry of that
     * version that is not revoked, or — for {@code legacyKey}, the legacy key being
     * revoked — that key still declared as {@code identity.publicKey}.
     */
    private static boolean trustsKey(AgentConfiguration config, int keyVersion, String legacyKey) {
        var identity = config.getIdentity();
        if (identity == null) {
            return false;
        }
        return (legacyKey != null && legacyKey.equals(identity.getPublicKey()))
                || (identity.getKeys() != null && identity.getKeys().stream().anyMatch(k -> k.version() == keyVersion && !k.revoked()));
    }

    /**
     * Moves the agent's descriptor from {@code fromVersion} to {@code toVersion},
     * as {@code DocumentDescriptorFilter} does for a {@code PUT}. A key change
     * writes the agent in process and answers a {@code POST}, which that filter
     * treats as a creation or not at all, so without this the descriptor stays
     * behind: every later save of the agent is answered 404, and a conversation on
     * the new version cannot start.
     */
    private void advanceDescriptor(String id, int fromVersion, int toVersion) {
        try {
            DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(id, fromVersion);
            descriptor.setLastModifiedOn(new Date());
            descriptor.setResource(RestUtilities.createURI(resourceURI, id, versionQueryParam, toVersion));
            DescriptorAccess.rebuildIndex(descriptor);
            documentDescriptorStore.updateDescriptor(id, fromVersion, descriptor);
        } catch (Exception e) {
            log.errorf(e, "Agent %s was saved as version %d, but its descriptor could not be moved from version %d", sanitize(id), toVersion,
                    fromVersion);
            throw new InternalServerErrorException("Agent " + id + " was saved as version " + toVersion
                    + ", but its descriptor still names version " + fromVersion + ": " + e.getMessage(), e);
        }
    }

    /**
     * 200, not 201: nothing new is created at {@code Location}, and a POST answered
     * 201 is what {@code DocumentDescriptorFilter} takes for a creation — it would
     * try to create a second descriptor for this agent and turn the answer into a
     * 500.
     */
    private static Response keyChangeResponse(String id, int agentVersion, AgentPublicKey key) {
        URI location = RestUtilities.createURI(resourceURI, id, versionQueryParam, agentVersion);
        return Response.ok(key).location(location).build();
    }

    @Override
    public String signingKeyProblem(String id) {
        AgentConfiguration stored = storedCurrentVersion(id);
        if (stored == null) {
            throw new NotFoundException("Agent " + id + " not found");
        }
        return signingKeyProblem(id, stored.getIdentity());
    }

    /**
     * Refuses {@code signInterAgentMessages} unless the agent can actually sign —
     * see {@link #signingKeyProblem(String, AgentConfiguration.AgentIdentity)}.
     * <p>
     * Without this the save succeeded and every turn after it logged a WARN and
     * wrote an UNSIGNED transcript entry, while the configuration said messages
     * were signed. {@code requirePeerVerification} needs nothing of the agent's
     * own: verifying uses the speakers' public keys.
     *
     * @param agentId
     *            the agent being saved, or {@code null} for one being created —
     *            which has no key yet, since keys are stored under the agent's id
     * @throws BadRequestException
     *             if signing is on and the agent cannot sign
     * @throws WebApplicationException
     *             503, if the vault could not answer
     */
    private void validateSecurityFlags(String agentId, AgentConfiguration config) {
        if (config.getSecurity() == null || !config.getSecurity().isSignInterAgentMessages()) {
            return;
        }
        if (agentId == null) {
            throw new BadRequestException("A new agent has no signing key yet. Create it with signInterAgentMessages off, "
                    + "generate its key with POST " + SIGNING_KEYS_PATH + " (eddi-admin), then turn signInterAgentMessages on.");
        }
        String problem = signingKeyProblem(agentId, config.getIdentity());
        if (problem != null) {
            throw new BadRequestException(problem);
        }
    }

    /**
     * Why an agent with this identity cannot sign right now, or {@code null} when
     * it can. It can when:
     * <ul>
     * <li>every key it declares that is not revoked is well-formed: a version from
     * 1 to {@link AgentPublicKey#MAX_VERSION}, declared once, holding a Base64
     * X.509 Ed25519 public key. A peer trusts every declared key, not only the one
     * that signs;</li>
     * <li>the key version it would sign with ({@code identity.signingKeyVersion()},
     * the same rule the signer uses) has a public key, is not revoked and is inside
     * its validity window — the signer refuses a key outside it and does not fall
     * back to an older one;</li>
     * <li>the vault holds the private key that signs as that public key.</li>
     * </ul>
     * Each answer names the way out.
     *
     * @throws WebApplicationException
     *             503, if the vault could not answer
     */
    private String signingKeyProblem(String agentId, AgentConfiguration.AgentIdentity identity) {
        String malformed = malformedKey(identity);
        if (malformed != null) {
            return malformed + " Revoke it with POST " + SIGNING_KEYS_PATH + "/{keyVersion}/revoke (eddi-admin), or turn signInterAgentMessages off.";
        }
        int keyVersion = identity != null ? identity.signingKeyVersion() : 0;
        String publicKey = identity != null ? identity.getKeyForVersion(keyVersion) : null;
        if (publicKey == null || publicKey.isBlank()) {
            return "signInterAgentMessages needs a signing key, and this agent declares none. Generate one with POST "
                    + SIGNING_KEYS_PATH + " (eddi-admin), then save with signInterAgentMessages on.";
        }
        String key = keyVersion > 0 ? "Signing key v" + keyVersion : "The legacy signing key (identity.publicKey)";
        String fix = " Generate a new key with POST " + SIGNING_KEYS_PATH + " (eddi-admin), or turn signInterAgentMessages off.";
        AgentPublicKey signingKey = identity.signingKey();
        if (signingKey != null && signingKey.revoked()) {
            return key + " is revoked." + fix;
        }
        if (signingKey != null && !signingKey.isValidAt(System.currentTimeMillis())) {
            return key + " is outside its validity window (validFromMs " + signingKey.validFromMs() + ", validUntilMs "
                    + signingKey.validUntilMs() + ")." + fix;
        }
        AgentSigningService.SigningKeyStatus status;
        try {
            status = agentSigningService.checkSigningKey(defaultTenantId, agentId, keyVersion, publicKey);
        } catch (AgentSigningService.AgentSigningException e) {
            throw vaultUnavailable("Could not check this agent's signing key: " + e.getMessage());
        }
        return switch (status) {
            case USABLE -> null;
            case INVALID_PUBLIC_KEY -> key + " is not a Base64 X.509 Ed25519 public key." + fix;
            case NOT_IN_VAULT -> key + " has no private key in the secrets vault." + fix;
            case UNREADABLE -> key + " is in the secrets vault but is not an Ed25519 private key." + fix;
            case MISMATCH -> key + " does not match the private key in the secrets vault." + fix;
        };
    }

    /**
     * The first declared key that is not revoked and is not well-formed, described,
     * or {@code null} when there is none. Only the signing-key endpoints write
     * keys, and they write well-formed ones; this finds what an agent stored before
     * identity became engine-owned may still carry.
     */
    private static String malformedKey(AgentConfiguration.AgentIdentity identity) {
        if (identity == null || identity.getKeys() == null) {
            return null;
        }
        Set<Integer> seen = new HashSet<>();
        for (AgentPublicKey key : identity.getKeys()) {
            if (key.revoked()) {
                continue;
            }
            if (key.version() < 1 || key.version() > AgentPublicKey.MAX_VERSION) {
                return "Signing key v" + key.version() + " has a version outside 1 to " + AgentPublicKey.MAX_VERSION + ".";
            }
            if (!seen.add(key.version())) {
                return "Signing key v" + key.version() + " is declared more than once.";
            }
            if (!AgentSigningService.isEd25519PublicKey(key.publicKeyB64())) {
                return "Signing key v" + key.version() + " is not a Base64 X.509 Ed25519 public key.";
            }
        }
        return null;
    }

    /**
     * A 503 whose JSON body carries {@code message}: the exception mapper that
     * copies a message into the body handles 4xx only.
     */
    private static WebApplicationException vaultUnavailable(String message) {
        return new WebApplicationException(message, Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON_TYPE).entity(ErrorResponses.body("vault_unavailable", message)).build());
    }
}
