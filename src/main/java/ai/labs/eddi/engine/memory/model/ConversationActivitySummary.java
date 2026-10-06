/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import java.time.Instant;

/**
 * The few fields of a stored conversation that a sweep over open conversations
 * needs — read as a projection, so such a sweep never deserializes a
 * conversation's steps, properties and outputs just to decide whether it is
 * idle.
 *
 * <p>
 * {@code lastInteraction} is the newest timestamp any step data of the
 * conversation carries — computed by the database, never from a loaded snapshot
 * — and is the same quantity the idle sweep has always aged conversations by.
 * </p>
 *
 * @param conversationId
 *            the conversation's id
 * @param conversationState
 *            the conversation's state, or {@code null} when it holds none this
 *            version knows
 * @param agentId
 *            the agent it runs on, or {@code null}
 * @param agentVersion
 *            that agent's version, or {@code null}
 * @param lastInteraction
 *            the newest step-data timestamp, or {@code null} when no step ever
 *            carried one
 */
public record ConversationActivitySummary(String conversationId, ConversationState conversationState, String agentId, Integer agentVersion,
        Instant lastInteraction) {
}
