package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Service interface for multi-agent group conversations. No JAX-RS
 * dependencies.
 *
 * @author ginccc
 */
public interface IGroupConversationService {

    /**
     * Start a group discussion with the given question.
     *
     * @param groupId
     *            the group configuration ID
     * @param question
     *            the question to discuss
     * @param userId
     *            the user who initiated the discussion
     * @param depth
     *            recursion depth (0 = top-level)
     * @return the completed group conversation with transcript
     */
    GroupConversation discuss(String groupId, String question, String userId, int depth)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    /**
     * Read a group conversation transcript.
     */
    GroupConversation readGroupConversation(String groupConversationId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    /**
     * Delete a group conversation and cascade-delete member conversations.
     */
    void deleteGroupConversation(String groupConversationId) throws IResourceStore.ResourceStoreException;

    /**
     * List group conversations for a given group config.
     */
    List<GroupConversation> listGroupConversations(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException;

    // --- Exceptions ---

    class GroupDiscussionException extends Exception {
        public GroupDiscussionException(String message) {
            super(message);
        }

        public GroupDiscussionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class GroupDepthExceededException extends GroupDiscussionException {
        public GroupDepthExceededException(String message) {
            super(message);
        }
    }
}
