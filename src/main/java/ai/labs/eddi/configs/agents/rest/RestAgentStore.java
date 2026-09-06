/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
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
import java.util.ArrayList;
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

        // Read BEFORE the delete — afterwards there is no row at all to ask whether
        // this Agent had signing key material to clean out of the vault, nor which
        // rotated versions it declares. Only asked on the permanent path; see the
        // vault cleanup below for why. null means "no key material".
        List<Integer> signingKeyVersions = Boolean.TRUE.equals(permanent) ? signingKeyVersions(id, version) : null;

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

        if (cascadeTargets != null) {
            try {
                int deletedSchedules = scheduleStore.deleteSchedulesByAgentId(id);
                if (deletedSchedules > 0) {
                    log.infof("Cascade-deleted %d schedule(s) for Agent %s", deletedSchedules, id);
                }
            } catch (Exception e) {
                log.warnf("Failed to cascade-delete schedules for Agent %s: %s", id, e.getMessage());
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
                    log.infof("Cascade-deleted package %s (v%d) for Agent %s", target.getId(), target.getVersion(), id);
                } catch (Exception e) {
                    log.warnf("Failed to cascade-delete package %s: %s", target.getId(), e.getMessage());
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
        // the config itself is not: there is no key-generation endpoint (see
        // validateSecurityFlags), so a private key that is gone cannot be recreated,
        // and an Agent restored from a ZIP backup would come back with a public key
        // whose private half no longer exists — signInterAgentMessages permanently
        // broken. A soft delete is the deliberately recoverable path and must stay
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
            log.warnf("Failed to delete deployment record(s) for Agent %s: %s", sanitize(id), e.getMessage());
        }

        return response;
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
            log.warnf("Re-check of package %s after the Agent delete failed — NOT cascade-deleting it: %s", target.getId(), e.getMessage());
            return true;
        }
        if (referencingAgents == null) {
            log.warnf("Re-check of package %s after the Agent delete returned no answer — NOT cascade-deleting it", target.getId());
            return true;
        }
        if (!referencingAgents.isEmpty()) {
            log.infof("Skipping cascade-delete of package %s (v%d) — it became referenced by %d Agent(s) after the cascade was planned",
                    target.getId(), target.getVersion(), referencingAgents.size());
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
                        log.infof("Skipping cascade-delete of package %s (v%d) — still referenced by %d other agent(s)", target.getId(),
                                target.getVersion(), referencingAgents.size() - 1);
                        continue;
                    }
                    targets.add(target);
                } catch (IResourceStore.ResourceNotFoundException e) {
                    log.infof("Skipping cascade-delete of package %s — it has no live version left", pinned.getId());
                } catch (Exception e) {
                    // FAIL CLOSED per workflow, exactly as the workflow store's own
                    // cascade does: a reference check that cannot answer is not a licence
                    // to delete, and letting the throwable out here would abort the whole
                    // request before the Agent itself had been deleted.
                    log.warnf("Failed to plan cascade-delete of package %s: %s", pinned.getId(), e.getMessage());
                }
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.warnf("Agent %s (v%d) not found for cascade — deleting Agent only", id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.warnf("Error reading Agent %s for cascade: %s", id, e.getMessage());
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
            log.debugf("Could not read Agent %s (v%s) to check for signing key material: %s", sanitize(id), version, e.getMessage());
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
