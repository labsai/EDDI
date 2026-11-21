/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The conversation behind a chat thread: one EDDI conversation per (thread
 * intent, user), kept in {@link IUserConversationStore}, replaced by a fresh
 * one when it has ended, and created race-safely (the stored mapping wins; the
 * loser's conversation is ended).
 * <p>
 * Plan item 0.6, extracted for its second consumer (Teams). Slack still carries
 * its own copy, which adds the legacy un-namespaced user-id adoption; moving
 * Slack onto this class is a separate change.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ChatThreadConversations {

    private static final Logger LOGGER = Logger.getLogger(ChatThreadConversations.class);

    /** The context key a channel conversation is started with. */
    public static final String CONTEXT_CHANNEL_INTENT = "channelIntent";

    /**
     * The conversation for a thread.
     *
     * @param conversationId
     *            the conversation to send to
     * @param replaced
     *            whether it was just created in place of an ended one
     * @param replacedEndReason
     *            why the ended one ended, when known
     */
    public record ThreadConversation(String conversationId, boolean replaced, String replacedEndReason) {
        static ThreadConversation existing(String conversationId) {
            return new ThreadConversation(conversationId, false, null);
        }
    }

    private record CreatedConversation(String conversationId, boolean won) {
    }

    private final IConversationService conversationService;
    private final IUserConversationStore userConversationStore;

    @Inject
    public ChatThreadConversations(IConversationService conversationService, IUserConversationStore userConversationStore) {
        this.conversationService = conversationService;
        this.userConversationStore = userConversationStore;
    }

    /**
     * The thread's live conversation, creating one (or replacing an ended one) as
     * needed.
     */
    public ThreadConversation open(String agentId, String eddiUserId, String intent) throws Exception {
        UserConversation existing = userConversationStore.readUserConversation(intent, eddiUserId);
        if (existing != null && existing.getConversationId() != null) {
            String endReason = endReasonIfEnded(existing.getConversationId());
            if (endReason == null) {
                return ThreadConversation.existing(existing.getConversationId());
            }
            return replaceEnded(agentId, eddiUserId, intent, existing.getConversationId(), endReason.isEmpty() ? null : endReason);
        }
        return ThreadConversation.existing(create(agentId, eddiUserId, intent).conversationId());
    }

    /**
     * Replaces {@code endedConversationId} with a fresh conversation, unless
     * another caller already did.
     */
    public ThreadConversation replaceEnded(String agentId, String eddiUserId, String intent, String endedConversationId, String endReason)
            throws Exception {
        userConversationStore.deleteUserConversationIfMatches(intent, eddiUserId, endedConversationId);
        CreatedConversation created = create(agentId, eddiUserId, intent);
        if (created.won()) {
            LOGGER.infof("Replaced ended conversation %s in %s with %s (end reason: %s)", sanitize(endedConversationId), sanitize(intent),
                    sanitize(created.conversationId()), endReason != null ? sanitize(endReason) : "none");
        }
        return new ThreadConversation(created.conversationId(), created.won(), created.won() ? endReason : null);
    }

    /** The end reason of a conversation, or {@code null} when unknown. */
    public String endReasonOf(String conversationId) {
        try {
            ConversationMemorySnapshot snapshot = conversationService.getConversationMemorySnapshot(conversationId);
            return snapshot != null ? snapshot.getEndReason() : null;
        } catch (Exception e) {
            LOGGER.debugf("Could not read the end reason of conversation %s: %s", sanitize(conversationId), e.getMessage());
            return null;
        }
    }

    /**
     * {@code null} when the conversation is live (or its state cannot be read: it
     * is kept), otherwise its end reason, {@code ""} when it has none.
     */
    private String endReasonIfEnded(String conversationId) {
        ConversationState state;
        try {
            state = conversationService.getConversationState(conversationId);
        } catch (IConversationService.ConversationNotFoundException e) {
            return "";
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not read the state of conversation %s — keeping it: %s", sanitize(conversationId), e.getMessage());
            return null;
        }
        if (state != ConversationState.ENDED) {
            return null;
        }
        String reason = endReasonOf(conversationId);
        return reason == null ? "" : reason;
    }

    private CreatedConversation create(String agentId, String eddiUserId, String intent) throws Exception {
        var result = conversationService.startConversation(Deployment.Environment.production, agentId, eddiUserId,
                Map.of(CONTEXT_CHANNEL_INTENT, new Context(Context.ContextType.string, intent)));
        var mapping = new UserConversation(intent, eddiUserId, Deployment.Environment.production, agentId, result.conversationId());
        try {
            userConversationStore.createUserConversation(mapping);
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            // Another message of the same thread created the mapping first: the stored
            // one wins and ours is an orphan.
            UserConversation winner = userConversationStore.readUserConversation(intent, eddiUserId);
            if (winner != null && winner.getConversationId() != null) {
                endOrphaned(result.conversationId());
                return new CreatedConversation(winner.getConversationId(), false);
            }
            LOGGER.warnf("Create conflict for %s/%s but no stored mapping could be read", sanitize(intent), sanitize(eddiUserId));
        }
        return new CreatedConversation(result.conversationId(), true);
    }

    private void endOrphaned(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            conversationService.endConversation(conversationId, "system:lost-create-race");
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not end the orphaned conversation %s", sanitize(conversationId));
        }
    }
}
