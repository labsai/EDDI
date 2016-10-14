package io.sls.persistence.impl.extensions.rest;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.extensions.IExtensionStore;
import io.sls.resources.rest.extensions.IRestExtensionStore;
import io.sls.resources.rest.extensions.model.ExtensionDefinition;
import io.sls.utilities.RestUtilities;
import org.jboss.resteasy.spi.NoLogWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 11.09.12
 * Time: 12:13
 */
public class RestExtensionStore implements IRestExtensionStore {
    private final IExtensionStore extensionStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Inject
    public RestExtensionStore(IExtensionStore extensionStore,
                              IDocumentDescriptorStore documentDescriptorStore) {
        this.extensionStore = extensionStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readExtensionDescriptors(String filter, Integer index, Integer limit) {
        List<DocumentDescriptor> ret = new LinkedList<DocumentDescriptor>();

        try {
            List<ExtensionDefinition> extensionDefinitions = extensionStore.readExtensions(filter, index, limit);

            for (ExtensionDefinition extensionDefinition : extensionDefinitions) {
                IResourceStore.IResourceId resourceId = extensionStore.searchExtension(extensionDefinition.getType().toString());
                ret.add(documentDescriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion()));
            }

            return ret;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public ExtensionDefinition readExtension(String id, Integer version) {
        try {
            return extensionStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public URI updateExtension(String id, Integer version, ExtensionDefinition extension) {
        try {
            Integer newVersion = extensionStore.update(id, version, extension);
            return RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = extensionStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public Response createExtension(ExtensionDefinition extension) {
        try {
            IResourceStore.IResourceId resourceId = extensionStore.create(extension);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).entity(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteExtension(String id, Integer version) {
        try {
            extensionStore.delete(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = extensionStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }
}
