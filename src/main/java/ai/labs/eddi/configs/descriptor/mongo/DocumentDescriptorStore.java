package ai.labs.eddi.configs.descriptor.mongo;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.mongo.DescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.models.DocumentDescriptor;
import com.mongodb.reactivestreams.client.MongoDatabase;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

import static ai.labs.eddi.datastore.IResourceStore.*;

/**
 * @author ginccc
 */
@ApplicationScoped
public class DocumentDescriptorStore implements IDocumentDescriptorStore {
    private final DescriptorStore<DocumentDescriptor> descriptorStore;

    @Inject
    public DocumentDescriptorStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        descriptorStore = new DescriptorStore<>(database, documentBuilder, DocumentDescriptor.class);
    }

    @Override
    public List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index,
                                                    Integer limit, boolean includeDeleted)
            throws ResourceStoreException, ResourceNotFoundException {

        return descriptorStore.readDescriptors(type, filter, index, limit, includeDeleted);
    }

    @Override
    public DocumentDescriptor readDescriptor(String resourceId, Integer version)
            throws ResourceStoreException, ResourceNotFoundException {

        return descriptorStore.readDescriptor(resourceId, version);
    }

    @Override
    public DocumentDescriptor readDescriptorWithHistory(String resourceId, Integer version)
            throws ResourceStoreException, ResourceNotFoundException {

        return descriptorStore.readDescriptorWithHistory(resourceId, version);
    }

    @Override
    public Integer updateDescriptor(String resourceId, Integer version, DocumentDescriptor descriptor)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {

        return descriptorStore.updateDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, DocumentDescriptor descriptor)
            throws ResourceStoreException, ResourceNotFoundException {

        descriptorStore.setDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, DocumentDescriptor descriptor)
            throws ResourceStoreException {

        descriptorStore.createDescriptor(resourceId, version, descriptor);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return descriptorStore.getCurrentResourceId(id);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version)
            throws ResourceNotFoundException, ResourceModifiedException {

        descriptorStore.deleteDescriptor(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        descriptorStore.deleteAllDescriptor(resourceId);
    }
}
