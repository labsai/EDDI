package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;

import java.util.List;

/**
 * @author ginccc
 */
public interface IConversationMemoryStore {
    String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException;

    ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException;

    void setConversationState(String conversationId, ConversationState conversationState);

    void deleteConversationMemorySnapshot(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    ConversationState getConversationState(String conversationId);

    Long getActiveConversationCount(String agentId, Integer agentVersion);

    List<String> getEndedConversationIds();

    // === GDPR ===

    /**
     * Find all conversation IDs belonging to a specific user.
     *
     * @param userId
     *            the user identifier
     * @return list of conversation IDs
     */
    List<String> getConversationIdsByUserId(String userId);

    /**
     * Delete all conversations belonging to a specific user (GDPR Art. 17).
     *
     * @param userId
     *            the user identifier
     * @return number of conversations deleted
     */
    long deleteConversationsByUserId(String userId);
}
