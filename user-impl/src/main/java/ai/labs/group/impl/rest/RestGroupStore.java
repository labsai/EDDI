package ai.labs.group.impl.rest;

import ai.labs.group.IGroupStore;
import ai.labs.group.model.Group;
import ai.labs.group.rest.IRestGroupStore;
import ai.labs.persistence.IResourceStore;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
@Slf4j
@RequestScoped
public class RestGroupStore implements IRestGroupStore {
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
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();

        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public void updateGroup(String groupId, Group group) {
        try {
            groupStore.updateGroup(groupId, group);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response createGroup(Group group) {
        try {
            String id = groupStore.createGroup(group);
            URI createdUri = RestUtilities.createURI(resourceURI, id);
            return Response.created(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void deleteGroup(String groupId) {
        groupStore.deleteGroup(groupId);
    }
}
