/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The few values of a finished turn the {@code OUTPUT_MATCH} rules of its agent
 * read, copied off the turn — never the live conversation memory.
 *
 * @param properties
 *            the conversation properties the rules name (all of them on a cache
 *            miss), as plain values
 * @param outputText
 *            the turn's output text, at most {@link #MAX_OUTPUT_CHARS};
 *            {@code null} when no rule reads {@code output.json}
 * @param actions
 *            the turn's actions; {@code null} when no rule reads them
 */
public record TurnSnapshot(String conversationId, String agentId, Integer agentVersion, Map<String, Object> properties, String outputText,
        List<String> actions, String userId, Instant capturedAt) {

    /** The longest output that is copied (and parsed as JSON). */
    public static final int MAX_OUTPUT_CHARS = 64 * 1024;

    public TurnSnapshot {
        properties = properties == null ? Map.of() : properties;
    }

    /** A snapshot without the conversation owner. */
    public TurnSnapshot(String conversationId, String agentId, Integer agentVersion, Map<String, Object> properties, String outputText,
            List<String> actions) {
        this(conversationId, agentId, agentVersion, properties, outputText, actions, null, null);
    }

    /** A snapshot without a capture time. */
    public TurnSnapshot(String conversationId, String agentId, Integer agentVersion, Map<String, Object> properties, String outputText,
            List<String> actions, String userId) {
        this(conversationId, agentId, agentVersion, properties, outputText, actions, userId, null);
    }
}
