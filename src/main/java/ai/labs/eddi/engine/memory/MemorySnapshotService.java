/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.model.Context;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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

    /**
     * Retention used when the caller does not supply one. Mirrors the default of
     * {@code AgentConfiguration.SessionManagement#maxCheckpointsPerConversation};
     * callers that have the agent configuration at hand should pass it explicitly
     * via {@link #createCheckpoint(IConversationMemory, String, String, int)}.
     * <p>
     * <strong>Today this is what every auto-checkpoint uses.</strong> The only
     * production caller, {@code ToolLoopRunner#executeSingleToolCallResult}, has
     * the conversation memory but not the agent configuration, and
     * {@code SessionManagement} has no slot on {@link IConversationMemory} — so the
     * agent-level {@code maxCheckpointsPerConversation} is NOT honoured at runtime
     * yet. Wiring it requires carrying {@code SessionManagement} onto the memory
     * (as {@code UserMemoryConfig} and {@code MemoryPolicy} already are) and
     * passing it at the call site.
     */
    static final int DEFAULT_MAX_CHECKPOINTS = 10;

    @Inject
    IConversationCheckpointStore checkpointStore;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Create a checkpoint of the current conversation state, retaining
     * {@link #DEFAULT_MAX_CHECKPOINTS} checkpoints per conversation.
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
        return createCheckpoint(memory, triggeredBy, triggeredByClass, DEFAULT_MAX_CHECKPOINTS);
    }

    /**
     * Create a checkpoint of the current conversation state with an explicit
     * retention.
     *
     * @param maxCheckpoints
     *            how many checkpoints to retain for this conversation — pass
     *            {@code AgentConfiguration.SessionManagement#getMaxCheckpointsPerConversation()}
     *            so the agent-level setting is honoured. Values {@code <= 0} fall
     *            back to {@link #DEFAULT_MAX_CHECKPOINTS} rather than pruning
     *            everything.
     * @return the created checkpoint
     */
    public MemoryCheckpoint createCheckpoint(IConversationMemory memory, String triggeredBy, String triggeredByClass, int maxCheckpoints) {
        String conversationId = memory.getConversationId();
        int stepIndex = memory.size() - 1; // 0-based step index
        Map<String, Property> properties = withoutSecretContextValues(memory, extractProperties(memory));

        MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                conversationId, stepIndex, properties, triggeredBy, triggeredByClass);

        checkpointStore.create(checkpoint);

        // Auto-prune if we have too many checkpoints
        checkpointStore.pruneOldest(conversationId, effectiveRetention(maxCheckpoints));

        incrementCounter("create");
        LOGGER.debugf("Created checkpoint '%s' for conversation '%s' at step %d (triggeredBy=%s)",
                checkpoint.checkpointId(), sanitize(conversationId), stepIndex, sanitize(triggeredBy));

        return checkpoint;
    }

    /**
     * Restore the conversation memory to a specific checkpoint.
     * <p>
     * <strong>Important — this is NOT a session-fork / time-travel
     * primitive.</strong> It restores <em>only</em> the conversation properties
     * captured in the checkpoint. It does NOT restore:
     * <ul>
     * <li>conversation steps or their step data,</li>
     * <li>conversation outputs (what the user was shown),</li>
     * <li>the step index or any execution/step-stack state,</li>
     * <li>external side-effects — API calls made and tool results already sent are
     * NOT reversed.</li>
     * </ul>
     * Callers that need a true fork must snapshot the full
     * {@code ConversationMemorySnapshot}, not a {@link MemoryCheckpoint}.
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
     * Get the retained checkpoints for a conversation (newest first), bounded by
     * {@link #DEFAULT_MAX_CHECKPOINTS}.
     */
    public List<MemoryCheckpoint> getCheckpoints(String conversationId) {
        return getCheckpoints(conversationId, DEFAULT_MAX_CHECKPOINTS);
    }

    /**
     * Get the retained checkpoints for a conversation (newest first), bounded by an
     * explicit limit. Values {@code <= 0} fall back to
     * {@link #DEFAULT_MAX_CHECKPOINTS}.
     */
    public List<MemoryCheckpoint> getCheckpoints(String conversationId, int maxCheckpoints) {
        return checkpointStore.findByConversationId(conversationId, effectiveRetention(maxCheckpoints));
    }

    private static int effectiveRetention(int maxCheckpoints) {
        return maxCheckpoints > 0 ? maxCheckpoints : DEFAULT_MAX_CHECKPOINTS;
    }

    /**
     * Delete all checkpoints for a conversation (GDPR erasure).
     */
    public long deleteCheckpoints(String conversationId) {
        return checkpointStore.deleteByConversationId(conversationId);
    }

    /**
     * Context data keys of the current step: {@code context:<name>}.
     */
    private static final String CONTEXT_DATA_PREFIX = "context:";

    /**
     * Secret context values are searched for inside other values from this length
     * on, and replaced where a value equals them from
     * {@link SecretValueScrubber#MIN_SEARCHED_SECRET_INPUT_LENGTH} — the floors the
     * turn-end scrub in {@code Conversation} applies, so a checkpoint keeps exactly
     * what the stored turn will keep.
     */
    static final int MIN_SEARCHED_SECRET_CONTEXT_LENGTH = 8;

    /**
     * The properties with every value the client marked {@code "secret": true} in
     * this turn's context removed. The turn scrubs them from its properties when it
     * ends, but a checkpoint is written mid-turn — before every tool call — so a
     * property instruction that copied a secret context value into an ordinary
     * property persisted it in plaintext in {@code conversation_checkpoints}.
     * Properties that carry none are passed through as they are.
     */
    static Map<String, Property> withoutSecretContextValues(IConversationMemory memory, Map<String, Property> properties) {
        if (properties.isEmpty() || memory.getCurrentStep() == null) {
            return properties;
        }
        Set<String> plaintexts = new LinkedHashSet<>();
        Set<String> wholeStrings = new LinkedHashSet<>();
        for (IData<?> datum : memory.getCurrentStep().getAllElements()) {
            if (datum != null && datum.getKey() != null && datum.getKey().startsWith(CONTEXT_DATA_PREFIX)
                    && datum.getResult() instanceof Context context && Boolean.TRUE.equals(context.getSecret())) {
                SecretValueScrubber.collectPlaintexts(context.getValue(), plaintexts);
                if (context.getValue() instanceof String text) {
                    wholeStrings.add(text);
                }
            }
        }
        List<String> needles = plaintexts.stream().filter(value -> value.length() >= MIN_SEARCHED_SECRET_CONTEXT_LENGTH).toList();
        List<String> exact = wholeStrings.stream()
                .filter(value -> value.length() >= SecretValueScrubber.MIN_SEARCHED_SECRET_INPUT_LENGTH
                        && value.length() < MIN_SEARCHED_SECRET_CONTEXT_LENGTH)
                .filter(value -> !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                .toList();
        if (needles.isEmpty() && exact.isEmpty()) {
            return properties;
        }
        Map<String, Property> redacted = new LinkedHashMap<>();
        properties.forEach((name, property) -> redacted.put(name, redact(property, needles, exact)));
        return redacted;
    }

    private static Property redact(Property property, List<String> needles, List<String> exact) {
        if (property == null) {
            return null;
        }
        try {
            Property cleaned = needles.isEmpty()
                    ? null
                    : SecretValueScrubber.scrubTyped(property, Property.class, needles, MemoryKeys.SECRET_CONTEXT_PLACEHOLDER);
            Property current = cleaned != null ? cleaned : property;
            Property exactCleaned = exact.isEmpty()
                    ? null
                    : SecretValueScrubber.scrubTypedExact(current, Property.class, exact, MemoryKeys.SECRET_CONTEXT_PLACEHOLDER);
            return exactCleaned != null ? exactCleaned : current;
        } catch (IllegalArgumentException e) {
            // Could not be read back after scrubbing: checkpoint the placeholder rather
            // than the value, which may be the secret.
            LOGGER.warnf("Property '%s' could not be scrubbed for the checkpoint; checkpointing a placeholder", sanitize(property.getName()));
            return new Property(property.getName(), MemoryKeys.SECRET_CONTEXT_PLACEHOLDER, property.getScope());
        }
    }

    private Map<String, Property> extractProperties(IConversationMemory memory) {
        var props = memory.getConversationProperties();
        if (props == null || props.isEmpty()) {
            return Map.of();
        }
        // Shallow copy — MemoryCheckpoint.create() deep-copies each Property
        return new LinkedHashMap<>(props);
    }

    private void restoreProperties(IConversationMemory memory, Map<String, Property> propertiesCopy) {
        var props = memory.getConversationProperties();
        if (props == null) {
            return;
        }
        // Clear current properties and restore from checkpoint.
        // Property objects in the checkpoint already have their original scope
        // and visibility preserved — no reconstruction needed.
        props.clear();
        propertiesCopy.forEach(props::put);
    }

    private void incrementCounter(String action) {
        Counter.builder("eddi.session.checkpoint.count")
                .tag("action", action)
                .register(meterRegistry)
                .increment();
    }
}
