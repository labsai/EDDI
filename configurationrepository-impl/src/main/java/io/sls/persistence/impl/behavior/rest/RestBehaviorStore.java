package io.sls.persistence.impl.behavior.rest;

import io.sls.persistence.IResourceStore;
import io.sls.persistence.impl.resources.rest.RestVersionInfo;
import io.sls.resources.rest.behavior.IBehaviorStore;
import io.sls.resources.rest.behavior.IRestBehaviorStore;
import io.sls.resources.rest.behavior.model.BehaviorConfiguration;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestBehaviorStore extends RestVersionInfo implements IRestBehaviorStore {
    private final IBehaviorStore behaviorStore;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public RestBehaviorStore(IBehaviorStore behaviorStore,
                             IDocumentDescriptorStore documentDescriptorStore) {
        this.behaviorStore = behaviorStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readBehaviorDescriptors(String filter, Integer index, Integer limit) {
        try {
            return documentDescriptorStore.readDescriptors("io.sls.behavior", filter, index, limit, false);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public BehaviorConfiguration readBehaviorRuleSet(String id, Integer version) {
        try {
            return behaviorStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public URI updateBehaviorRuleSet(String id, Integer version, BehaviorConfiguration behaviorConfiguration) {
        try {
            Integer newVersion = behaviorStore.update(id, version, behaviorConfiguration);
            return RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = behaviorStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public Response createBehaviorRuleSet(BehaviorConfiguration behaviorConfiguration) {
        try {
            IResourceStore.IResourceId resourceId = behaviorStore.create(behaviorConfiguration);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).entity(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteBehaviorRuleSet(String id, Integer version) {
        try {
            behaviorStore.delete(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = behaviorStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return behaviorStore.getCurrentResourceId(id);
    }
}
