/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.model.Deployment;

/**
 * The few fields of a stored conversation that the conversation listing needs —
 * read as a projection, so a listing never deserializes a conversation's steps,
 * properties and outputs just to show one row.
 *
 * @param conversationId
 *            the conversation's id
 * @param userId
 *            the owner recorded in the conversation, or {@code null}
 * @param environment
 *            the environment the conversation runs in, or {@code null}
 * @param conversationState
 *            the conversation's state, or {@code null}
 * @param agentId
 *            the agent it runs on, or {@code null}
 * @param agentVersion
 *            that agent's version, or {@code null}
 * @param conversationStepCount
 *            how many steps the conversation holds
 */
public record ConversationListingSummary(String conversationId, String userId, Deployment.Environment environment,
        ConversationState conversationState, String agentId, Integer agentVersion, int conversationStepCount) {

    /** The summary of a conversation already loaded in full. */
    public static ConversationListingSummary of(ConversationMemorySnapshot snapshot, String conversationId) {
        var steps = snapshot.getConversationSteps();
        return new ConversationListingSummary(conversationId, snapshot.getUserId(), snapshot.getEnvironment(),
                snapshot.getConversationState(), snapshot.getAgentId(), snapshot.getAgentVersion(), steps == null ? 0 : steps.size());
    }
}
