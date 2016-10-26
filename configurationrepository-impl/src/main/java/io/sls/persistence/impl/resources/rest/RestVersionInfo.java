package io.sls.persistence.impl.resources.rest;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.IRestVersionInfo;
import io.sls.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
@Slf4j
public abstract class RestVersionInfo<T> implements IRestVersionInfo {
    private final String resourceURI;
    private final IResourceStore<T> resourceStore;

    public RestVersionInfo(String resourceURI, IResourceStore<T> resourceStore) {
        this.resourceURI = resourceURI;
        this.resourceStore = resourceStore;
    }

    @Override
    public Integer getCurrentVersion(String id) {
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            return currentResourceId.getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    public Response create(T obj) {
        try {
            IResourceStore.IResourceId resourceId = resourceStore.create(obj);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).entity(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    protected T read(String id, Integer version) {
        try {
            return resourceStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    public URI update(String id, Integer version, T document) {
        try {
            Integer newVersion = resourceStore.update(id, version, document);
            return RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = resourceStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    public void delete(String id, Integer version) {
        try {
            resourceStore.delete(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = resourceStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    protected abstract IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;
}
