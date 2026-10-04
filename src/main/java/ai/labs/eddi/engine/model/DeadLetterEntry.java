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
 * @param reason
 *            why the turn was dead-lettered: {@link #REASON_FENCED},
 *            {@link #REASON_TIMEOUT} or {@link #REASON_FAILED}; {@code null}
 *            for an entry written before the reason was recorded
 * @param nodeId
 *            the node the turn failed on, {@code null} on a single node and for
 *            older entries
 * @param fence
 *            for a {@link #REASON_FENCED} entry: {@code token} (what the
 *            refused write carried) and {@code storedFence} (what the
 *            conversation already had); {@code null} otherwise
 */
public record DeadLetterEntry(String id, String conversationId, String error, long timestamp, String payload, Map<String, Object> turn,
        String reason, String nodeId, Map<String, Object> fence) {

    /**
     * The write was refused by the cluster fence: another node took the
     * conversation over.
     */
    public static final String REASON_FENCED = "fenced";
    /** The turn ran out of time. */
    public static final String REASON_TIMEOUT = "timeout";
    /** Any other failure. */
    public static final String REASON_FAILED = "failed";

    /** Not replayable: the client flagged the turn {@code secretInput}. */
    public static final String NOT_REPLAYABLE_SECRET = "SECRET_INPUT";
    /** Not replayable: input capture is switched off. */
    public static final String NOT_REPLAYABLE_NOT_CAPTURED = "INPUT_NOT_CAPTURED";
    /** Not replayable: not a conversation turn (HITL resume, group member turn). */
    public static final String NOT_REPLAYABLE_NOT_A_TURN = "NOT_A_TURN";

    /** The shape before the turn descriptor existed. */
    public DeadLetterEntry(String id, String conversationId, String error, long timestamp, String payload) {
        this(id, conversationId, error, timestamp, payload, null);
    }

    /** The shape before reason, node and fence were recorded. */
    public DeadLetterEntry(String id, String conversationId, String error, long timestamp, String payload, Map<String, Object> turn) {
        this(id, conversationId, error, timestamp, payload, turn, null, null, null);
    }

    /**
     * Why {@link #isReplayable()} is false — one of the {@code NOT_REPLAYABLE_*}
     * codes — or {@code null} when the entry can be replayed.
     */
    public String notReplayableReason() {
        if (isReplayable()) {
            return null;
        }
        if (turn == null || turn.get("agentId") == null) {
            return NOT_REPLAYABLE_NOT_A_TURN;
        }
        if (Boolean.TRUE.equals(turn.get("secretInput"))) {
            return NOT_REPLAYABLE_SECRET;
        }
        return NOT_REPLAYABLE_NOT_CAPTURED;
    }

    /** Whether a replay can rebuild the turn: it needs the agent and the input. */
    @JsonIgnore
    public boolean isReplayable() {
        return turn != null && turn.get("input") != null && turn.get("agentId") != null;
    }
}
