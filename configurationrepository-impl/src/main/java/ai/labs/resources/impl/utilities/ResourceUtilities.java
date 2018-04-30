package ai.labs.resources.impl.utilities;

import ai.labs.persistence.IResourceStore;
import com.mongodb.client.FindIterable;
import org.bson.Document;

import java.util.List;
import java.util.Objects;

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
}
