package io.sls.group.impl.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.sls.group.IGroupStore;
import io.sls.group.model.Group;
import io.sls.persistence.IResourceStore;
import io.sls.serialization.IJsonSerialization;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;

import javax.inject.Inject;
import java.io.IOException;

/**
 * @author ginccc
 */
@Slf4j
public class GroupStore implements IGroupStore {
    private static final String COLLECTION_GROUPS = "groups";
    private final DBCollection collection;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public GroupStore(DB database, IJsonSerialization jsonSerialization) {
        collection = database.getCollection(COLLECTION_GROUPS);
        this.jsonSerialization = jsonSerialization;
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

            return jsonSerialization.deserialize(groupDocument.toString(), Group.class);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into Group entity.", e);
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

        return document.get("_id").toString();
    }

    private String serialize(Group group) throws IResourceStore.ResourceStoreException {
        try {
            return jsonSerialization.serialize(group);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException("Cannot serialize Group entity into json.", e);
        }
    }

    @Override
    public void deleteGroup(String groupId) {
        collection.remove(new BasicDBObject("_id", new ObjectId(groupId)));
    }
}
