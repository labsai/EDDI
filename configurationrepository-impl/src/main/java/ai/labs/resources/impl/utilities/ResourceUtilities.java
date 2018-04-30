package ai.labs.resources.impl.utilities;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import com.mongodb.client.FindIterable;
import org.bson.Document;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Objects;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;

public class ResourceUtilities {
    private static final String MONGO_OBJECT_ID = "_id";

    public static void addIfNewerVersion(IResourceStore.IResourceId resourceId,
                                         List<IResourceStore.IResourceId> resources) {
        if (resources.isEmpty()) {
            resources.add(resourceId);
        } else {
            boolean addToList = resources.stream().noneMatch(bot ->
                    Objects.equals(resourceId.getId(), bot.getId()) && resourceId.getVersion() < bot.getVersion());

            if (addToList) {
                resources.add(resourceId);
            }
        }
    }

    public static void extractIds(List<String> ids, FindIterable<Document> documentIterable) {
        for (Document document : documentIterable) {
            ids.add(document.getObjectId(MONGO_OBJECT_ID).toString());
        }
    }

    public static List<DocumentDescriptor> createMaleFormattedResourceUriException(String containingResourceUri) {
        String message = String.format("Bad resource uri. Needs to be of this format: " +
                "eddi://ai.labs.<type>/<path>/<ID>?version=<VERSION>" +
                "\n actual: '%s'", containingResourceUri);
        throw new BadRequestException(Response.status(BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN).build());
    }

}
