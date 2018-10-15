package ai.labs.resources.rest.botmanagement;

import ai.labs.models.UserConversation;

import static ai.labs.persistence.IResourceStore.*;

public interface IUserConversationStore {
    UserConversation readUserConversation(String intent, String userId)
            throws ResourceNotFoundException, ResourceStoreException;

    void createUserConversation(UserConversation userConversation)
            throws ResourceAlreadyExistsException, ResourceStoreException;

    void deleteUserConversation(String intent, String userId) throws ResourceStoreException;
}
