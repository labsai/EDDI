package ai.labs.resources.impl.extensions.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.extensions.IExtensionStore;
import ai.labs.resources.rest.extensions.IRestExtensionStore;
import ai.labs.resources.rest.extensions.model.ExtensionDefinition;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestExtensionStore extends RestVersionInfo<ExtensionDefinition> implements IRestExtensionStore {
    private final IExtensionStore extensionStore;

    @Inject
    public RestExtensionStore(IExtensionStore extensionStore,
                              IDocumentDescriptorStore documentDescriptorStore) {
        super(resourceURI, extensionStore, documentDescriptorStore);
        this.extensionStore = extensionStore;
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
        return read(id, version);
    }

    @Override
    public Response updateExtension(String id, Integer version, ExtensionDefinition extension) {
        return update(id, version, extension);
    }

    @Override
    public Response createExtension(ExtensionDefinition extension) {
        return create(extension);
    }

    @Override
    public Response deleteExtension(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return extensionStore.getCurrentResourceId(id);
    }
}
