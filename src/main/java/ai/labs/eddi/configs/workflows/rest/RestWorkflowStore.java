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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.*;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

@ApplicationScoped
public class RestWorkflowStore implements IRestWorkflowStore {
    private static final String KEY_CONFIG = "config";
    private static final String KEY_URI = "uri";
    private static final String KEY_TYPE = "type";
    private static final String KEY_DICTIONARIES = "dictionaries";
    private static final String DICTIONARY_TYPE_HOST = "ai.labs.parser.dictionaries.regular";
    /**
     * How many resources a cascade deliberately did not delete (still referenced,
     * not routable, or the delete itself failed). Absent when the cascade removed
     * everything it walked.
     */
    private static final String CASCADE_SKIPPED_HEADER = "X-Cascade-Skipped";
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

        CascadePlan plan = cascade && isCurrentVersion(id, version) ? planCascade(id, version) : CascadePlan.empty();

        // The workflow itself is deleted FIRST, its referenced resources only once
        // that succeeded. isCurrentVersion() above is a check-then-act: a concurrent
        // update committing between it and the delete left the extension resources
        // torn down while the delete answered 409 for a stale version — the exact
        // destructive outcome the version check exists to prevent. The store's own
        // delete is version-checked (HistorizedResourceStore.delete raises
        // ResourceModifiedException), so a workflow that moved on in the meantime
        // now raises before anything it references has been touched.
        Response response = restVersionInfo.delete(id, version, permanent);

