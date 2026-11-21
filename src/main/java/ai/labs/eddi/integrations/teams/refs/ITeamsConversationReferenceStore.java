/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.refs;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where to reach a Teams conversation again: the Bot Connector
 * {@code serviceUrl} and ids of the last verified activity seen in it. A reply
 * within the same turn uses the activity itself; this record is what a later,
 * proactive message (an approval notice, a scheduled reply) needs, since the
 * Bot Connector has no lookup of its own.
 * <p>
 * Written only from activities whose token was verified and whose tenant passed
 * the integration's check, so the {@code serviceUrl} here is one the Bot
 * Connector itself vouched for. One row per (application, conversation); a
 * personal chat's row names its user, and is erased with that user's data.
 *
 * @since 6.7.0
 */
public interface ITeamsConversationReferenceStore {

    /**
     * A conversation reference.
     *
     * @param appId
     *            the bot's app id (lower-case)
     * @param conversationId
     *            the Teams conversation id, as the activity carried it
     * @param conversationType
     *            {@code personal}, {@code groupChat} or {@code channel}
     * @param serviceUrl
     *            the Bot Connector endpoint for this conversation
     * @param tenantId
     *            the tenant (lower-case)
     * @param botId
     *            the bot's id in the conversation ({@code recipient.id})
     * @param userId
     *            the EDDI user id of a personal chat's user; {@code null} otherwise
     * @param updatedAt
     *            when the reference was last written
     */
    record TeamsConversationReference(String appId, String conversationId, String conversationType, String serviceUrl, String tenantId,
            String botId, String userId, Instant updatedAt) {
    }

    /** Inserts or replaces the reference for its (appId, conversationId). */
    void upsert(TeamsConversationReference reference);

    Optional<TeamsConversationReference> find(String appId, String conversationId);

    /** The references naming {@code userId}, for export. */
    List<TeamsConversationReference> findByUser(String userId);

    /** Deletes the references naming {@code userId}; returns how many. */
    long deleteByUser(String userId);
}
