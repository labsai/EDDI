package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.Date;
import java.util.List;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

public class ResourceUtilities {
    private static final String COPY_APPENDIX = " - Copy";

    public static IResourceStore.IResourceId validateUri(String resourceUriString) {
        if (resourceUriString.startsWith("eddi://")) {
            URI resourceUri = URI.create(resourceUriString);
            IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(resourceUri);
            if (!isNullOrEmpty(resourceId.getId()) && !isNullOrEmpty(resourceId.getVersion()) &&
                    resourceId.getVersion() > 0) {
                return resourceId;
            }
        }

        return null;
    }

    public static List<DocumentDescriptor> createMalFormattedResourceUriException(String containingResourceUri) {
        String message = String.format("Bad resource uri. Needs to be of this format: " +
                "eddi://ai.labs.<type>/<path>/<ID>?version=<VERSION>" +
                "\n actual: '%s'", containingResourceUri);
        throw new BadRequestException(Response.status(BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN).build());
    }

    public static void createDocumentDescriptorForDuplicate(IDocumentDescriptorStore documentDescriptorStore,
            String oldId,
            Integer oldVersion,
            URI newResourceLocation)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        var oldDescriptor = documentDescriptorStore.readDescriptor(oldId, oldVersion);

        var newResourceId = RestUtilities.extractResourceId(newResourceLocation);

        if (!isNullOrEmpty(oldDescriptor.getName())) {
            oldDescriptor.setName(oldDescriptor.getName() + COPY_APPENDIX);
        }

        oldDescriptor.setResource(newResourceLocation);
        Date currentTime = new Date(System.currentTimeMillis());
        oldDescriptor.setCreatedOn(currentTime);
        oldDescriptor.setLastModifiedOn(currentTime);

        documentDescriptorStore.createDescriptor(
                newResourceId.getId(),
                newResourceId.getVersion(),
                oldDescriptor);

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

    public static ConversationDescriptor createConversationDescriptorDocument(URI resource, URI agentResourceURI,
            String userId) {
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
