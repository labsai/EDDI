/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.DescriptorStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

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
    public DocumentDescriptorStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        descriptorStore = new DescriptorStore<>(storageFactory, documentBuilder, DocumentDescriptor.class);
    }

    @Override
    public List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted)
            throws ResourceStoreException, ResourceNotFoundException {

        return descriptorStore.readDescriptors(type, filter, index, limit, includeDeleted);
    }

    @Override
    public DocumentDescriptor readDescriptor(String resourceId, Integer version) throws ResourceStoreException, ResourceNotFoundException {

        return descriptorStore.readDescriptor(resourceId, version);
    }

    @Override
    public DocumentDescriptor readDescriptorWithHistory(String resourceId, Integer version) throws ResourceStoreException, ResourceNotFoundException {

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
    public void createDescriptor(String resourceId, Integer version, DocumentDescriptor descriptor) throws ResourceStoreException {

        descriptorStore.createDescriptor(resourceId, version, descriptor);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return descriptorStore.getCurrentResourceId(id);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version) throws ResourceNotFoundException, ResourceModifiedException {

        descriptorStore.deleteDescriptor(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        descriptorStore.deleteAllDescriptor(resourceId);
    }

    @Override
    public List<DocumentDescriptor> findByOriginId(String originId) throws ResourceStoreException, ResourceNotFoundException {
        return descriptorStore.findByOriginId(originId);
    }
}
