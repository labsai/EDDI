package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;

import java.util.List;

/**
 * Service interface for multi-agent group conversations. No JAX-RS
 * dependencies.
 *
 * @author ginccc
 */
public interface IGroupConversationService {

    /**
     * Start a group discussion with the given question (synchronous).
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
     * Start a group discussion with event listener callbacks (synchronous).
     */
    GroupConversation discuss(String groupId, String question, String userId, int depth, GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    /**
     * Start a group discussion asynchronously. Creates the GroupConversation record
     * synchronously (so the caller gets the ID), then runs phases in a background
     * virtual thread. Progress is emitted via the listener.
     *
     * @return the newly created (IN_PROGRESS) GroupConversation
     */
    GroupConversation startAndDiscussAsync(String groupId, String question, String userId, GroupDiscussionEventListener listener)
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

    // --- Event listener for SSE streaming ---

    interface GroupDiscussionEventListener {
        default void onGroupStart(GroupConversationEventSink.GroupStartEvent event) {
        }
        default void onPhaseStart(GroupConversationEventSink.PhaseStartEvent event) {
        }
        default void onSpeakerStart(GroupConversationEventSink.SpeakerStartEvent event) {
        }
        default void onSpeakerComplete(GroupConversationEventSink.SpeakerCompleteEvent event) {
        }
        default void onPhaseComplete(GroupConversationEventSink.PhaseCompleteEvent event) {
        }
        default void onSynthesisStart(GroupConversationEventSink.SynthesisStartEvent event) {
        }
        default void onSynthesisComplete(GroupConversationEventSink.SynthesisCompleteEvent event) {
        }
        default void onGroupComplete(GroupConversationEventSink.GroupCompleteEvent event) {
        }
        default void onGroupError(GroupConversationEventSink.GroupErrorEvent event) {
        }
    }

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
