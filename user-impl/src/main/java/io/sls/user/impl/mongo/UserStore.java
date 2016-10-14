package io.sls.user.impl.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.sls.persistence.IResourceStore;
import io.sls.serialization.JSONSerialization;
import io.sls.user.IUserStore;
import io.sls.user.model.User;
import io.sls.utilities.SecurityUtilities;
import org.bson.types.ObjectId;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 13:40
 */
public class UserStore implements IUserStore {
    public static final String COLLECTION_USERS = "users";
    private final DBCollection collection;

    @Inject
    public UserStore(DB database) {
        collection = database.getCollection(COLLECTION_USERS);
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

        User user = convert(userDocument);

        return user;

    }

    private User convert(DBObject userDocument) throws IResourceStore.ResourceStoreException {
        try {
            return JSONSerialization.deserialize(userDocument.toString(), new TypeReference<User>() {
            });
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into User entity.");
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

        String generatedId = document.get("_id").toString();

        return generatedId;
    }

    private String serialize(User user) throws IResourceStore.ResourceStoreException {
        try {
            return JSONSerialization.serialize(user);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot serialize User entity into json.");
        }
    }

    @Override
    public void deleteUser(String userId) {
        collection.remove(new BasicDBObject("_id", new ObjectId(userId)));
    }
}
