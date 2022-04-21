package ai.labs.eddi.datastore.serialization;

import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IDescriptorStore<T> {
    List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    T readDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    T readDescriptorWithHistory(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    Integer updateDescriptor(String resourceId, Integer version, T descriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException;

    void setDescriptor(String resourceId, Integer version, T descriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void createDescriptor(String resourceId, Integer version, T descriptor) throws IResourceStore.ResourceStoreException;

    IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;

    void deleteDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException;

    void deleteAllDescriptor(String resourceId);
}
