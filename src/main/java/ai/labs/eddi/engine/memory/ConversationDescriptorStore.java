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
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor.TitleSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    @Override
    public boolean writeGeneratedTitle(String conversationId, String title, TitleSource source) {
        Objects.requireNonNull(title, "title");
        if (source != TitleSource.AUTO && source != TitleSource.LLM) {
            throw new IllegalArgumentException("Not a generated title source: " + source);
        }
        // AUTO lands only where there is no title at all; LLM also replaces AUTO.
        List<String> replaceable = source == TitleSource.LLM ? List.of(TitleSource.AUTO.name()) : List.of();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(ConversationDescriptor.FIELD_TITLE, title);
        fields.put(ConversationDescriptor.FIELD_TITLE_SOURCE, source.name());
        return setFields(conversationId, fields, ConversationDescriptor.FIELD_TITLE_SOURCE, replaceable);
    }

    @Override
    public boolean writeOwnerEdit(String conversationId, String title, Boolean pinned) {
        if (title == null && pinned == null) {
            throw new IllegalArgumentException("Nothing to write: neither a title nor a pin");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (title != null) {
            fields.put(ConversationDescriptor.FIELD_TITLE, title);
            fields.put(ConversationDescriptor.FIELD_TITLE_SOURCE, TitleSource.USER.name());
        }
        if (pinned != null) {
            fields.put(ConversationDescriptor.FIELD_PINNED, pinned);
        }
        return setFields(conversationId, fields, null, List.of());
    }

    private boolean setFields(String conversationId, Map<String, Object> fields, String guardField, Collection<String> replaceable) {
        ConversationDescriptorMemo.forget(descriptorMemoInstance, conversationId);
        try {
            return descriptorStore.setFieldsIf(conversationId, fields, guardField, replaceable);
        } catch (UnsupportedOperationException e) {
            return setFieldsByRewrite(conversationId, fields, guardField, replaceable);
        }
    }

    /**
     * For a storage backend that cannot update single fields: a read-modify-write
     * of the whole descriptor. Not atomic — the backends EDDI ships both take the
     * single update above.
     */
    private boolean setFieldsByRewrite(String conversationId, Map<String, Object> fields, String guardField, Collection<String> replaceable) {
        try {
            var resourceId = descriptorStore.getCurrentResourceId(conversationId);
            var descriptor = descriptorStore.readDescriptor(conversationId, resourceId.getVersion());
            if (guardField != null && descriptor.getTitleSource() != null && !replaceable.contains(descriptor.getTitleSource().name())) {
                return false;
            }
            if (fields.containsKey(ConversationDescriptor.FIELD_TITLE)) {
                descriptor.setTitle((String) fields.get(ConversationDescriptor.FIELD_TITLE));
                descriptor.setTitleSource(TitleSource.valueOf((String) fields.get(ConversationDescriptor.FIELD_TITLE_SOURCE)));
            }
            if (fields.get(ConversationDescriptor.FIELD_PINNED) instanceof Boolean pinned) {
                descriptor.setPinned(pinned);
            }
            descriptorStore.setDescriptor(conversationId, resourceId.getVersion(), descriptor);
            return true;
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            LOGGER.debugf("No live descriptor to update for conversation %s", sanitize(conversationId));
            return false;
        }
    }
}
