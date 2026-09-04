/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.RestUtilities;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

public class ResourceUtilities {
    private static final String COPY_APPENDIX = " - Copy";

    public static IResourceStore.IResourceId validateUri(String resourceUriString) {
        if (resourceUriString.startsWith("eddi://")) {
            URI resourceUri = URI.create(resourceUriString);
            IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(resourceUri);
            if (!isNullOrEmpty(resourceId.getId()) && !isNullOrEmpty(resourceId.getVersion()) && resourceId.getVersion() > 0) {
                return resourceId;
            }
        }

        return null;
    }

    /**
     * The 400 for a resource URI that is not in the expected shape.
     *
     * <p>
     * Returns the exception for the caller to {@code throw}, rather than being
     * declared to return {@code List<DocumentDescriptor>} and throwing from inside
     * — a signature that existed only so call sites could write
     * {@code return createMalFormatted…(uri);} to satisfy the compiler, and that
     * read as if a value came back.
     * </p>
     */
    public static BadRequestException malformedResourceUri(String containingResourceUri) {
        String message = String.format(
                "Bad resource uri. Needs to be of this format: " + "eddi://ai.labs.<type>/<path>/<ID>?version=<VERSION>" + "\n actual: '%s'",
                containingResourceUri);
        return new BadRequestException(Response.status(BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN).build());
    }

    /**
     * Applies {@code filter}, {@code index} and {@code limit} to an
     * already-materialised descriptor list.
     *
     * <p>
     * The reverse-reference listings ("which agents contain this workflow?") answer
     * from a store lookup that takes neither a filter nor a page, so those three
     * REST parameters were accepted and then silently dropped: a client paging with
     * {@code index}/{@code limit} got the whole list back on every page, and a
     * resource referenced by thousands of workflows returned all of them at once.
     * Post-filtering is an approximation of the store-side filter — a
     * case-insensitive substring over the same descriptor fields the descriptor
     * store matches on — and it is applied here rather than pushed down because
     * there is no query to push it into.
     * </p>
     *
     * @param index
     *            page number, not offset — the same meaning the descriptor store
     *            gives it
     */
    public static List<DocumentDescriptor> filterAndPage(List<DocumentDescriptor> descriptors, String filter, Integer index,
                                                         Integer limit) {
        if (descriptors == null || descriptors.isEmpty()) {
            return descriptors;
        }

        List<DocumentDescriptor> matching = descriptors;
        if (!isNullOrEmpty(filter)) {
            String needle = filter.toLowerCase(Locale.ROOT);
            matching = descriptors.stream().filter(descriptor -> matches(descriptor, needle)).toList();
        }

        // null or non-positive means "no page": an in-process caller may pass either,
        // even though the REST overloads declare @DefaultValue("20"). Paging on a
        // limit of 0 would return an empty page and silently drop every row.
        if (limit == null || limit <= 0) {
            return matching;
        }
        int page = index == null || index < 0 ? 0 : index;
        int from = Math.min(page * limit, matching.size());
        int to = Math.min(from + limit, matching.size());
        return matching.subList(from, to);
    }

    /**
     * The descriptor fields this post-filter matches on, deliberately the same set
     * the descriptor store's own filter searches — a narrower set would make one
     * query string select different rows depending on which listing answered it.
     */
    private static boolean matches(DocumentDescriptor descriptor, String needle) {
        return containsIgnoreCase(descriptor.getName(), needle)
                || containsIgnoreCase(descriptor.getDescription(), needle)
                || containsIgnoreCase(descriptor.getOwnerId(), needle)
                || (descriptor.getResource() != null && containsIgnoreCase(descriptor.getResource().toString(), needle));
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Creates the descriptor for a duplicated resource.
     *
     * <h3>A copy belongs to whoever made it</h3> The source descriptor is used as a
     * template for name and description only — its ownership, space, visibility and
     * grants are stripped and the duplicating caller is stamped instead.
     * <p>
     * Copying them would be wrong in both directions. Duplicating a colleague's
     * <em>published</em> agent (which anyone may read, and therefore duplicate)
     * would file the copy under <em>their</em> name, in <em>their</em> space,
     * leaving the person who made it unable to edit or delete it — and letting
     * anyone inject resources into someone else's workspace, attributed to them.
     * <p>
     * {@code DocumentDescriptorFilter} cannot rescue this: it only creates a
     * descriptor when none exists, and this method has already created one by the
     * time the filter runs.
     */
    public static void createDocumentDescriptorForDuplicate(IDocumentDescriptorStore documentDescriptorStore,
                                                            ResourceAccessGuard resourceAccessGuard, String oldId, Integer oldVersion,
                                                            URI newResourceLocation)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        var oldDescriptor = documentDescriptorStore.readDescriptor(oldId, oldVersion);

        var newResourceId = RestUtilities.extractResourceId(newResourceLocation);

        DocumentDescriptor duplicate = new DocumentDescriptor();
        duplicate.setDescription(oldDescriptor.getDescription());
        if (!isNullOrEmpty(oldDescriptor.getName())) {
            duplicate.setName(oldDescriptor.getName() + COPY_APPENDIX);
        } else {
            duplicate.setName(oldDescriptor.getName());
        }

        duplicate.setResource(newResourceLocation);
        Date currentTime = new Date(System.currentTimeMillis());
        duplicate.setCreatedOn(currentTime);
        duplicate.setLastModifiedOn(currentTime);

        documentDescriptorStore.createDescriptor(newResourceId.getId(), newResourceId.getVersion(),
                resourceAccessGuard.stampNewDescriptor(duplicate));

    }

    public static DocumentDescriptor createDocumentDescriptor(URI resource) {
        Date current = new Date(System.currentTimeMillis());

        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(resource);
        descriptor.setName("");
        descriptor.setDescription("");
        descriptor.setCreatedOn(current);
        descriptor.setLastModifiedOn(current);

        return descriptor;
    }

    public static ConversationDescriptor createConversationDescriptorDocument(URI resource, URI agentResourceURI, String userId) {
        ConversationDescriptor conversationDescriptor = new ConversationDescriptor();
        conversationDescriptor.setResource(resource);
        conversationDescriptor.setUserId(userId);
        conversationDescriptor.setAgentResource(agentResourceURI);
        Date createdOn = new Date(System.currentTimeMillis());
        conversationDescriptor.setCreatedOn(createdOn);
        conversationDescriptor.setLastModifiedOn(createdOn);
        conversationDescriptor.setCreatedBy(null);
        conversationDescriptor.setLastModifiedBy(null);
        conversationDescriptor.setViewState(ConversationDescriptor.ViewState.UNSEEN);
        return conversationDescriptor;
    }

}
