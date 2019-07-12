package ai.labs.resources.impl.permission.rest;

import ai.labs.permission.IPermissionStore;
import ai.labs.permission.model.Permissions;
import ai.labs.permission.rest.IRestPermissionStore;
import ai.labs.persistence.IResourceStore;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;


/**
 * @author ginccc
 */
@ApplicationScoped
public class RestPermissionStore implements IRestPermissionStore {
    private final String resourceURI = "eddi://ai.labs.permission/permissionstore/permissions/";
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
