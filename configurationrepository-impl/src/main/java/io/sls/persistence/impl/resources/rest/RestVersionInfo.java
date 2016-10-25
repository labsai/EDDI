package io.sls.persistence.impl.resources.rest;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.IRestVersionInfo;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.patch.PatchInstruction;
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
public abstract class RestVersionInfo implements IRestVersionInfo {
    @Override
    public Integer getCurrentVersion(String id) {
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            return currentResourceId.getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    public Response create(IResourceStore resourceStore, String resourceURI, Object obj) {
        try {
            IResourceStore.IResourceId resourceId = resourceStore.create(obj);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).entity(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    protected <T> T read(IResourceStore<T> resourceStore, String id, Integer version) {
        try {
            return resourceStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    public URI update(IResourceStore resourceStore, String resourceURI, String id, Integer version, Object document) {
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

    public void delete(IResourceStore resourceStore, String resourceURI, String id, Integer version) {
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

    public void patch(IDocumentDescriptorStore documentDescriptorStore, String id, Integer version, PatchInstruction<DocumentDescriptor> patchInstruction) {
        try {
            DocumentDescriptor documentDescriptor = documentDescriptorStore.readDescriptor(id, version);
            DocumentDescriptor simpleDescriptor = patchInstruction.getDocument();

            if (patchInstruction.getOperation().equals(PatchInstruction.PatchOperation.SET)) {
                documentDescriptor.setName(simpleDescriptor.getName());
                documentDescriptor.setDescription(simpleDescriptor.getDescription());
            } else {
                documentDescriptor.setName("");
                documentDescriptor.setDescription("");
            }

            documentDescriptorStore.setDescriptor(id, version, documentDescriptor);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    protected abstract IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;
}
