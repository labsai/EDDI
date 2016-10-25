package io.sls.permission;

import io.sls.permission.model.Permissions;
import io.sls.persistence.IResourceStore;

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
