/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.AccessScope;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;
import org.jboss.logging.Logger;

import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * The shared CRUD body behind all fifteen configuration resource types.
 *
 * <h3>This is where the authoring surface is guarded</h3> Every
 * {@code IRest*Store} delegates its list / read / update / delete here, so one
 * {@link ResourceAccessGuard} covers all fifteen types rather than each store
 * remembering to check. MCP tools that hold an injected facade inherit it
 * through the same beans; MCP tools that resolve a store through
 * {@code IRestInterfaceFactory} make a loopback HTTP call instead and are
 * subject to the endpoint's checks, not this object's.
 * <p>
 * The engine deliberately does <em>not</em> pass through this class: since the
 * runtime/authoring split, {@code ResourceClientLibrary.getResource} and the
 * runtime store services read {@link IResourceStore} directly. A conversation
 * turn runs under the chatting user's identity, and requiring them to own the
 * agent's configuration would break every shared agent.
 *
 * @author ginccc
 */
public class RestVersionInfo<T> implements IRestVersionInfo {
    private static final Logger LOGGER = Logger.getLogger(RestVersionInfo.class);

    private final String resourceURI;
    private final IResourceStore<T> resourceStore;
    protected final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard accessGuard;
    private final String resourceTypeLabel;

    public RestVersionInfo(String resourceURI, IResourceStore<T> resourceStore, IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard accessGuard) {
        this.resourceURI = resourceURI;
        this.resourceStore = resourceStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.accessGuard = accessGuard;
        this.resourceTypeLabel = toResourceTypeLabel(resourceURI);
    }

    /**
     * "ai.labs.agent" reads as noise in a 403 body; "agent" does not. Falls back to
     * the raw descriptor type if the URI is not in the expected shape.
     */
    private static String toResourceTypeLabel(String resourceURI) {
        String type = RestUtilities.extractDescriptorType(resourceURI);
        if (type == null || type.isBlank()) {
            return "resource";
        }
        int lastDot = type.lastIndexOf('.');
        return lastDot >= 0 && lastDot < type.length() - 1 ? type.substring(lastDot + 1) : type;
    }

