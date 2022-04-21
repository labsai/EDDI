package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.models.ConversationState;

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
