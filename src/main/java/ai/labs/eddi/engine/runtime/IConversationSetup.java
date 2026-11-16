/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.model.Context;

import java.net.URI;

public interface IConversationSetup {
    /** A conversation of its own — not a group member's thread. */
    default void createConversationDescriptor(String agentId, IAgent latestAgent, String userId, String conversationId,
                                              URI conversationUri)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        createConversationDescriptor(agentId, latestAgent, userId, conversationId, conversationUri, null);
    }

    /**
     * Writes the new conversation's descriptor.
     *
     * @param groupConversationId
     *            the group conversation this is a member's thread of, or
     *            {@code null}; recorded on the descriptor so the owner's own
     *            listing can leave member threads out
     */
    void createConversationDescriptor(String agentId, IAgent latestAgent, String userId, String conversationId, URI conversationUri,
                                      String groupConversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    String computeAnonymousUserIdIfEmpty(String userId, Context userIdContext);

    /**
     * Points the conversation's descriptor at another version of its agent, after
     * the conversation moved to it. Conversation listings filter on the
     * descriptor's agent URI.
     *
     * @since 6.5.0
     */
    void updateConversationAgentVersion(String conversationId, String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
