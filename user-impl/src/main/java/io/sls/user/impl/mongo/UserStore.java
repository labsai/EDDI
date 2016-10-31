package io.sls.user.impl.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.sls.persistence.IResourceStore;
import io.sls.serialization.IJsonSerialization;
import io.sls.user.IUserStore;
import io.sls.user.model.User;
import io.sls.utilities.SecurityUtilities;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;

import javax.inject.Inject;
import java.io.IOException;

/**
 * @author ginccc
 */
@Slf4j
public class UserStore implements IUserStore {
    private static final String COLLECTION_USERS = "users";
    private final DBCollection collection;
    private IJsonSerialization jsonSerialization;

    @Inject
    public UserStore(DB database, IJsonSerialization jsonSerialization) {
        collection = database.getCollection(COLLECTION_USERS);
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public String searchUser(String username) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        DBObject userDocument = collection.findOne(new BasicDBObject("username", username));

        if (userDocument == null) {
            String message = "Resource 'User' not found. (username=%s)";
            message = String.format(message, username);
            throw new IResourceStore.ResourceNotFoundException(message);
        }

        return userDocument.get("_id").toString();
    }

    public User readUser(String userId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        DBObject userDocument = collection.findOne(new BasicDBObject("_id", new ObjectId(userId)));

        if (userDocument == null) {
            String message = "Resource 'User' not found. (userId=%s)";
            message = String.format(message, userId);
            throw new IResourceStore.ResourceNotFoundException(message);
        }

        userDocument.removeField("_id");

        return convert(userDocument);

    }

    private User convert(DBObject userDocument) throws IResourceStore.ResourceStoreException {
        try {
            return jsonSerialization.deserialize(userDocument.toString(), User.class);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }


    @Override
    public void updateUser(String userId, User user) throws IResourceStore.ResourceStoreException {
        String jsonUser = serialize(user);
        DBObject document = (DBObject) JSON.parse(jsonUser);

        document.put("_id", new ObjectId(userId));

        collection.save(document);
    }

    @Override
    public String createUser(User user) throws IResourceStore.ResourceStoreException {
        user.setSalt(SecurityUtilities.generateSalt());
        user.setPassword(SecurityUtilities.hashPassword(user.getPassword(), user.getSalt()));

        String jsonUser = serialize(user);
        DBObject document = (DBObject) JSON.parse(jsonUser);

        collection.insert(document);

        return document.get("_id").toString();
    }

    private String serialize(User user) throws IResourceStore.ResourceStoreException {
        try {
            return jsonSerialization.serialize(user);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException("Cannot serialize User entity into json.", e);
        }
    }

    @Override
    public void deleteUser(String userId) {
        collection.remove(new BasicDBObject("_id", new ObjectId(userId)));
    }
}