    /**
     * The descriptors of the resources <em>this</em> store owns, restricted to what
     * the caller may see.
     * <p>
     * Prefer this over {@link #readDescriptors(String, String, Integer, Integer)}:
     * the descriptor type is derived from the store's own {@code resourceURI}, so a
     * listing can never silently query a namespace the store does not write to.
     */
    public List<DocumentDescriptor> readDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors(RestUtilities.extractDescriptorType(resourceURI), filter, index, limit);
    }

    /**
     * As {@link #readDescriptors(String, Integer, Integer)}, narrowed to one space.
     * <p>
     * The narrowing happens in the query for the same reason the access predicate
     * does: filtering a page after the fact returns short pages, and page 2 of
     * "everything" is not page 2 of "this space". It can only ever remove rows —
     * see {@link AccessScope#withinSpace}.
     */
    public List<DocumentDescriptor> readDescriptors(String filter, Integer index, Integer limit, String space) {
        return readDescriptors(RestUtilities.extractDescriptorType(resourceURI), filter, index, limit,
                accessGuard.listingScope().withinSpace(space));
    }

    public List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit) {
        return readDescriptors(type, filter, index, limit, accessGuard.listingScope());
    }

    private List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit,
                                                     AccessScope scope) {
        try {
            // Filtered in the query rather than on the returned page: post-filtering
            // returns short pages and forces the scan-budgeted back-fill that conversation
            // listing has to do, where no such predicate exists.
            List<DocumentDescriptor> descriptors = documentDescriptorStore.readDescriptors(type, filter, index, limit, false,
                    scope);
            // The scope decides WHICH rows come back; this decides how much of each row a
            // non-owner gets to read. Without it every listing serialises the grant list.
            descriptors.forEach(accessGuard::redactForCaller);
            return descriptors;
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Asserts the caller may read this resource. Exposed for the stores whose read
     * methods bypass {@link #read} because they take extra filter arguments —
     * {@code RestOutputStore.readOutputSet} and
     * {@code RestDictionaryStore.readExpressions} among them — so those cannot be
     * left unguarded by accident.
     */
    public void requireViewAccess(String id) {
        accessGuard.requireAccess(id, AccessLevel.VIEW, resourceTypeLabel);
    }

    /** Asserts the caller may modify this resource. */
    public void requireEditAccess(String id) {
        accessGuard.requireAccess(id, AccessLevel.EDIT, resourceTypeLabel);
    }

    /**
     * Asserts the caller may delete or re-share this resource.
     * <p>
     * {@link #delete} applies this itself, but a cascading delete tears down
     * referenced resources <em>before</em> deleting the resource named in the
     * request — so a cascade must check first, or an unauthorised caller destroys
     * the graph on the way to being refused.
     */
    public void requireOwnAccess(String id) {
        accessGuard.requireAccess(id, AccessLevel.OWN, resourceTypeLabel);
    }

    public Response create(T document) {
        RuntimeUtilities.checkNotNull(document, "document");

        try {
            IResourceStore.IResourceId resourceId = resourceStore.create(document);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).location(createdUri)
                    .header("X-Resource-URI", createdUri.toString()).build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Creates a new resource and returns the {@link IResourceStore.IResourceId}
     * directly, bypassing the JAX-RS {@link Response} wrapper entirely.
     * <p>
     * For in-process callers (CDI direct calls, import service, duplicate
     * operations) that want the id and version, not an HTTP envelope to unwrap
     * again. It is <em>not</em> a workaround for {@code Response.getLocation()}:
     * that works for {@code eddi://} URIs, as
     * {@code RestWorkflowStoreCrudTest.duplicateDeepCopyWithParserDictionaries}
     * demonstrates against the real JAX-RS {@code RuntimeDelegate}.
     *
     * @param document
     *            the resource to create
     * @return the created resource's ID and version
     */
    public IResourceStore.IResourceId createDocument(T document) {
        RuntimeUtilities.checkNotNull(document, "document");

        try {
            return resourceStore.create(document);
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    public T read(String id, Integer version) {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNegative(version, "version");

        // Checked against the CURRENT descriptor, not the addressed version: ownership
        // and sharing belong to the resource, so reading an old version of a resource
        // that was since re-shared must not be decided against stale sharing.
        requireViewAccess(id);

        // After the access check, and by the same rule update/delete follow: version 0
        // means "current". Reading it literally made GET ?version=0 a 404 while
        // PUT ?version=0 and DELETE ?version=0 acted on the current version, so no
        // single convention worked across the three verbs.
        version = validateParameters(id, version);

        try {
            return resourceStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    public Response update(String id, Integer version, T document) {
        requireEditAccess(id);
        version = validateParameters(id, version);
        RuntimeUtilities.checkNotNull(document, "document");

        try {
            Integer newVersion = resourceStore.update(id, version, document);
            URI newResourceUri = RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
            return Response.ok().location(newResourceUri).build();
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceModifiedException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    public Response delete(String id, Integer version) {
        return delete(id, version, false);
    }

    /**
     * Deleting requires {@link AccessLevel#OWN}, not {@code EDIT}.
     * <p>
     * A teammate sharing a space gets {@code EDIT}, so they can change a
     * colleague's agent but not remove it. Deletion and re-sharing both change who
     * can reach the resource at all, and both stay with whoever made it — plus
     * administrators, whom {@code seesEverything()} admits.
     */
    public Response delete(String id, Integer version, boolean permanent) {
        accessGuard.requireAccess(id, AccessLevel.OWN, resourceTypeLabel);
        version = validateParameters(id, version);

        try {
            if (permanent) {
                resourceStore.deleteAllPermanently(id);
            } else {
                resourceStore.delete(id, version);
            }
            markDescriptorDeleted(id, version);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceModifiedException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Flags the resource's descriptor as deleted, here rather than only in
     * {@code DocumentDescriptorFilter}.
     *
     * <p>
     * That filter is a JAX-RS {@code ContainerResponseFilter}, so it runs for an
     * HTTP DELETE and for nothing else. Every in-process delete — the agent
     * cascade, the workflow cascade, the orphan purge — reaches the store through a
     * direct CDI call and never touched a descriptor, while the store layer itself
     * (neither {@code deleteAllPermanently} nor {@code HistorizedResourceStore
     * .delete}) touches one either. The result: a cascade-deleted rule set kept
     * {@code deleted=false}, so {@code readDescriptors(includeDeleted=false)} still
     * listed it, opening it answered 404, and the orphan scan kept re-reporting
     * resources it had already purged. Marking it at this level makes the two paths
     * converge; the filter then repeats the same idempotent write on the HTTP path.
     * </p>
     *
     * <p>
     * The descriptor is <em>flagged</em>, never removed, even for
     * {@code permanent=true}. {@code documentDescriptorStore.deleteAllDescriptor}
     * would be the tidier cleanup, but on the HTTP path
     * {@code DocumentDescriptorFilter} runs after this and reads the descriptor
     * back — a missing row there becomes a {@code NotFoundException}, so erasing it
     * would answer 404 to a delete that in fact succeeded.
     * </p>
     *
     * <p>
     * Best-effort by design: the resource IS gone by the time we get here, so a
     * descriptor that cannot be updated must not turn a completed delete into an
     * error response.
     * </p>
     */
    private void markDescriptorDeleted(String id, Integer version) {
        try {
            DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(id, version);
            if (descriptor != null && !descriptor.isDeleted()) {
                descriptor.setDeleted(true);
                documentDescriptorStore.setDescriptor(id, version, descriptor);
            }
        } catch (Exception e) {
            LOGGER.warnf("Deleted %s '%s' (v%s) but could not flag its descriptor as deleted: %s", resourceTypeLabel, id, version,
                    e.getMessage());
        }
    }

    public Integer validateParameters(String id, Integer version) {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNegative(version, "version");

        if (version == 0) {
            version = getCurrentVersion(id);
        }
        return version;
    }

    @Override
    public String getResourceURI() {
        return resourceURI;
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return resourceStore.getCurrentResourceId(id);
    }
}
