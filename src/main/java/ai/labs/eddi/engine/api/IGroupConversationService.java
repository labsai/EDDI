/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;

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

    /**
     * Cancel a running or paused group discussion.
     *
     * @param conversationId
     *            the group conversation to cancel
     * @param mode
     *            CANCEL_GRACEFUL (stop at next boundary) or CANCEL_IMMEDIATE
     *            (interrupt the blocking wave)
     * @return true if the discussion was cancelled or an in-flight leg was
     *         signalled to stop; false if it was already in a terminal state or a
     *         concurrent state change won the race — maps to HTTP 409
     * @throws IResourceStore.ResourceNotFoundException
     *             if no group conversation with that id exists
     * @throws IResourceStore.ResourceStoreException
     *             on persistence failures
     */
    boolean cancelDiscussion(String conversationId, ControlSignal mode)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    GroupConversation resumeDiscussion(String groupConversationId,
                                       GroupApprovalRequest request,
                                       GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException,
            IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException;

    /**
     * List group conversations currently awaiting human approval, as bounded
     * summaries (no transcripts). Used by dashboards and admin UIs.
     *
     * @param groupId
     *            restrict to this group configuration ID; {@code null} for all
     *            groups
     * @param limit
     *            maximum number of summaries to return (clamped to [1, 1000])
     */
    List<ai.labs.eddi.engine.model.PendingApprovalSummary> listGroupPendingApprovals(String groupId, int limit)
            throws IResourceStore.ResourceStoreException;

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
        default void onGroupComplete(GroupConversationEventSink.GroupCompleteEvent event) {
        }
        default void onGroupError(GroupConversationEventSink.GroupErrorEvent event) {
        }
        default void onTaskPlanCreated(GroupConversationEventSink.TaskPlanCreatedEvent event) {
        }
        default void onTaskVerified(GroupConversationEventSink.TaskVerifiedEvent event) {
        }
        default void onHitlPause(GroupConversationEventSink.HitlPauseEvent event) {
        }
        default void onHitlResume(GroupConversationEventSink.HitlResumeEvent event) {
        }
        default void onCancelled(GroupConversationEventSink.CancelledEvent event) {
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
