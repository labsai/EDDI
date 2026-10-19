/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

/**
 * One conversation a compatibility-generation upgrade may move — a projection
 * of the four fields the selection needs, never the steps, properties or
 * outputs.
 *
 * @param conversationId
 *            the conversation's id
 * @param conversationState
 *            its stored state, or {@code null} when it holds none this version
 *            knows
 * @param agentVersion
 *            the agent version it is stored on
 * @param compatibilityGeneration
 *            its stored generation, {@code null} for a conversation that has
 *            none (it started before generations existed)
 */
public record ConversationGenerationCandidate(String conversationId, ConversationState conversationState, Integer agentVersion,
        Integer compatibilityGeneration) {
}
