package io.sls.user.impl.rest;

import io.sls.persistence.IResourceStore;
import io.sls.user.IUserStore;
import io.sls.user.model.User;
import io.sls.user.rest.IRestUserStore;
import io.sls.utilities.RestUtilities;
import io.sls.utilities.SecurityUtilities;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
public class RestUserStore implements IRestUserStore {
    private final IUserStore userStore;

    @Inject
    public RestUserStore(IUserStore userStore) {
        this.userStore = userStore;
    }

    @Override
    public URI searchUser(String username) {
        try {
            String id = userStore.searchUser(username);
            return RestUtilities.createURI(resourceURI, id);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);

        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public User readUser(String userId) {
        try {
            return userStore.readUser(userId);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);

        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public void updateUser(String userId, User user) {
        try {
            userStore.updateUser(userId, user);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response createUser(User user) {
        try {
            String id = userStore.createUser(user);
            URI createdUri = RestUtilities.createURI(resourceURI, id);
            return Response.created(createdUri).entity(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteUser(String userId) {
        userStore.deleteUser(userId);
    }

    @Override
    public void changePassword(String userId, String newPassword) {
        User user = readUser(userId);
        user.setSalt(SecurityUtilities.generateSalt());
        user.setPassword(SecurityUtilities.hashPassword(newPassword, user.getSalt()));
        updateUser(userId, user);
    }
}
