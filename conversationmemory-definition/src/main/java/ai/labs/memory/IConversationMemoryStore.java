package ai.labs.memory;

import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.models.ConversationState;
import ai.labs.persistence.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IConversationMemoryStore {
    String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException;

    ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String botId, Integer botVersion) throws IResourceStore.ResourceStoreException;

    void setConversationState(String conversationId, ConversationState conversationState);

    void deleteConversationMemorySnapshot(String conversationId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    ConversationState getConversationState(String conversationId);

    Long getActiveConversationCount(String botId, Integer botVersion);
}
