package ai.labs.permission.impl;

import ai.labs.group.IGroupStore;
import ai.labs.permission.IAuthorization;
import ai.labs.permission.IPermissionStore;
import ai.labs.permission.model.AuthorizedSubjects;
import ai.labs.permission.model.AuthorizedUser;
import ai.labs.permission.model.Permissions;
import ai.labs.permission.utilities.PermissionUtilities;
import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.ThreadContext;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.user.IUserStore;
import ai.labs.user.impl.utilities.UserUtilities;
import ai.labs.utilities.SecurityUtilities;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class PermissionStore implements IPermissionStore {
    private static final String COLLECTION_PERMISSIONS = "permissions";
    private final DBCollection collection;
    private final IJsonSerialization jsonSerialization;
    private IUserStore userStore;
    private IGroupStore groupStore;

    @Inject
    public PermissionStore(DB database, IJsonSerialization jsonSerialization, IUserStore userStore, IGroupStore groupStore) {
        collection = database.getCollection(COLLECTION_PERMISSIONS);
        this.jsonSerialization = jsonSerialization;
        this.userStore = userStore;
        this.groupStore = groupStore;
    }


    @Override
    public Permissions readPermissions(String resourceId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        DBObject permissionsDocument = collection.findOne(new BasicDBObject("_id", new ObjectId(resourceId)));

        try {
            if (permissionsDocument == null) {
                String message = "Resource 'Permissions' not found. (id=%s)";
                message = String.format(message, resourceId);
                throw new IResourceStore.ResourceNotFoundException(message);
            }

            permissionsDocument.removeField("_id");

            return jsonSerialization.deserialize(permissionsDocument.toString(), Permissions.class);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into Permissions entity.", e);
        }
    }

    @Override
    public Permissions readFilteredPermissions(String resourceId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        Permissions permissions = readPermissions(resourceId);
        Principal principal = SecurityUtilities.getPrincipal(ThreadContext.getSubject());
        URI userURI = UserUtilities.getUserURI(userStore, principal);

        if (!isUserAdministrator(permissions, userURI)) {
            PermissionUtilities.keepOwnPermissionsOnly(userStore, groupStore, permissions);
        }

        return permissions;
    }

    private boolean isUserAdministrator(Permissions permissions, URI userURI) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        if (userURI == null) {
            return false;
        }

        AuthorizedSubjects authorizedSubjects = permissions.getPermissions().get(IAuthorization.Type.ADMINISTRATION);
        if (authorizedSubjects == null) {
            return false;
        }
        List<AuthorizedUser> authorizedUsers = PermissionUtilities.mergeAuthorizedSubjects(groupStore, authorizedSubjects);
        PermissionUtilities.filterAuthorizedSubjectsByUser(userURI, authorizedUsers);

        return PermissionUtilities.containsUser(authorizedUsers, userURI);
    }

    @Override
    public void updatePermissions(String resourceId, Permissions permissions) throws IResourceStore.ResourceStoreException {
        String jsonPermissions = serialize(permissions);
        DBObject permissionsDocument = (DBObject) JSON.parse(jsonPermissions);

        permissionsDocument.put("_id", new ObjectId(resourceId));

        collection.save(permissionsDocument);
    }

    @Override
    public void copyPermissions(String fromResourceId, String toResourceId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        DBObject permissionsDocument = collection.findOne(new BasicDBObject("_id", new ObjectId(fromResourceId)));

        try {
            if (permissionsDocument == null) {
                String message = "Resource 'Permissions' not found. (id=%s)";
                message = String.format(message, fromResourceId);
                throw new IResourceStore.ResourceNotFoundException(message);
            }

            permissionsDocument.removeField("_id");

            Permissions permissions = jsonSerialization.deserialize(permissionsDocument.toString(), Permissions.class);

            createPermissions(toResourceId, permissions);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into Permissions entity.", e);
        }
    }

    @Override
    public void deletePermissions(String resourceId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        collection.remove(new BasicDBObject("_id", new ObjectId(resourceId)));
    }

    @Override
    public void createPermissions(String resourceId, Permissions permissions) throws IResourceStore.ResourceStoreException {
        updatePermissions(resourceId, permissions);
    }

    private String serialize(Permissions permissions) throws IResourceStore.ResourceStoreException {
        try {
            return jsonSerialization.serialize(permissions);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException("Cannot serialize User entity into json.", e);
        }
    }
}
