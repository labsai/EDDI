package io.sls.memory;

import io.sls.memory.model.ConversationMemorySnapshot;
import io.sls.memory.model.ConversationState;
import io.sls.persistence.IResourceStore;

/**
 * @author ginccc
 */
public interface IConversationMemoryStore {
    String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException;

    ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void setConversationState(String conversationId, ConversationState conversationState);

    void deleteConversationMemorySnapshot(String conversationId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    ConversationState getConversationState(String conversationId);
}
