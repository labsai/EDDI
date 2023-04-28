package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.models.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;
import org.jboss.logging.Logger;

import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
public class RestVersionInfo<T> implements IRestVersionInfo {
    private final String resourceURI;
    private final IResourceStore<T> resourceStore;
    protected final IDocumentDescriptorStore documentDescriptorStore;

    private final Logger log = Logger.getLogger(RestVersionInfo.class);

    public RestVersionInfo(String resourceURI, IResourceStore<T> resourceStore,
                           IDocumentDescriptorStore documentDescriptorStore) {
        this.resourceURI = resourceURI;
        this.resourceStore = resourceStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    public List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit) {
        try {
            return documentDescriptorStore.readDescriptors(type, filter, index, limit, false);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    public Response create(T document) {
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

    public T read(String id, Integer version) {
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

    public Response update(String id, Integer version, T document) {
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

    public Response delete(String id, Integer version) {
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

    public Integer validateParameters(String id, Integer version) {
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

    @Override
    public String getResourceURI() {
        return resourceURI;
    }
}
