/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

/**
 * A single dead-letter entry representing a failed conversation task.
 *
 * @param id
 *            unique identifier (the JetStream stream sequence in cluster mode,
 *            an in-memory counter otherwise)
 * @param conversationId
 *            the conversation this task belonged to
 * @param error
 *            the error message from the final failure
 * @param timestamp
 *            epoch millis when the task was dead-lettered
 * @param payload
 *            full JSON payload (for inspection)
 * @param turn
 *            the replayable description of the turn — agent, environment, user,
 *            input and (when {@code eddi.coordinator.dead-letter.capture-input}
 *            is on) the context — or {@code null} when the task carried none (a
 *            HITL resume, a group member turn). Only an entry with a captured
 *            input can be replayed.
 */
public record DeadLetterEntry(String id, String conversationId, String error, long timestamp, String payload, Map<String, Object> turn) {

    /** The shape before the turn descriptor existed. */
    public DeadLetterEntry(String id, String conversationId, String error, long timestamp, String payload) {
        this(id, conversationId, error, timestamp, payload, null);
    }

    /** Whether a replay can rebuild the turn: it needs the agent and the input. */
    @JsonIgnore
    public boolean isReplayable() {
        return turn != null && turn.get("input") != null && turn.get("agentId") != null;
    }
}
