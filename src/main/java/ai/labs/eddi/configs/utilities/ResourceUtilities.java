package ai.labs.eddi.configs.utilities;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.models.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoCollection;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

public class ResourceUtilities {
    private static final String MONGO_OBJECT_ID = "_id";
    private static final String MONGO_OBJECT_VERSION = "_version";
    private static final String COPY_APPENDIX = " - Copy";

    public static List<IResourceStore.IResourceId> getAllConfigsContainingResources(Document filter,
                                                                                    MongoCollection<Document> currentCollection,
                                                                                    MongoCollection<Document> historyCollection,
                                                                                    IDocumentDescriptorStore documentDescriptorStore)
            throws IResourceStore.ResourceNotFoundException {
        List<String> currentResourceIds = new LinkedList<>();
        FindPublisher<Document> documentIterable;

        List<IResourceStore.IResourceId> allConfigsContainingResource = new LinkedList<>();

        documentIterable = currentCollection.find(filter);
        ResourceUtilities.extractIds(currentResourceIds, documentIterable);

        for (String currentResourceId : currentResourceIds) {
            allConfigsContainingResource.add(documentDescriptorStore.getCurrentResourceId(currentResourceId));
        }

        List<IResourceStore.IResourceId> versionedResources = new LinkedList<>();
        documentIterable = historyCollection.find(filter);
        ResourceUtilities.extractVersionedIds(versionedResources, documentIterable);

        allConfigsContainingResource.addAll(versionedResources);
        Comparator<IResourceStore.IResourceId> comparator =
                Comparator.comparing(IResourceStore.IResourceId::getId).thenComparingInt(IResourceStore.IResourceId::getVersion).reversed();

        return allConfigsContainingResource.stream().sorted(comparator).collect(Collectors.toList());
    }

    private static void extractIds(List<String> ids, FindPublisher<Document> documentIterable) {
        Observable.fromPublisher(documentIterable).subscribe(document -> {
            ids.add(document.getObjectId(MONGO_OBJECT_ID).toString());
        });

    }

    private static void extractVersionedIds(List<IResourceStore.IResourceId> versionedIds,
                                            FindPublisher<Document> documentIterable) {

        Observable.fromPublisher(documentIterable).subscribe(document -> {
            Object idObject = document.get(MONGO_OBJECT_ID);
            String objectId = ((Document) idObject).getObjectId(MONGO_OBJECT_ID).toString();
            Integer objectVersion = ((Document) idObject).getInteger(MONGO_OBJECT_VERSION);
            versionedIds.add(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return objectId;
                }

                @Override
                public Integer getVersion() {
                    return objectVersion;
                }
            });
        });
    }

    public static List<DocumentDescriptor> createMalFormattedResourceUriException(String containingResourceUri) {
        String message = String.format("Bad resource uri. Needs to be of this format: " +
                "eddi://ai.labs.<type>/<path>/<ID>?version=<VERSION>" +
                "\n actual: '%s'", containingResourceUri);
        throw new BadRequestException(Response.status(BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN).build());
    }

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

    public static DocumentDescriptor createDocumentDescriptor(URI resource/*, URI author*/) {
        Date current = new Date(System.currentTimeMillis());

        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(resource);
        descriptor.setName("");
        descriptor.setDescription("");
        descriptor.setCreatedOn(current);
        descriptor.setLastModifiedOn(current);
        /*descriptor.setCreatedBy(author);
        descriptor.setLastModifiedBy(author);*/

        return descriptor;
    }

    public static ConversationDescriptor createConversationDescriptorDocument(URI resource, URI botResourceURI) {
        ConversationDescriptor conversationDescriptor = new ConversationDescriptor();
        conversationDescriptor.setResource(resource);
        conversationDescriptor.setBotResource(botResourceURI);
        Date createdOn = new Date(System.currentTimeMillis());
        conversationDescriptor.setCreatedOn(createdOn);
        conversationDescriptor.setLastModifiedOn(createdOn);
        conversationDescriptor.setCreatedBy(null);
        conversationDescriptor.setLastModifiedBy(null);
        conversationDescriptor.setViewState(ConversationDescriptor.ViewState.UNSEEN);
        return conversationDescriptor;
    }

}
