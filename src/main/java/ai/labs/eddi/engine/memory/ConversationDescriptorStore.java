package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.DescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Date;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ConversationDescriptorStore implements IConversationDescriptorStore {
    private final DescriptorStore<ConversationDescriptor> descriptorStore;

    @Inject
    public ConversationDescriptorStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        descriptorStore = new DescriptorStore<>(storageFactory, documentBuilder, ConversationDescriptor.class);
    }

    @Override
    public void updateTimeStamp(String conversationId) {
        try {
            // Read the current descriptor, update the timestamp, and save it back
            var resourceId = descriptorStore.getCurrentResourceId(conversationId);
            var descriptor = descriptorStore.readDescriptor(conversationId, resourceId.getVersion());
            descriptor.setLastModifiedOn(new Date(System.currentTimeMillis()));
            descriptorStore.setDescriptor(conversationId, resourceId.getVersion(), descriptor);
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            // Log and skip — same behavior as the MongoDB version when document not found
        }
    }

    @Override
    public List<ConversationDescriptor> readDescriptors(String type, String filter, Integer index,
                                                        Integer limit, boolean includeDeleted)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.readDescriptors(type, filter, index, limit, includeDeleted);
    }

    @Override
    public ConversationDescriptor readDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.readDescriptor(resourceId, version);
    }

    @Override
    public ConversationDescriptor readDescriptorWithHistory(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.readDescriptorWithHistory(resourceId, version);
    }

    @Override
    public Integer updateDescriptor(String resourceId, Integer version, ConversationDescriptor descriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {

        return descriptorStore.updateDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, ConversationDescriptor descriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        descriptorStore.setDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, ConversationDescriptor descriptor)
            throws IResourceStore.ResourceStoreException {

        descriptorStore.createDescriptor(resourceId, version, descriptor);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return descriptorStore.getCurrentResourceId(id);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {

        descriptorStore.deleteDescriptor(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        descriptorStore.deleteAllDescriptor(resourceId);
    }
}
