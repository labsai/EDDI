package ai.labs.resources.impl.resources.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
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
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
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

    protected Response create(T obj) {
        try {
            IResourceStore.IResourceId resourceId = resourceStore.create(obj);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).location(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    protected Response read(String id, Integer version) {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNegative(version, "version");
        if (version == 0) {
            return redirectToLatestVersion(id);
        }

        try {
            return Response.ok(resourceStore.read(id, version)).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    protected URI update(String id, Integer version, T document) {
        version = validateParameters(id, version);
        RuntimeUtilities.checkNotNull(document, "document");

        try {
            Integer newVersion = resourceStore.update(id, version, document);
            return RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            return throwConflictException(id, e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    protected void delete(String id, Integer version) {
        version = validateParameters(id, version);

        try {
            resourceStore.delete(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            throwConflictException(id, e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    private Integer validateParameters(String id, Integer version) {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNegative(version, "version");

        if (version == 0) {
            version = getCurrentVersion(id);
        }
        return version;
    }

    private URI throwConflictException(String id, IResourceStore.ResourceModifiedException e) {
        try {
            IResourceStore.IResourceId currentId = resourceStore.getCurrentResourceId(id);
            throw RestUtilities.createConflictException(resourceURI, currentId);
        } catch (IResourceStore.ResourceNotFoundException e1) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    protected abstract IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;
}
