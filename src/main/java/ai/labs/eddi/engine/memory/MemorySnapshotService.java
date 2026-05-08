/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;
import ai.labs.eddi.configs.properties.model.Property;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Service for creating and restoring memory snapshots. Provides automatic
 * checkpointing before state-changing tool executions and rollback on failure.
 * <p>
 * <strong>Thread safety:</strong> This service is stateless — all state lives
 * in the {@link IConversationCheckpointStore}. Concurrent access is safe
 * because checkpoint creation and retrieval are atomic store operations.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class MemorySnapshotService {

    private static final Logger LOGGER = Logger.getLogger(MemorySnapshotService.class);
    private static final int DEFAULT_MAX_CHECKPOINTS = 10;

    @Inject
    IConversationCheckpointStore checkpointStore;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Create a checkpoint of the current conversation state.
     *
     * @param memory
     *            the live conversation memory
     * @param triggeredBy
     *            human-readable description of what triggered the snapshot
     * @param triggeredByClass
     *            the class name of the component that triggered it
     * @return the created checkpoint
     */
    public MemoryCheckpoint createCheckpoint(IConversationMemory memory, String triggeredBy, String triggeredByClass) {
        String conversationId = memory.getConversationId();
        int stepIndex = memory.size() - 1; // 0-based step index
        Map<String, Object> properties = extractProperties(memory);

        MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                conversationId, stepIndex, properties, triggeredBy, triggeredByClass);

        checkpointStore.create(checkpoint);

        // Auto-prune if we have too many checkpoints
        checkpointStore.pruneOldest(conversationId, DEFAULT_MAX_CHECKPOINTS);

        incrementCounter("create");
        LOGGER.debugf("Created checkpoint '%s' for conversation '%s' at step %d (triggeredBy=%s)",
                checkpoint.checkpointId(), conversationId, stepIndex, triggeredBy);

        return checkpoint;
    }

    /**
     * Restore the conversation memory to a specific checkpoint.
     * <p>
     * <strong>Important:</strong> This restores only the conversation properties
     * captured in the checkpoint. It does not restore the step index or any
     * execution/step-stack state. External side-effects (API calls, tool results
     * already sent) are NOT reversed.
     *
     * @param memory
     *            the live conversation memory to restore
     * @param checkpointId
     *            the checkpoint to restore to
     * @return true if rollback was successful, false if checkpoint not found
     */
    public boolean rollbackToCheckpoint(IConversationMemory memory, String checkpointId) {
        MemoryCheckpoint checkpoint = checkpointStore.findById(checkpointId);
        if (checkpoint == null) {
            LOGGER.warnf("Checkpoint '%s' not found for rollback", checkpointId);
            incrementCounter("rollback_failed");
            return false;
        }

        // Verify this checkpoint belongs to the same conversation
        if (!checkpoint.conversationId().equals(memory.getConversationId())) {
            LOGGER.warnf("Checkpoint '%s' belongs to conversation '%s', not '%s'",
                    checkpointId, checkpoint.conversationId(), memory.getConversationId());
            incrementCounter("rollback_failed");
            return false;
        }

        // Restore properties
        restoreProperties(memory, checkpoint.propertiesCopy());

        incrementCounter("rollback_success");
        LOGGER.infof("Rolled back conversation '%s' to checkpoint '%s' (step %d)",
                memory.getConversationId(), checkpointId, checkpoint.stepIndex());

        return true;
    }

    /**
     * Get all checkpoints for a conversation (newest first).
     */
    public List<MemoryCheckpoint> getCheckpoints(String conversationId) {
        return checkpointStore.findByConversationId(conversationId, DEFAULT_MAX_CHECKPOINTS);
    }

    /**
     * Delete all checkpoints for a conversation (GDPR erasure).
     */
    public long deleteCheckpoints(String conversationId) {
        return checkpointStore.deleteByConversationId(conversationId);
    }

    private Map<String, Object> extractProperties(IConversationMemory memory) {
        var props = memory.getConversationProperties();
        if (props == null) {
            return Map.of();
        }
        // Create a deep copy of the properties map to isolate checkpoint state
        return DeepCopyUtil.deepCopy(props.toMap());
    }

    @SuppressWarnings("unchecked")
    private void restoreProperties(IConversationMemory memory, Map<String, Object> propertiesCopy) {
        var props = memory.getConversationProperties();
        if (props == null) {
            return;
        }
        // Clear current properties and restore from checkpoint
        props.clear();
        propertiesCopy.forEach((key, value) -> {
            Property property;
            if (value instanceof String s) {
                property = new Property(key, s, Property.Scope.conversation);
            } else if (value instanceof Map<?, ?> m) {
                property = new Property(key, (Map<String, Object>) m, Property.Scope.conversation);
            } else if (value instanceof List<?> l) {
                property = new Property(key, (List<Object>) l, Property.Scope.conversation);
            } else if (value instanceof Integer i) {
                property = new Property(key, i, Property.Scope.conversation);
            } else if (value instanceof Float f) {
                property = new Property(key, f, Property.Scope.conversation);
            } else if (value instanceof Boolean b) {
                property = new Property(key, b, Property.Scope.conversation);
            } else {
                // Fallback: convert to string
                property = new Property(key, String.valueOf(value), Property.Scope.conversation);
            }
            props.put(key, property);
        });
    }

    private void incrementCounter(String action) {
        Counter.builder("eddi.session.checkpoint.count")
                .tag("action", action)
                .register(meterRegistry)
                .increment();
    }
}
