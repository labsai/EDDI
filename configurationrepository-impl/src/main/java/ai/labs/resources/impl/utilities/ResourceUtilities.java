package ai.labs.resources.impl.utilities;

import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.IResourceStore.IResourceId;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.utilities.RestUtilities;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;

public class ResourceUtilities {
    private static final String MONGO_OBJECT_ID = "_id";
    private static final String MONGO_OBJECT_VERSION = "_version";

    public static List<IResourceId> getAllConfigsContainingResources(Document filter,
                                                                     MongoCollection<Document> currentCollection,
                                                                     MongoCollection<Document> historyCollection,
                                                                     IDocumentDescriptorStore documentDescriptorStore)
            throws IResourceStore.ResourceNotFoundException {
        List<String> currentResourceIds = new LinkedList<>();
        FindIterable<Document> documentIterable;

        List<IResourceId> allConfigsContainingResource = new LinkedList<>();

        documentIterable = currentCollection.find(filter);
        ResourceUtilities.extractIds(currentResourceIds, documentIterable);

        for (String currentResourceId : currentResourceIds) {
            allConfigsContainingResource.add(documentDescriptorStore.getCurrentResourceId(currentResourceId));
        }

        List<IResourceId> versionedResources = new LinkedList<>();
        documentIterable = historyCollection.find(filter);
        ResourceUtilities.extractVersionedIds(versionedResources, documentIterable);

        allConfigsContainingResource.addAll(versionedResources);
        Comparator<IResourceId> comparator =
                Comparator.comparing(IResourceId::getId).thenComparingInt(IResourceId::getVersion).reversed();

        return allConfigsContainingResource.stream().sorted(comparator).collect(Collectors.toList());
    }

    private static void extractIds(List<String> ids, FindIterable<Document> documentIterable) {
        for (Document document : documentIterable) {
            ids.add(document.getObjectId(MONGO_OBJECT_ID).toString());
        }
    }

    private static void extractVersionedIds(List<IResourceId> versionedIds,
                                            FindIterable<Document> documentIterable) {

        for (Document document : documentIterable) {
            Object idObject = document.get(MONGO_OBJECT_ID);
            String objectId = ((Document) idObject).getObjectId(MONGO_OBJECT_ID).toString();
            Integer objectVersion = ((Document) idObject).getInteger(MONGO_OBJECT_VERSION);
            versionedIds.add(new IResourceId() {
                @Override
                public String getId() {
                    return objectId;
                }

                @Override
                public Integer getVersion() {
                    return objectVersion;
                }
            });
        }
    }

    public static List<DocumentDescriptor> createMalFormattedResourceUriException(String containingResourceUri) {
        String message = String.format("Bad resource uri. Needs to be of this format: " +
                "eddi://ai.labs.<type>/<path>/<ID>?version=<VERSION>" +
                "\n actual: '%s'", containingResourceUri);
        throw new BadRequestException(Response.status(BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN).build());
    }

    public static IResourceId validateUri(String resourceUriString) {
        if (resourceUriString.startsWith("eddi://")) {
            URI resourceUri = URI.create(resourceUriString);
            IResourceId resourceId = RestUtilities.extractResourceId(resourceUri);
            if (!isNullOrEmpty(resourceId.getId()) && !isNullOrEmpty(resourceId.getVersion()) &&
                    resourceId.getVersion() > 0) {
                return resourceId;
            }
        }

        return null;
    }
}
