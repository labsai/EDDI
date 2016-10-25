package io.sls.persistence.impl.extensions.rest;

import io.sls.persistence.IResourceStore;
import io.sls.persistence.impl.resources.rest.RestVersionInfo;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.extensions.IExtensionStore;
import io.sls.resources.rest.extensions.IRestExtensionStore;
import io.sls.resources.rest.extensions.model.ExtensionDefinition;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestExtensionStore extends RestVersionInfo implements IRestExtensionStore {
    private final IExtensionStore extensionStore;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public RestExtensionStore(IExtensionStore extensionStore,
                              IDocumentDescriptorStore documentDescriptorStore) {
        this.extensionStore = extensionStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readExtensionDescriptors(String filter, Integer index, Integer limit) {
        List<DocumentDescriptor> ret = new LinkedList<>();

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
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public ExtensionDefinition readExtension(String id, Integer version) {
        return read(extensionStore, id, version);
    }

    @Override
    public URI updateExtension(String id, Integer version, ExtensionDefinition extension) {
        return update(extensionStore, resourceURI, id, version, extension);
    }

    @Override
    public Response createExtension(ExtensionDefinition extension) {
        return create(extensionStore, resourceURI, extension);
    }

    @Override
    public void deleteExtension(String id, Integer version) {
        delete(extensionStore, resourceURI, id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return extensionStore.getCurrentResourceId(id);
    }
}
