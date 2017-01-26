package ai.labs.permission;

import ai.labs.permission.model.Permissions;
import ai.labs.persistence.IResourceStore;

/**
 * @author ginccc
 */
public interface IPermissionStore {
    Permissions readPermissions(String resourceId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    Permissions readFilteredPermissions(String resourceId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void updatePermissions(String resourceId, Permissions permissions) throws IResourceStore.ResourceStoreException;

    void createPermissions(String resourceId, Permissions permissions) throws IResourceStore.ResourceStoreException;

    void copyPermissions(String fromResourceId, String toResourceId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void deletePermissions(String resourceId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
