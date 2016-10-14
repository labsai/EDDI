package io.sls.group.impl.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.sls.group.IGroupStore;
import io.sls.group.model.Group;
import io.sls.persistence.IResourceStore;
import io.sls.serialization.JSONSerialization;
import org.bson.types.ObjectId;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 13:40
 */
public class GroupStore implements IGroupStore {
    public static final String COLLECTION_GROUPS = "groups";
    private final DBCollection collection;

    @Inject
    public GroupStore(DB database) {
        collection = database.getCollection(COLLECTION_GROUPS);
    }

    public Group readGroup(String groupId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        DBObject groupDocument = collection.findOne(new BasicDBObject("_id", new ObjectId(groupId)));

        try {
            if (groupDocument == null) {
                String message = "Resource 'Group' not found. (groupId=%s)";
                message = String.format(message, groupId);
                throw new IResourceStore.ResourceNotFoundException(message);
            }

            groupDocument.removeField("_id");

            Group group = JSONSerialization.deserialize(groupDocument.toString(), new TypeReference<Group>() {
            });

            return group;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into Group entity.");
        }
    }


    @Override
    public void updateGroup(String groupId, Group group) {
        String jsonGroup = JSON.serialize(group);
        DBObject document = (DBObject) JSON.parse(jsonGroup);

        document.put("_id", new ObjectId(groupId));

        collection.save(document);
    }

    @Override
    public String createGroup(Group group) throws IResourceStore.ResourceStoreException {
        String jsonGroup = serialize(group);
        DBObject document = (DBObject) JSON.parse(jsonGroup);

        collection.insert(document);

        String generatedId = document.get("_id").toString();

        return generatedId;
    }

    private String serialize(Group group) throws IResourceStore.ResourceStoreException {
        try {
            return JSONSerialization.serialize(group);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot serialize Group entity into json.");
        }
    }

    @Override
    public void deleteGroup(String groupId) {
        collection.remove(new BasicDBObject("_id", new ObjectId(groupId)));
    }
}
