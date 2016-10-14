package io.sls.persistence;

import java.util.List;

/**
 * User: jarisch
 * Date: 19.11.12
 * Time: 17:30
 */
public interface IDescriptorStore<T> {
    List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    T readDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    Integer updateDescriptor(String resourceId, Integer version, T descriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException;

    void setDescriptor(String resourceId, Integer version, T descriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void createDescriptor(String resourceId, Integer version, T descriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;

    void deleteDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException;

    void deleteAllDescriptor(String resourceId);
}