        int skipped = plan.skipped();
        for (URI candidate : plan.toDelete()) {
            if (stillReferencedAfterParentDelete(candidate)) {
                skipped++;
                continue;
            }
            if (!deleteCascadedResource(candidate)) {
                skipped++;
            }
        }
        if (skipped > 0) {
            // The operator's only feedback is this response: a cascade that skipped
            // half the graph used to answer exactly like one that deleted all of it,
            // with the difference visible only in the server log.
            return Response.fromResponse(response).header(CASCADE_SKIPPED_HEADER, skipped).build();
        }
        return response;
    }

    /**
     * Whether {@code version} is the workflow's live version, i.e. whether a
     * cascade addressed at it may run.
     *
     * <p>
     * {@code workflowStore.read} falls back to history, so without this a stale
     * version's extension resources were collected and deleted for a request that
     * {@code restVersionInfo.delete()} was going to reject anyway. Refuse first,
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

    /**
     * What a cascade will delete, and how many referenced resources it decided to
     * leave alone.
     *
     * <p>
     * {@code toDelete} is a SET, and deliberately: two workflow steps may name the
     * same resource (two httpcalls steps on one apicalls config is ordinary), and
     * the second delete of one resource finds it already soft-deleted,
     * {@code HistorizedResourceStore.delete} raises
     * {@code ResourceNotFoundException}, and the delete loop counted that as a
     * skip. A cascade that removed everything it walked then answered
     * {@code X-Cascade-Skipped: 1} — the header exists to stop the response lying
     * about the cascade, so it must not lie the other way either.
     * </p>
     */
    private record CascadePlan(Collection<URI> toDelete, int skipped) {
        static CascadePlan empty() {
            return new CascadePlan(Set.of(), 0);
        }
    }

    /**
     * Decides — before anything is deleted — which of this workflow's referenced
     * resources the cascade may remove.
     *
     * <p>
     * Deciding and deleting are separate steps so that the reference guard still
     * counts this workflow among the referrers (hence {@code > 1} below) while the
     * workflow is deleted first; see {@link #deleteWorkflow}.
     * </p>
     */
    private CascadePlan planCascade(String id, Integer version) {
        // LinkedHashSet: de-duplicated (see CascadePlan) while keeping the traversal
        // order, so the delete order stays the workflow's own step order.
        Set<URI> toDelete = new LinkedHashSet<>();
        int[] skipped = {0};
        try {
            WorkflowConfiguration workflowConfig = workflowStore.read(id, version);
            for (var workflowStep : workflowConfig.getWorkflowSteps()) {
                // Parser dictionaries
                URI type = workflowStep.getType();
                if (type != null && "ai.labs.parser".equals(type.getHost())) {
                    collectParserDictionaries(workflowStep, toDelete, skipped);
                }

                // Main extension resource (via config.uri)
                Map<String, Object> config = workflowStep.getConfig();
                if (!isNullOrEmpty(config)) {
                    Object resourceUriObj = config.get(KEY_URI);
                    if (!isNullOrEmpty(resourceUriObj)) {
                        collectCascadeCandidate(URI.create(resourceUriObj.toString()), toDelete, skipped);
                    }
                }
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.warnf("Workflow %s (v%d) not found for cascade — deleting workflow only", sanitize(id), version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.warnf("Error reading workflow %s for cascade: %s", sanitize(id), sanitize(e.getMessage()));
        }
        return new CascadePlan(toDelete, skipped[0]);
    }

    private void collectParserDictionaries(WorkflowStep workflowStep, Set<URI> toDelete, int[] skipped) {
        // A parser step with no extensions block at all is legal stored data (Jackson
        // leaves it null for an absent or explicitly null "extensions"). This runs on
        // the destructive cascade, where an exception aborts it mid-way — after some
        // resources have already been deleted — so a step that does not have the
        // expected shape is skipped, not thrown on. Pattern-matched rather than
        // cast for exactly that reason: a stored '"dictionaries": {}' or a list
        // holding anything but objects is a ClassCastException on a blind cast, and
        // updateResourceInWorkflow already walks this shape defensively.
        Map<String, Object> extensions = workflowStep.getExtensions();
        if (extensions == null || !(extensions.get(KEY_DICTIONARIES) instanceof List<?> dictionaries)) {
            return;
        }
        for (Object entry : dictionaries) {
            if (!(entry instanceof Map<?, ?> dictionary)) {
                continue;
            }
            var dictType = dictionary.get(KEY_TYPE);
            if (dictType == null || !DICTIONARY_TYPE_HOST.equals(URI.create(dictType.toString()).getHost())) {
                continue;
            }
            if (!(dictionary.get(KEY_CONFIG) instanceof Map<?, ?> config)) {
                continue;
            }
            Object dictionaryUriObj = config.get(KEY_URI);
            if (!isNullOrEmpty(dictionaryUriObj)) {
                collectCascadeCandidate(URI.create(dictionaryUriObj.toString()), toDelete, skipped);
            }
        }
    }

    /**
     * Adds a referenced resource to the cascade, if nothing else still references
     * it.
     *
     * <p>
     * The reference is resolved to the version that EXISTS before either question
     * is asked. A stored step keeps pinning {@code ?version=1} after the rule set
     * it names has been edited to v2 — the normal state, not an edge case — and
     * both halves of this guard used to run against the pinned version: the
     * reference check asked who else pins a version nobody may still pin, and the
     * delete was then rejected as stale by the store and swallowed as a WARN. So
     * {@code cascade=true} quietly deleted nothing at all for every resource that
     * had ever been edited, while the API description said it had soft-deleted
     * them.
     * </p>
     *
     * <p>
     * The cascade never deletes permanently, whatever the request asked for.
     * {@code deleteAllPermanently} is ID-scoped — every version and every history
     * row — while this guard can only ever speak for the versions it can see.
     * Soft-deleting the current version keeps guard and effect on the same scope;
     * erasing a shared resource stays an explicit, non-cascading request against
     * that resource.
     * </p>
     */
    private void collectCascadeCandidate(URI pinnedUri, Set<URI> toDelete, int[] skipped) {
        IResourceStore.IResourceId current;
        try {
            current = resourceClientLibrary.getCurrentResourceId(pinnedUri);
        } catch (Exception e) {
            // Fails closed PER RESOURCE, like the reference check below, rather than
            // letting the throwable out of planCascade. getCurrentResourceId only
            // absorbs ResourceNotFoundException: the Mongo store's getCurrentVersion
            // does `new ObjectId(id)`, which raises IllegalArgumentException for the
            // 18-23 char hex and UUID-shaped ids RestUtilities.isValidId accepts, and
            // a transport failure surfaces as MongoException. Uncaught, ONE
            // hand-written or imported step reference turned the whole
            // cascade=true delete into a 500 — before the workflow itself was
            // deleted, so the workflow became undeletable-with-cascade until someone
            // edited the stray reference out.
            log.errorf(e, "Could not resolve %s to a live version — NOT cascade-deleting it", pinnedUri);
            skipped[0]++;
            return;
        }
        if (current == null) {
            // Either the type is not one this cascade can route, or the resource has
            // no live version left. Both mean "nothing here to delete", and neither
            // is a reason to guess at the pinned version.
            log.infof("Skipping cascade-delete of %s — no live version to delete, or its type is not routable", pinnedUri);
            skipped[0]++;
            return;
        }

        URI currentUri = withVersion(pinnedUri, current.getVersion());
        List<DocumentDescriptor> referencingWorkflows;
        try {
            // includePreviousVersions=true: the reverse lookup walks the resource's
            // versions from this one down to 1, so a workflow still pinning an OLDER
            // version protects it. Starting from the CURRENT version means there is
            // no newer version above it for the walk to miss.
            referencingWorkflows = workflowStore.getWorkflowDescriptorsContainingResource(currentUri.toString(), true);
        } catch (Exception e) {
            // FAIL CLOSED. A cascade-delete is irreversible and this is the only
            // thing standing between it and a config another workflow still uses —
            // if the reference check cannot answer, we do not get to guess.
            //
            // ERROR, not WARN: a backend whose reverse-lookup query is broken
            // answers this way for EVERY resource, which silently turns cascade
            // delete into a permanent no-op. That must be loud, and the cause must
            // be in the log — hence the throwable rather than just its message.
            log.errorf(e, "Reference check for %s failed — NOT cascade-deleting it", currentUri);
            skipped[0]++;
            return;
        }

        if (referencingWorkflows == null) {
            log.warnf("Reference check for %s returned no answer — NOT cascade-deleting it", currentUri);
            skipped[0]++;
            return;
        }

        if (referencingWorkflows.size() > 1) {
            log.infof("Skipping cascade-delete of resource %s — still referenced by %d other workflow(s)", currentUri,
                    referencingWorkflows.size() - 1);
            skipped[0]++;
            return;
        }

        toDelete.add(currentUri);
    }

    /** The same resource, addressed at {@code version}. */
    private static URI withVersion(URI resourceUri, Integer version) {
        String raw = resourceUri.toString();
        int queryStart = raw.indexOf('?');
        return URI.create((queryStart >= 0 ? raw.substring(0, queryStart) : raw) + versionQueryParam + version);
    }

    /**
     * Re-asks "is anyone still using this?" immediately before the irreversible
     * child delete.
     *
     * <p>
     * {@link #planCascade} answers that question BEFORE the parent workflow is
     * deleted — it has to, so the parent still counts among the referrers — and a
     * workflow created, or re-pointed at this resource, in the window between the
     * two would then have its newly shared configuration soft-deleted underneath
     * it. The parent is gone by the time this runs, so the honest expectation here
     * is ZERO referrers, not the {@code > 1} the plan phase used: the reverse
     * lookup drops a soft-deleted or erased referrer (see
     * {@code AbstractResourceStore.isStaleReference}).
     * </p>
     *
     * <p>
     * This narrows the window to the instants between this query and the delete; it
     * does not close it. Closing it needs a deployment-wide lock over reference
     * writes, which would serialise configuration editing against every cascade.
     * </p>
     *
     * <p>
     * FAILS CLOSED, like the plan-phase check it repeats: a lookup that throws or
     * answers null has told us nothing, and "nothing" is not a licence to delete.
     * </p>
     *
     * @return true when the resource must NOT be deleted
     */
    private boolean stillReferencedAfterParentDelete(URI currentUri) {
        List<DocumentDescriptor> referencingWorkflows;
        try {
            referencingWorkflows = workflowStore.getWorkflowDescriptorsContainingResource(currentUri.toString(), true);
        } catch (Exception e) {
            log.errorf(e, "Re-check of %s after the workflow delete failed — NOT cascade-deleting it", currentUri);
            return true;
        }
        if (referencingWorkflows == null) {
            log.warnf("Re-check of %s after the workflow delete returned no answer — NOT cascade-deleting it", currentUri);
            return true;
        }
        if (!referencingWorkflows.isEmpty()) {
            log.infof("Skipping cascade-delete of %s — it became referenced by %d workflow(s) after the cascade was planned",
                    currentUri, referencingWorkflows.size());
            return true;
        }
        return false;
    }

    /** @return true when the resource was actually deleted */
    private boolean deleteCascadedResource(URI resourceUri) {
        try {
            resourceClientLibrary.deleteResource(resourceUri, false);
            log.infof("Cascade-deleted resource %s", resourceUri);
            return true;
        } catch (Exception e) {
            log.warnf("Failed to cascade-delete resource %s: %s", resourceUri, e.getMessage());
            return false;
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
        // Pattern-matched, not cast, for the same reason collectParserDictionaries
        // is: a stored '"dictionaries": {}' or a list element that is not an object
        // makes a blind (List<Map<…>>) cast throw, here after earlier steps of the
        // deep copy have already created and persisted sub-resources.
        Map<String, Object> extensions = workflowStep.getExtensions();
        if (extensions == null || !(extensions.get(KEY_DICTIONARIES) instanceof List<?> dictionaries)) {
            return;
        }
        for (Object entry : dictionaries) {
            if (!(entry instanceof Map<?, ?> dictionary)) {
                continue;
            }
            // Same null guard as collectParserDictionaries — the two walked the same
            // stored shape but disagreed about whether "type" may be absent.
            var dictTypeObj = dictionary.get(KEY_TYPE);
            if (dictTypeObj == null) {
                continue;
            }
            URI type = URI.create(dictTypeObj.toString());
            if (DICTIONARY_TYPE_HOST.equals(type.getHost()) && dictionary.get(KEY_CONFIG) instanceof Map<?, ?> configMap) {
                @SuppressWarnings("unchecked")
                var config = (Map<String, Object>) configMap;
                Object dictionaryUriObj = config.get(KEY_URI);
                if (!isNullOrEmpty(dictionaryUriObj)) {
                    var newDictionaryLocation = duplicateResource(dictionaryUriObj);
                    config.put(KEY_URI, newDictionaryLocation);
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
