/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * G9 — {@code toMap()} must reflect EVERY {@link Map} mutation, not just
 * {@code put}/{@code putAll}; G10 — {@code scope: step} values must stay out of
 * the persisted projection (step data + conversation output).
 */
class ConversationPropertiesMapContractTest {

    private IConversationMemory.IWritableConversationStep currentStep;
    private ConversationProperties properties;

    @BeforeEach
    void setUp() {
        IConversationMemory memory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        properties = new ConversationProperties(memory);
    }

    @Test
    @DisplayName("G9 — clear() removes the values from the template view too")
    void clearIsReflectedInToMap() {
        properties.put("name", new Property("name", "Alice", Property.Scope.conversation));
        assertEquals("Alice", properties.toMap().get("name"));

        properties.clear();

        assertTrue(properties.toMap().isEmpty(), "clear() must not leave stale values visible via {properties.x}");
    }

    @Test
    @DisplayName("G9 — remove() removes the value from the template view too")
    void removeIsReflectedInToMap() {
        properties.put("name", new Property("name", "Alice", Property.Scope.conversation));
        properties.put("city", new Property("city", "Berlin", Property.Scope.conversation));

        properties.remove("name");

        assertNull(properties.toMap().get("name"), "remove() must not leave a stale value visible via {properties.x}");
        assertEquals("Berlin", properties.toMap().get("city"));
    }

    @Test
    @DisplayName("G9 — entrySet().removeIf is reflected in the template view too")
    void entrySetRemoveIfIsReflectedInToMap() {
        properties.put("keep", new Property("keep", "yes", Property.Scope.conversation));
        properties.put("drop", new Property("drop", "no", Property.Scope.step));

        properties.entrySet().removeIf(entry -> entry.getValue().getScope() == Property.Scope.step);

        assertEquals(Map.of("keep", "yes"), properties.toMap());
    }

    @Test
    @DisplayName("G9 — a checkpoint-style clear-then-restore drops post-checkpoint properties")
    void clearThenRestoreDropsPostCheckpointProperties() {
        var atCheckpoint = Map.of("name", new Property("name", "Alice", Property.Scope.conversation));
        properties.putAll(atCheckpoint);
        properties.put("addedLater", new Property("addedLater", "leaked", Property.Scope.conversation));

        // exactly what MemorySnapshotService.restoreProperties does
        properties.clear();
        atCheckpoint.forEach(properties::put);

        assertNull(properties.toMap().get("addedLater"), "rollback must remove post-checkpoint properties from templates");
        assertEquals("Alice", properties.toMap().get("name"));
    }

    @Test
    @DisplayName("G10 — a step-scoped property skips the persisted step data but IS mirrored for this turn's templates")
    void stepScopedPropertyIsMirroredButNotStoredAsStepData() {
        properties.put("transient", new Property("transient", "temp", Property.Scope.step));

        // the persisted step Data<> is what leaked into later turns — skip it
        verify(currentStep, never()).storeData(any(IData.class));

        // ...but {memory.current.properties.transient} must resolve DURING this turn
        // (Conversation#removeOldInvalidProperties strips it again at the end of it)
        ArgumentCaptor<Map<String, Object>> mirrored = ArgumentCaptor.forClass(Map.class);
        verify(currentStep).addConversationOutputMap(eq("properties"), mirrored.capture());
        assertEquals("temp", mirrored.getValue().get("transient"));

        assertEquals("temp", properties.toMap().get("transient"));
    }

    @Test
    @DisplayName("G10 — a conversation-scoped property is still mirrored into the persisted projection")
    void conversationScopedPropertyIsStillPersisted() {
        properties.put("name", new Property("name", "Alice", Property.Scope.conversation));

        verify(currentStep).storeData(any(IData.class));
        verify(currentStep).addConversationOutputMap(eq("properties"), anyMap());
    }

    /**
     * {@link Property} is deserialized from stored configuration and may legally
     * carry a null name. {@code toMap()} has always fallen back to the map key; the
     * mirror into the conversation output did not, so the two views of one property
     * disagreed — the template resolved the value while the client-visible output
     * carried a {@code null} key for the same entry.
     */
    @Test
    @DisplayName("a property with no name is mirrored under the map key, matching toMap()")
    void namelessPropertyIsMirroredUnderTheMapKey() {
        properties.put("nickname", new Property(null, "Ali", Property.Scope.conversation));

        ArgumentCaptor<Map<String, Object>> mirrored = ArgumentCaptor.forClass(Map.class);
        verify(currentStep).addConversationOutputMap(eq("properties"), mirrored.capture());

        assertEquals("Ali", mirrored.getValue().get("nickname"), "the mirror must key on the map key when the property has no name");
        assertFalse(mirrored.getValue().containsKey(null), "a null key is unusable to a client and must never be emitted");
        // The two views of the same property must agree.
        assertEquals(properties.toMap().get("nickname"), mirrored.getValue().get("nickname"));
    }

    @Test
    @DisplayName("an explicit property name still wins over the map key")
    void explicitNameStillWins() {
        properties.put("mapKey", new Property("realName", "Alice", Property.Scope.conversation));

        ArgumentCaptor<Map<String, Object>> mirrored = ArgumentCaptor.forClass(Map.class);
        verify(currentStep).addConversationOutputMap(eq("properties"), mirrored.capture());

        assertEquals("Alice", mirrored.getValue().get("realName"));
        assertFalse(mirrored.getValue().containsKey("mapKey"), "the fallback must not override an explicit name");
    }
}
