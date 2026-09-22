/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Undo used to pop the step and nothing else, so a slot filled by the undone
 * turn stayed filled (reproduced live: after undo, both the conversation-scoped
 * and the longTerm property still held the undone value). Each turn now records
 * its property changes, which undo reverts and redo re-applies.
 */
class ConversationMemoryUndoPropertiesTest {

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
    }

    /** One turn: start a step, let {@code changes} run, record the delta. */
    private void turn(Runnable changes) {
        memory.startNextStep();
        var before = memory.serializedProperties();
        changes.run();
        memory.recordPropertyChanges(before);
    }

    private String value(String key) {
        Property property = memory.getConversationProperties().get(key);
        return property == null ? null : property.getValueString();
    }

    @Test
    @DisplayName("undo restores the value a property had before the undone turn; redo re-applies it")
    void undoRestoresAndRedoReapplies() {
        turn(() -> memory.getConversationProperties().put("agentName", new Property("agentName", "Alpha", Scope.conversation)));
        turn(() -> memory.getConversationProperties().put("agentName", new Property("agentName", "Beta", Scope.conversation)));

        memory.undoLastStep();
        assertEquals("Alpha", value("agentName"));

        memory.redoLastStep();
        assertEquals("Beta", value("agentName"));
    }

    @Test
    @DisplayName("a property the undone turn created is removed; one it removed comes back")
    void createdAndRemovedProperties() {
        turn(() -> memory.getConversationProperties().put("keep", new Property("keep", "x", Scope.conversation)));
        turn(() -> {
            memory.getConversationProperties().put("created", new Property("created", "new", Scope.longTerm));
            memory.getConversationProperties().remove("keep");
        });

        memory.undoLastStep();

        assertNull(value("created"));
        assertEquals("x", value("keep"));
        assertEquals(Scope.conversation, memory.getConversationProperties().get("keep").getScope());
    }

    @Test
    @DisplayName("an in-place mutation counts as a change — the baseline is serialized, not referenced")
    void inPlaceMutationIsRecorded() {
        turn(() -> memory.getConversationProperties().put("color", new Property("color", "teal", Scope.longTerm)));
        turn(() -> memory.getConversationProperties().get("color").setValueString("red"));

        memory.undoLastStep();

        assertEquals("teal", value("color"));
    }

    @Test
    @DisplayName("a turn that changed nothing records nothing")
    void unchangedTurnRecordsNothing() {
        turn(() -> memory.getConversationProperties().put("a", new Property("a", "1", Scope.conversation)));
        turn(() -> {
        });

        assertTrue(ConversationMemory.propertyChanges(memory.getCurrentStep()).isEmpty());
    }

    @Test
    @DisplayName("a new turn after undo discards the redo history — it belongs to an abandoned timeline")
    void newTurnClearsTheRedoCache() {
        turn(() -> memory.getConversationProperties().put("v", new Property("v", "1", Scope.conversation)));
        turn(() -> memory.getConversationProperties().put("v", new Property("v", "2", Scope.conversation)));
        memory.undoLastStep();
        assertTrue(memory.isRedoAvailable());

        turn(() -> memory.getConversationProperties().put("v", new Property("v", "3", Scope.conversation)));

        assertFalse(memory.isRedoAvailable(), "redo must not graft the undone step onto the new timeline");
        assertEquals("3", value("v"));
    }

    @Test
    @DisplayName("the recorded changes survive the snapshot round trip the store performs between requests")
    void survivesSnapshotRoundTrip() {
        turn(() -> memory.getConversationProperties().put("agentName", new Property("agentName", "Alpha", Scope.conversation)));
        turn(() -> memory.getConversationProperties().put("agentName", new Property("agentName", "Beta", Scope.conversation)));

        var reloaded = (ConversationMemory) ConversationMemoryUtilities
                .convertConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));
        reloaded.undoLastStep();

        assertEquals("Alpha", reloaded.getConversationProperties().get("agentName").getValueString());
    }
}
