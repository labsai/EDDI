package io.sls.persistence.impl.permission.rest;

import io.sls.permission.IPermissionStore;
import io.sls.permission.model.Permissions;
import io.sls.permission.rest.IRestPermissionStore;
import io.sls.persistence.IResourceStore;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;


/**
 * User: jarisch
 * Date: 20.11.12
 * Time: 11:48
 */
public class RestPermissionStore implements IRestPermissionStore {
    private final String resourceURI = "resource://io.sls.permission/permissionstore/permissions/";
    private final IPermissionStore permissionStore;

    @Inject
    public RestPermissionStore(IPermissionStore permissionStore) {
        this.permissionStore = permissionStore;
    }

    @Override
    public Permissions readPermissions(String resourceId) {
        try {
            return permissionStore.readFilteredPermissions(resourceId);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);

        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public void updatePermissions(String resourceId, Permissions permissions) {
        try {
            permissionStore.updatePermissions(resourceId, permissions);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }
}
