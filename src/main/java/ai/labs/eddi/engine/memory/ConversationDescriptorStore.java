/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.DescriptorStore;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.memory.descriptor.ConversationDescriptorMemo;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Date;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ConversationDescriptorStore implements IConversationDescriptorStore {
    private static final Logger LOGGER = Logger.getLogger(ConversationDescriptorStore.class);
    private final DescriptorStore<ConversationDescriptor> descriptorStore;

    /**
     * Every write forgets what the current request remembered about the
     * conversation's descriptor. Field-injected so that directly constructed
     * instances keep working; {@code null} there means nothing is remembered.
     */
    @Inject
    Instance<ConversationDescriptorMemo> descriptorMemoInstance;

    @Inject
    public ConversationDescriptorStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        descriptorStore = new DescriptorStore<>(storageFactory, documentBuilder, ConversationDescriptor.class);
    }

    /**
     * Stamps the conversation's {@code lastModifiedOn}, which the retention sweep
     * and the listings sort and filter on, with one update of that field.
     * <p>
     * This used to be a read-modify-write: the current id, the descriptor read
     * twice over and a version-checked replace of the whole document — three reads
     * and a write on every turn, and a replace that could undo a concurrent edit of
     * any other descriptor field. A failure is logged and swallowed rather than
     * thrown: the turn has already been stored, and the next turn stamps again.
     */
    @Override
    public void updateTimeStamp(String conversationId) {
        ConversationDescriptorMemo.forget(descriptorMemoInstance, conversationId);
        long now = System.currentTimeMillis();
        try {
            if (!descriptorStore.touchLastModified(conversationId, now)) {
                LOGGER.debugf("No live descriptor to stamp for conversation %s", sanitize(conversationId));
            }
        } catch (UnsupportedOperationException e) {
            updateTimeStampByRewrite(conversationId, now);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not update lastModifiedOn of conversation %s: %s", sanitize(conversationId), e.getMessage());
        }
    }

    /** For a storage backend that cannot update a single field. */
    private void updateTimeStampByRewrite(String conversationId, long now) {
        try {
            var resourceId = descriptorStore.getCurrentResourceId(conversationId);
            var descriptor = descriptorStore.readDescriptor(conversationId, resourceId.getVersion());
            descriptor.setLastModifiedOn(new Date(now));
            descriptorStore.setDescriptor(conversationId, resourceId.getVersion(), descriptor);
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            LOGGER.debugf("No live descriptor to stamp for conversation %s", sanitize(conversationId));
        }
    }

    @Override
    public List<ConversationDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.readDescriptors(type, filter, index, limit, includeDeleted);
    }

    @Override
    public List<ConversationDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted,
                                                        List<IResourceFilter.QueryFilters> restrictions)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.readDescriptorsRestricted(type, filter, index, limit, includeDeleted, restrictions);
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
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {

        ConversationDescriptorMemo.forget(descriptorMemoInstance, resourceId);
        return descriptorStore.updateDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, ConversationDescriptor descriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        ConversationDescriptorMemo.forget(descriptorMemoInstance, resourceId);
        descriptorStore.setDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, ConversationDescriptor descriptor) throws IResourceStore.ResourceStoreException {

        ConversationDescriptorMemo.forget(descriptorMemoInstance, resourceId);
        descriptorStore.createDescriptor(resourceId, version, descriptor);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return descriptorStore.getCurrentResourceId(id);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {

        ConversationDescriptorMemo.forget(descriptorMemoInstance, resourceId);
        descriptorStore.deleteDescriptor(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        ConversationDescriptorMemo.forget(descriptorMemoInstance, resourceId);
        descriptorStore.deleteAllDescriptor(resourceId);
    }

    @Override
    public List<ConversationDescriptor> findByOriginId(String originId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorStore.findByOriginId(originId);
    }
}
