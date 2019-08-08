package ai.labs.channels.differ.storage;

import ai.labs.channels.differ.model.DifferConversationInfo;
import ai.labs.persistence.IResourceStore;

import java.util.List;

public interface IDifferConversationStore {
    List<String> getAllDifferConversationIds();

    DifferConversationInfo readDifferConversation(String conversationId) throws IResourceStore.ResourceStoreException;

    void createDifferConversation(DifferConversationInfo differConversationInfo)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException;

    void deleteDifferConversation(String conversationId);
}
