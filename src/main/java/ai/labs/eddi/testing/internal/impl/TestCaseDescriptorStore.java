package ai.labs.eddi.testing.internal.impl;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.DescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.testing.descriptor.ITestCaseDescriptorStore;
import ai.labs.eddi.testing.descriptor.model.TestCaseDescriptor;
import com.mongodb.reactivestreams.client.MongoDatabase;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class TestCaseDescriptorStore implements ITestCaseDescriptorStore {
    private final DescriptorStore<TestCaseDescriptor> descriptorStore;

    @Inject
    public TestCaseDescriptorStore(MongoDatabase mongoDatabase, IDocumentBuilder documentBuilder) {
        descriptorStore = new DescriptorStore<>(mongoDatabase, documentBuilder, TestCaseDescriptor.class);
    }

    @Override
    public List<TestCaseDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorStore.readDescriptors(type, filter, index, limit, includeDeleted);
    }

    @Override
    public TestCaseDescriptor readDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorStore.readDescriptor(resourceId, version);
    }

    @Override
    public TestCaseDescriptor readDescriptorWithHistory(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorStore.readDescriptorWithHistory(resourceId, version);
    }

    @Override
    public Integer updateDescriptor(String resourceId, Integer version, TestCaseDescriptor descriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        return descriptorStore.updateDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, TestCaseDescriptor descriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        descriptorStore.setDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, TestCaseDescriptor descriptor) throws IResourceStore.ResourceStoreException {
        descriptorStore.createDescriptor(resourceId, version, descriptor);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return descriptorStore.getCurrentResourceId(id);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
        descriptorStore.deleteDescriptor(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {

    }
}
