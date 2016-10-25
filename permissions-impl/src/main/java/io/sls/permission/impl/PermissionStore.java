package io.sls.permission.impl;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.sls.group.IGroupStore;
import io.sls.permission.IAuthorization;
import io.sls.permission.IPermissionStore;
import io.sls.permission.model.AuthorizedSubjects;
import io.sls.permission.model.AuthorizedUser;
import io.sls.permission.model.Permissions;
import io.sls.permission.utilities.PermissionUtilities;
import io.sls.persistence.IResourceStore;
import io.sls.runtime.ThreadContext;
import io.sls.serialization.JSONSerialization;
import io.sls.user.IUserStore;
import io.sls.user.impl.utilities.UserUtilities;
import io.sls.utilities.SecurityUtilities;
import org.bson.types.ObjectId;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.List;

/**
 * @author ginccc
 */
public class PermissionStore implements IPermissionStore {
    public static final String COLLECTION_PERMISSIONS = "permissions";
    private final DBCollection collection;
    private IUserStore userStore;
    private IGroupStore groupStore;

    @Inject
    public PermissionStore(DB database, IUserStore userStore, IGroupStore groupStore) {
        collection = database.getCollection(COLLECTION_PERMISSIONS);
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

            Permissions permissions = JSONSerialization.deserialize(permissionsDocument.toString(), new TypeReference<Permissions>() {
            });

            return permissions;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into Permissions entity.");
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

            Permissions permissions = JSONSerialization.deserialize(permissionsDocument.toString(), new TypeReference<Permissions>() {
            });

            createPermissions(toResourceId, permissions);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into Permissions entity.");
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
            return JSONSerialization.serialize(permissions);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot serialize User entity into json.");
        }
    }
}
