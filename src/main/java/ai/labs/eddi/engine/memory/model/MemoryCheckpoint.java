/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.configs.properties.model.Property;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of conversation state at a specific point in time. Used
 * for rollback after failed tool executions and for conversation forking.
 * <p>
 * Each checkpoint captures the current {@code stepIndex} and a deep copy of the
 * full {@link Property} objects (including scope and visibility) so that
 * rollback restores properties with their original metadata intact.
 *
 * @param checkpointId
 *            unique identifier for this checkpoint
 * @param conversationId
 *            the conversation this checkpoint belongs to
 * @param parentConversationId
 *            if forked, the originating conversation ID (null otherwise)
 * @param stepIndex
 *            the step index at the time of snapshot (0-based)
 * @param propertiesCopy
 *            snapshot of conversation properties at checkpoint time (preserves
 *            scope and visibility)
 * @param createdAt
 *            when the checkpoint was created
 * @param triggeredBy
 *            human-readable description of what triggered the snapshot
 * @param triggeredByClass
 *            the tool or component class that caused the checkpoint
 * @since 6.0.0
 */
public record MemoryCheckpoint(
        String checkpointId,
        String conversationId,
        String parentConversationId,
        int stepIndex,
        Map<String, Property> propertiesCopy,
        Instant createdAt,
        String triggeredBy,
        String triggeredByClass) {

    /**
     * Create a new checkpoint for the current conversation state.
     *
     * @param conversationId
     *            the conversation ID
     * @param stepIndex
     *            the current step index
     * @param properties
     *            the current conversation properties (will be deep-copied)
     * @param triggeredBy
     *            what triggered this checkpoint
     * @param triggeredByClass
     *            the class name of the trigger
     * @return a new MemoryCheckpoint
     */
    public static MemoryCheckpoint create(String conversationId, int stepIndex,
                                          Map<String, Property> properties, String triggeredBy, String triggeredByClass) {
        return new MemoryCheckpoint(
                java.util.UUID.randomUUID().toString(),
                conversationId,
                null,
                stepIndex,
                copyProperties(properties),
                Instant.now(),
                triggeredBy,
                triggeredByClass);
    }

    /**
     * Create a checkpoint with a known parent (for forking).
     */
    public MemoryCheckpoint withParent(String parentId) {
        return new MemoryCheckpoint(checkpointId, conversationId, parentId, stepIndex,
                propertiesCopy, createdAt, triggeredBy, triggeredByClass);
    }

    /**
     * Deep-copy the properties map. Each {@link Property} is cloned via its
     * all-args constructor to isolate checkpoint state from live memory.
     */
    private static Map<String, Property> copyProperties(Map<String, Property> original) {
        if (original == null || original.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Property> copy = new LinkedHashMap<>(original.size());
        for (Map.Entry<String, Property> entry : original.entrySet()) {
            Property p = entry.getValue();
            Property cloned = new Property(
                    p.getName(), p.getValueString(), p.getValueObject(),
                    p.getValueList(), p.getValueInt(), p.getValueFloat(),
                    p.getValueBoolean(), p.getScope(), p.getVisibility());
            copy.put(entry.getKey(), cloned);
        }
        return Collections.unmodifiableMap(copy);
    }
}
