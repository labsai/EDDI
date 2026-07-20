/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Store interface for group conversation transcripts. DB-agnostic, uses
 * {@code IResourceStorageFactory} under the hood.
 *
 * @author ginccc
 */
public interface IGroupConversationStore {

    String create(GroupConversation conversation) throws IResourceStore.ResourceStoreException;

    GroupConversation read(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void update(GroupConversation conversation) throws IResourceStore.ResourceStoreException;

    void delete(String id) throws IResourceStore.ResourceStoreException;

    List<GroupConversation> listByGroupId(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException;

    /**
     * Atomically transition a group conversation from expectedState to newState.
     * Returns true if the transition succeeded (state matched), false if the state
     * had already changed (concurrent modification).
     */
    boolean compareAndSetState(String id, GroupConversation.GroupConversationState expectedState,
                               GroupConversation.GroupConversationState newState)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    /**
     * Update the group conversation only if it is currently in the expected state.
     * Throws {@link IResourceStore.ResourceModifiedException} if the conversation
     * exists but the state does not match (CAS conflict — REST: 409), and
     * {@link GroupConversationGoneException} (unchecked) if the conversation was
     * deleted concurrently (REST: 404) — the two zero-match outcomes are
     * deliberately distinguishable so callers never report a deletion as a state
     * conflict.
     */
    void updateIfState(GroupConversation gc, GroupConversation.GroupConversationState expectedState)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException;

    /**
     * The group conversation targeted by a conditional update no longer exists.
     * Unchecked so existing CAS call sites keep compiling; surfaces that expose the
     * operation should map it to 404 rather than a 409 conflict.
     */
    class GroupConversationGoneException extends RuntimeException {
        public GroupConversationGoneException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Find all group conversations currently in the given state.
     */
    List<GroupConversation> findByState(GroupConversation.GroupConversationState state)
            throws IResourceStore.ResourceStoreException;

    /**
     * Bounded variant of {@link #findByState} with an optional group filter — backs
     * the pending-approvals listing endpoint.
     *
     * @param groupId
     *            restrict results to this group configuration ID; {@code null} for
     *            all groups
     * @param limit
     *            maximum number of conversations to return
     */
    List<GroupConversation> findByState(GroupConversation.GroupConversationState state, String groupId, int limit)
            throws IResourceStore.ResourceStoreException;
}
