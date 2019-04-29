package ai.labs.resources.impl.resources.rest;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;

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
public abstract class RestVersionInfo<T> implements IRestVersionInfo {
    private final String resourceURI;
    private final IResourceStore<T> resourceStore;
    protected final IDocumentDescriptorStore documentDescriptorStore;

    public RestVersionInfo(String resourceURI, IResourceStore<T> resourceStore,
                           IDocumentDescriptorStore documentDescriptorStore) {
        this.resourceURI = resourceURI;
        this.resourceStore = resourceStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    protected List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit) {
        try {
            return documentDescriptorStore.readDescriptors(type, filter, index, limit, false);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
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

    @Override
    public Response redirectToLatestVersion(String id) {
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            String path = URI.create(resourceURI).getPath();
            return Response.seeOther(URI.create(path + id + versionQueryParam + currentResourceId.getVersion())).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage());
        }
    }

    protected Response create(T document) {
        RuntimeUtilities.checkNotNull(document, "document");

        try {
            IResourceStore.IResourceId resourceId = resourceStore.create(document);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).location(createdUri).build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    protected T read(String id, Integer version) {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNegative(version, "version");

        try {
            return resourceStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    protected Response update(String id, Integer version, T document) {
        version = validateParameters(id, version);
        RuntimeUtilities.checkNotNull(document, "document");

        try {
            Integer newVersion = resourceStore.update(id, version, document);
            URI newResourceUri = RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
            return Response.ok().location(newResourceUri).build();
        } catch (IResourceStore.ResourceModifiedException e) {
            return throwConflictException(id, e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    protected Response delete(String id, Integer version) {
        version = validateParameters(id, version);

        try {
            resourceStore.delete(id, version);
            return Response.ok().build();
        } catch (IResourceStore.ResourceModifiedException e) {
            throwConflictException(id, e);
            return null;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    protected Integer validateParameters(String id, Integer version) {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNegative(version, "version");

        if (version == 0) {
            version = getCurrentVersion(id);
        }
        return version;
    }

    private Response throwConflictException(String id, IResourceStore.ResourceModifiedException e) {
        try {
            IResourceStore.IResourceId currentId = resourceStore.getCurrentResourceId(id);
            throw RestUtilities.createConflictException(resourceURI, currentId);
        } catch (IResourceStore.ResourceNotFoundException e1) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    protected abstract IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;
}
