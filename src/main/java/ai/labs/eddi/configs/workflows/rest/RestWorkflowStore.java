/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;

import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.*;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

@ApplicationScoped
public class RestWorkflowStore implements IRestWorkflowStore {
    private static final String KEY_CONFIG = "config";
    private static final String KEY_URI = "uri";
    private final IWorkflowStore workflowStore;
    private final ResourceClientLibrary resourceClientLibrary;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<WorkflowConfiguration> restVersionInfo;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard resourceAccessGuard;

    private static final Logger log = Logger.getLogger(RestWorkflowStore.class);

    @Inject
    public RestWorkflowStore(IWorkflowStore workflowStore, ResourceClientLibrary resourceClientLibrary,
            IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator,
            ResourceAccessGuard resourceAccessGuard) {
        this.resourceAccessGuard = resourceAccessGuard;
        restVersionInfo = new RestVersionInfo<>(resourceURI, workflowStore, documentDescriptorStore, resourceAccessGuard);
        this.documentDescriptorStore = documentDescriptorStore;
        this.workflowStore = workflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.jsonSchemaCreator = jsonSchemaCreator;
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
            return Response.ok(jsonSchemaCreator.generateSchema(WorkflowConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readWorkflowDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public List<DocumentDescriptor> readWorkflowDescriptors(String filter, Integer index, Integer limit, String containingResourceUri,
                                                            Boolean includePreviousVersions) {

        if (validateUri(containingResourceUri) == null) {
            throw malformedResourceUri(containingResourceUri);
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
            return filterAndPage(
                    visibleOnly(workflowStore.getWorkflowDescriptorsContainingResource(containingResourceUri, includePreviousVersions)), filter,
                    index, limit);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public WorkflowConfiguration readWorkflow(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateWorkflow(String id, Integer version, WorkflowConfiguration workflowConfiguration) {
        return restVersionInfo.update(id, version, workflowConfiguration);
    }

    @Override
    public Response updateResourceInWorkflow(String id, Integer version, URI resourceURI) {
        // Must carry a real version, not merely a '?' — same guard and same reason as
        // the agent-store variant: a stored reference is matched by everything before
        // the query and then replaced by this URI, so '...?other=2' would overwrite a
        // versioned reference with a versionless one.
        String resourceURIWithoutVersion = RestUtilities.pathWithoutVersionQuery(resourceURI);
        if (resourceURIWithoutVersion == null) {
            return Response.status(BAD_REQUEST)
                    .entity("resourceURI must carry a version, e.g. '...?version=2'")
                    .type(MediaType.TEXT_PLAIN).build();
        }

        boolean updated = false;
        WorkflowConfiguration workflowConfig = readWorkflow(id, version);
        for (WorkflowStep workflowStep : workflowConfig.getWorkflowSteps()) {
            Map<String, Object> workflowStepConfig = workflowStep.getConfig();
            if (updateResourceURI(resourceURI, resourceURIWithoutVersion, workflowStepConfig)) {
                updated = true;
            }

            // Pattern-matched rather than blind-cast (as RestOrphanAdmin already walks
            // the same shape). A stored step with "extensions": null, or an extension
            // value that is an object rather than an array, turned this endpoint into a
            // 500 — and this is the endpoint the re-point cascade walks for every config
            // edit, so one malformed step blocked re-pointing the whole workflow.
            Map<String, Object> extensions = workflowStep.getExtensions();
            if (extensions == null) {
                continue;
            }
            for (Object extensionValue : extensions.values()) {
                if (!(extensionValue instanceof List<?> extensionElements)) {
                    continue;
                }
                for (Object extensionElement : extensionElements) {
                    if (extensionElement instanceof Map<?, ?> elementMap
                            && elementMap.get(KEY_CONFIG) instanceof Map<?, ?> configMap) {
                        @SuppressWarnings("unchecked")
                        var config = (Map<String, Object>) configMap;
                        if (updateResourceURI(resourceURI, resourceURIWithoutVersion, config)) {
                            updated = true;
                        }
                    }
                }
            }
        }

        if (updated) {
            return updateWorkflow(id, version, workflowConfig);
        } else {
            URI uri = RestUtilities.createURI(RestWorkflowStore.resourceURI, id, versionQueryParam, version);
            return Response.status(BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
        }
    }

    private boolean updateResourceURI(URI resourceURI, String resourceURIWithoutVersion, Map<String, Object> config) {
        // Null-tolerant on both sides: a step may have no config at all, and a
        // present-but-null "uri" (JSON `"uri": null`) used to NPE on uri.toString().
        if (config == null) {
            return false;
        }
        Object uri = config.get(KEY_URI);
        if (uri != null && uri.toString().startsWith(resourceURIWithoutVersion)) {
            // found resource URI to update
            config.put(KEY_URI, resourceURI);
            return true;
        }

        return false;
    }

    @Override
    public Response createWorkflow(WorkflowConfiguration workflowConfiguration) {
        return restVersionInfo.create(workflowConfiguration);
    }

    @Override
    public Response deleteWorkflow(String id, Integer version, Boolean permanent, Boolean cascade) {
        // Before the cascade, not after: restVersionInfo.delete() checks at the end,
        // by which point the referenced resources would already be gone.
        restVersionInfo.requireOwnAccess(id);

        // '0' means current, and resolving it here rather than only inside
        // restVersionInfo.delete() is what makes ?version=0&cascade=true cascade at
        // all — the read below would otherwise match nothing and skip silently.
        version = restVersionInfo.validateParameters(id, version);

        if (cascade && isCurrentVersion(id, version)) {
            try {
                WorkflowConfiguration workflowConfig = workflowStore.read(id, version);
                for (var workflowStep : workflowConfig.getWorkflowSteps()) {
                    // Delete parser dictionaries
                    URI type = workflowStep.getType();
                    if (type != null && "ai.labs.parser".equals(type.getHost())) {
                        deleteParserDictionaries(workflowStep);
                    }

                    // Delete main extension resource (via config.uri)
                    Map<String, Object> config = workflowStep.getConfig();
                    if (!isNullOrEmpty(config)) {
                        Object resourceUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(resourceUriObj)) {
                            deleteResourceSafely(URI.create(resourceUriObj.toString()));
                        }
                    }
                }
            } catch (IResourceStore.ResourceNotFoundException e) {
                log.warnf("Workflow %s (v%d) not found for cascade — deleting workflow only", id, version);
            } catch (IResourceStore.ResourceStoreException e) {
                log.warnf("Error reading workflow %s for cascade: %s", id, e.getMessage());
            }
        }
        return restVersionInfo.delete(id, version, permanent);
    }

    /**
     * Whether {@code version} is the workflow's live version, i.e. whether a
     * cascade addressed at it may run.
     *
     * <p>
     * Same ordering hazard as the agent store: {@code workflowStore.read} falls
     * back to history, so a stale version's extension resources were torn down
     * before {@code restVersionInfo.delete()} rejected the request. Refuse first,
     * touch nothing.
     * </p>
     *
     * @return true when the versions match; false when the workflow has no live
     *         version (already soft-deleted), so a {@code permanent=true} purge of
     *         the remaining history still works
     */
    private boolean isCurrentVersion(String id, Integer version) {
        IResourceStore.IResourceId current;
        try {
            current = workflowStore.getCurrentResourceId(id);
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Already soft-deleted: no live version to protect, so skip the cascade
            // and let the delete below purge whatever history is left.
            return false;
        }

        if (!current.getVersion().equals(version)) {
            throw RestUtilities.createConflictException(resourceURI, current);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void deleteParserDictionaries(WorkflowStep workflowStep) {
        // A parser step with no extensions block at all is legal stored data (Jackson
        // leaves it null for an absent or explicitly null "extensions"). This runs on
        // the destructive cascade, where an NPE aborts it mid-way — after some
        // resources have already been deleted — so the step is skipped, not thrown on.
        Map<String, Object> extensions = workflowStep.getExtensions();
        if (extensions == null) {
            return;
        }
        var dictionaries = (List<Map<String, Object>>) extensions.get("dictionaries");
        if (!isNullOrEmpty(dictionaries)) {
            for (var dictionary : dictionaries) {
                var dictType = dictionary.get("type");
                if (dictType != null && "ai.labs.parser.dictionaries.regular".equals(URI.create(dictType.toString()).getHost())) {
                    var config = (Map<String, Object>) dictionary.get("config");
                    if (!isNullOrEmpty(config)) {
                        Object dictionaryUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(dictionaryUriObj)) {
                            deleteResourceSafely(URI.create(dictionaryUriObj.toString()));
                        }
                    }
                }
            }
        }
    }

    /**
     * Soft-deletes a referenced resource, if nothing else still references it.
     *
     * <p>
     * The cascade never deletes permanently, whatever the request asked for. The
     * guard below is VERSION-scoped — it asks who references
     * {@code R?version=<pinned>} — while a permanent delete is ID-scoped:
     * {@code deleteAllPermanently} drops every version and every history row. A
     * workflow pinning a different version of the same rule set is invisible to the
     * guard and would lose it outright, unrecoverably. Soft-deleting the pinned
     * version keeps guard and effect on the same scope; erasing a shared resource
     * stays an explicit, non-cascading request against that resource.
     * </p>
     */
    private void deleteResourceSafely(URI resourceUri) {
        List<DocumentDescriptor> referencingWorkflows;
        try {
            // Is this resource still referenced by other workflows?
            referencingWorkflows = workflowStore.getWorkflowDescriptorsContainingResource(resourceUri.toString(), false);
        } catch (Exception e) {
            // FAIL CLOSED. A cascade-delete is irreversible and this is the only
            // thing standing between it and a config another workflow still uses —
            // if the reference check cannot answer, we do not get to guess.
            //
            // ERROR, not WARN: a backend whose reverse-lookup query is broken
            // answers this way for EVERY resource, which silently turns cascade
            // delete into a permanent no-op. That must be loud, and the cause must
            // be in the log — hence the throwable rather than just its message.
            log.errorf(e, "Reference check for %s failed — NOT cascade-deleting it", resourceUri);
            return;
        }

        if (referencingWorkflows == null) {
            log.warnf("Reference check for %s returned no answer — NOT cascade-deleting it", resourceUri);
            return;
        }

        if (referencingWorkflows.size() > 1) {
            log.infof("Skipping cascade-delete of resource %s — still referenced by %d other workflow(s)", resourceUri,
                    referencingWorkflows.size() - 1);
            return;
        }

        try {
            resourceClientLibrary.deleteResource(resourceUri, false);
            log.infof("Cascade-deleted resource %s", resourceUri);
        } catch (Exception e) {
            log.warnf("Failed to cascade-delete resource %s: %s", resourceUri, e.getMessage());
        }
    }

    @Override
    public Response duplicateWorkflow(String id, Integer version, Boolean deepCopy) {
        restVersionInfo.requireViewAccess(id);
        // Keep the normalised version — validateParameters maps 0 -> current, and
        // discarding it left workflowStore.read(id, 0) matching nothing, so the
        // documented '0 means current' shorthand 404'd here while PUT and DELETE
        // honoured it.
        version = restVersionInfo.validateParameters(id, version);
        try {
            WorkflowConfiguration workflowConfig = workflowStore.read(id, version);
            if (deepCopy) {
                for (var workflowStep : workflowConfig.getWorkflowSteps()) {
                    // Guarded exactly as deleteWorkflow guards it: a stored step without a
                    // type is accepted by WorkflowStore.create, and an unguarded getHost()
                    // made ?deepCopy=true a bare NPE/500 — after sub-resources earlier in
                    // the loop had already been created and persisted.
                    URI type = workflowStep.getType();
                    if (type != null && "ai.labs.parser".equals(type.getHost())) {
                        duplicateDictionaryInParser(workflowStep);
                    }

                    Map<String, Object> config = workflowStep.getConfig();
                    if (!isNullOrEmpty(config)) {
                        Object resourceUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(resourceUriObj)) {
                            var newResourceLocation = duplicateResource(resourceUriObj);
                            config.put(KEY_URI, newResourceLocation);
                        }
                    }
                }
            }

            // createDocument() because the id and version are what this method needs;
            // wrapping them in a Response only to unwrap it again would be the detour.
            // NOT because Response.getLocation() is broken for eddi:// URIs — it is not,
            // and duplicateResource() below depends on it working
            // (RestWorkflowStoreCrudTest.duplicateDeepCopyWithParserDictionaries pins
            // that with the real JAX-RS RuntimeDelegate).
            IResourceStore.IResourceId resourceId = restVersionInfo.createDocument(workflowConfig);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            createDocumentDescriptorForDuplicate(documentDescriptorStore, resourceAccessGuard, id, version, createdUri);

            return Response.created(createdUri).location(createdUri)
                    .header("X-Resource-URI", createdUri.toString())
                    .entity(createdUri.toString()).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    private void duplicateDictionaryInParser(WorkflowStep workflowStep) throws ServiceException {
        // Same guard as deleteParserDictionaries: a stored parser step may carry no
        // extensions block at all, and a deep copy that NPEs leaves the sub-resources
        // it already created behind.
        Map<String, Object> extensions = workflowStep.getExtensions();
        if (extensions == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        var dictionaries = (List<Map<String, Object>>) extensions.get("dictionaries");
        if (!isNullOrEmpty(dictionaries)) {
            for (var dictionary : dictionaries) {
                // Same null guard as deleteParserDictionaries — the two walked the same
                // stored shape but disagreed about whether "type" may be absent.
                var dictTypeObj = dictionary.get("type");
                if (dictTypeObj == null) {
                    continue;
                }
                URI type = URI.create(dictTypeObj.toString());
                if ("ai.labs.parser.dictionaries.regular".equals(type.getHost())) {
                    @SuppressWarnings("unchecked")
                    var config = (Map<String, URI>) dictionary.get("config");
                    if (!isNullOrEmpty(config)) {
                        Object dictionaryUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(dictionaryUriObj)) {
                            var newDictionaryLocation = duplicateResource(dictionaryUriObj);
                            config.put(KEY_URI, newDictionaryLocation);
                        }
                    }
                }
            }
        }
    }

    private URI duplicateResource(Object resourceUriObj) throws ServiceException {
        URI newResourceLocation = null;

        try {
            if (!isNullOrEmpty(resourceUriObj)) {
                URI oldResourceUri = URI.create(resourceUriObj.toString());

                Response duplicateResourceResponse = resourceClientLibrary.duplicateResource(oldResourceUri);

                newResourceLocation = duplicateResourceResponse.getLocation();

                var oldResourceId = RestUtilities.extractResourceId(oldResourceUri);
                createDocumentDescriptorForDuplicate(documentDescriptorStore, resourceAccessGuard, oldResourceId.getId(), oldResourceId.getVersion(),
                        newResourceLocation);
            }
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }

        if (isNullOrEmpty(newResourceLocation)) {
            String errorMsg = String.format("New resource for %s could not be created: the duplicate response carried no Location header.",
                    resourceUriObj);
            throw new ServiceException(errorMsg);
        }

        return newResourceLocation;
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return workflowStore.getCurrentResourceId(id);
    }
}
