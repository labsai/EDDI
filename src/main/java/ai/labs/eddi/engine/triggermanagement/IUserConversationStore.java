/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;

import java.util.List;

import static ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;

public interface IUserConversationStore {
    UserConversation readUserConversation(String intent, String userId) throws IResourceStore.ResourceStoreException;

    /**
     * Reverse lookup: find the managed-conversation mapping that references the
     * given conversationId. Used to resolve a conversation back to its originating
     * channel/thread (e.g. after a HITL resume, to push the outcome to Slack).
     * <p>
     * A conversationId is expected to appear in at most one mapping. If more than
     * one references it, an arbitrary one is returned.
     *
     * @return the mapping, or {@code null} if no mapping references this
     *         conversationId
     */
    UserConversation readUserConversationByConversationId(String conversationId) throws IResourceStore.ResourceStoreException;

    void createUserConversation(UserConversation userConversation) throws ResourceAlreadyExistsException, IResourceStore.ResourceStoreException;

    void deleteUserConversation(String intent, String userId) throws IResourceStore.ResourceStoreException;

    // === GDPR ===

    /**
     * Delete all managed conversation mappings for a user (GDPR Art. 17).
     *
     * @return number of mappings deleted
     */
    long deleteAllForUser(String userId) throws IResourceStore.ResourceStoreException;

    /**
     * Get all managed conversation mappings for a user (GDPR Art. 15/20 export).
     */
    List<UserConversation> getAllForUser(String userId) throws IResourceStore.ResourceStoreException;
}
