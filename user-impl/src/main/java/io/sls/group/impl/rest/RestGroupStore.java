package io.sls.group.impl.rest;

import io.sls.group.IGroupStore;
import io.sls.group.model.Group;
import io.sls.group.rest.IRestGroupStore;
import io.sls.persistence.IResourceStore;
import io.sls.utilities.RestUtilities;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 13:34
 */
public class RestGroupStore implements IRestGroupStore {
    private final String resourceURI = "resource://io.sls.group/groupstore/groups/";
    private final IGroupStore groupStore;

    @Inject
    public RestGroupStore(IGroupStore groupStore) {
        this.groupStore = groupStore;
    }

    @Override
    public Group readGroup(String groupId) {
        try {
            return groupStore.readGroup(groupId);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);

        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public void updateGroup(String groupId, Group group) {
        groupStore.updateGroup(groupId, group);
    }

    @Override
    public Response createGroup(Group group) {
        try {
            String id = groupStore.createGroup(group);
            URI createdUri = RestUtilities.createURI(resourceURI, id);
            return Response.created(createdUri).entity(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteGroup(String groupId) {
        groupStore.deleteGroup(groupId);
    }
}
