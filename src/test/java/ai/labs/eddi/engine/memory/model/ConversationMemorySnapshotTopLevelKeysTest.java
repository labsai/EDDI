/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link ConversationMemorySnapshot#TOP_LEVEL_KEYS}, which the
 * append-style write uses to {@code $unset} the keys a turn stopped emitting.
 * <p>
 * The serialization omits nulls, and {@code $set} merges where
 * {@code replaceOne} replaces — so a key missing from this set is a key that
 * would keep a stale value on disk forever after the turn cleared it. That is
 * silent data corruption of exactly the kind this branch exists to remove, and
 * it would appear the moment somebody adds a field whose JSON name the
 * derivation does not predict. Hence these tests rather than a comment.
 */
@DisplayName("ConversationMemorySnapshot top-level keys")
class ConversationMemorySnapshotTopLevelKeysTest {

    @Test
    @DisplayName("every @JsonProperty rename on the class is covered by TOP_LEVEL_KEYS")
    void everyRenameIsCovered() {
        for (Method method : ConversationMemorySnapshot.class.getDeclaredMethods()) {
            JsonProperty annotation = method.getAnnotation(JsonProperty.class);
            if (annotation == null || annotation.value().isEmpty()) {
                continue;
            }
            assertTrue(ConversationMemorySnapshot.TOP_LEVEL_KEYS.contains(annotation.value()),
                    () -> "TOP_LEVEL_KEYS is derived from the field names, so a @JsonProperty rename has to be mapped "
                            + "explicitly in computeTopLevelKeys(). Missing: '" + annotation.value() + "' (from "
                            + method.getName() + "). Without it, an append-style write can never clear that field.");
        }
    }

    @Test
    @DisplayName("TOP_LEVEL_KEYS has one entry per persisted instance field")
    void oneKeyPerPersistedField() {
        long persistedFields = Arrays.stream(ConversationMemorySnapshot.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !Modifier.isTransient(field.getModifiers()))
                .count();
        assertEquals(persistedFields, ConversationMemorySnapshot.TOP_LEVEL_KEYS.size(),
                "a renamed key must replace its field's key, not be added beside it");
    }

    @Test
    @DisplayName("a fully populated snapshot emits no key outside TOP_LEVEL_KEYS")
    void populatedSnapshotEmitsOnlyKnownKeys() throws Exception {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SerializationCustomizer.configureObjectMapper(mapper, false);

        var json = mapper.readTree(mapper.writeValueAsString(populatedSnapshot()));
        Set<String> emitted = new TreeSet<>();
        json.fieldNames().forEachRemaining(emitted::add);

        Set<String> unknown = new TreeSet<>(emitted);
        unknown.removeAll(ConversationMemorySnapshot.TOP_LEVEL_KEYS);
        assertTrue(unknown.isEmpty(),
                () -> "these emitted keys are not in TOP_LEVEL_KEYS, so an append-style write could never clear them: " + unknown);
        // Sanity: the probe must actually be populated, or the assertion above is
        // vacuous.
        assertTrue(emitted.contains("_id") && emitted.contains("_rev") && emitted.contains("hitlPauseReason"),
                () -> "the probe snapshot did not emit the fields it sets: " + emitted);
    }

    /**
     * Every nullable top-level field set, so the serialization emits as many keys
     * as it can.
     */
    private static ConversationMemorySnapshot populatedSnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setId("aabbccddeeff112233445566");
        snapshot.setRevision(3L);
        snapshot.setSchemaVersion(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION);
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setUserId("user-1");
        snapshot.setResolutionProvenance(ResolutionPrincipal.Provenance.VERIFIED);
        snapshot.setEnvironment(Deployment.Environment.test);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setHitlPausedWorkflowId("workflow-1");
        snapshot.setHitlPausedAbsoluteTaskIndex(2);
        snapshot.setHitlPausedAt(Instant.EPOCH);
        snapshot.setHitlPauseReason("needs review");
        snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        snapshot.setHitlApprovalTimeout("PT30M");
        snapshot.setHitlPauseType("TOOL_CALL");
        snapshot.setHitlPendingToolCalls(new PendingToolCallBatch());
        snapshot.getConversationOutputs().add(new ConversationOutput());
        snapshot.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
        snapshot.getPendingLongTermWrites().add("some-key");
        return snapshot;
    }
}
