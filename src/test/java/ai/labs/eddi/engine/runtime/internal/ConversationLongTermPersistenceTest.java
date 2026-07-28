/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * G6 — {@code storePropertiesPermanently} re-upserted EVERY longTerm property
 * on EVERY turn. Since all recalled entries are tagged {@code longTerm},
 * untouched memories had their {@code updatedAt} refreshed each turn, which
 * degenerated {@code recallOrder: "most_recent"} into "everything is recent"
 * and stopped {@code deleteOlderThan} retention from ever expiring anything for
 * an active user.
 * <p>
 * G10 — a {@code step}-scoped property is dropped at the end of the turn.
 */
class ConversationLongTermPersistenceTest {

    private ConversationMemory memory;
    private IUserMemoryStore userMemoryStore;
    private IPropertiesHandler propertiesHandler;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        userMemoryStore = mock(IUserMemoryStore.class);
        propertiesHandler = mock(IPropertiesHandler.class);
        when(propertiesHandler.getUserMemoryStore()).thenReturn(userMemoryStore);
    }

    /**
     * Mirrors {@code Agent#continueConversation}: a NEW Conversation per turn over
     * an already-hydrated memory.
     */
    private Conversation nextTurn() {
        return new Conversation(List.<IExecutableWorkflow>of(), memory, propertiesHandler,
                (IConversation.IConversationOutputRenderer) null);
    }

    @Test
    @DisplayName("G6 — an untouched recalled memory is not re-upserted, so its updatedAt survives")
    void untouchedLongTermPropertyIsNotRewritten() throws Exception {
        memory.getConversationProperties().put("favorite_color", new Property("favorite_color", "blue", Scope.longTerm));

        nextTurn().say("hello", new LinkedHashMap<>());

        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));
    }

    @Test
    @DisplayName("G6 — a longTerm property whose value CHANGED this turn is upserted")
    void changedLongTermPropertyIsWritten() throws Exception {
        memory.getConversationProperties().put("favorite_color", new Property("favorite_color", "blue", Scope.longTerm));

        Conversation conversation = nextTurn();
        // the turn changes the value
        memory.getConversationProperties().put("favorite_color", new Property("favorite_color", "green", Scope.longTerm));
        conversation.say("make it green", new LinkedHashMap<>());

        ArgumentCaptor<UserMemoryEntry> entry = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore).upsert(entry.capture());
        assertEquals("favorite_color", entry.getValue().key());
        assertEquals("green", entry.getValue().value());
    }

    @Test
    @DisplayName("G6 — a longTerm property created during the turn is upserted")
    void newLongTermPropertyIsWritten() throws Exception {
        Conversation conversation = nextTurn();
        memory.getConversationProperties().put("dietary_restriction", new Property("dietary_restriction", "vegan", Scope.longTerm));

        conversation.say("I am vegan", new LinkedHashMap<>());

        ArgumentCaptor<UserMemoryEntry> entry = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore).upsert(entry.capture());
        assertEquals("dietary_restriction", entry.getValue().key());
    }

    @Test
    @DisplayName("G6 — a second turn that changes nothing writes nothing either")
    void secondTurnWithoutChangesWritesNothing() throws Exception {
        Conversation first = nextTurn();
        memory.getConversationProperties().put("dietary_restriction", new Property("dietary_restriction", "vegan", Scope.longTerm));
        first.say("I am vegan", new LinkedHashMap<>());
        verify(userMemoryStore, times(1)).upsert(any(UserMemoryEntry.class));

        nextTurn().say("thanks", new LinkedHashMap<>());

        verify(userMemoryStore, times(1)).upsert(any(UserMemoryEntry.class));
    }

    @Test
    @DisplayName("G10 — a step-scoped property is dropped at the end of the turn")
    void stepScopedPropertyIsDroppedAfterTheTurn() throws Exception {
        Conversation conversation = nextTurn();
        memory.getConversationProperties().put("temp", new Property("temp", "value", Scope.step));
        memory.getConversationProperties().put("keep", new Property("keep", "value", Scope.conversation));

        conversation.say("hi", new LinkedHashMap<>());

        assertFalse(memory.getConversationProperties().containsKey("temp"));
        assertNull(memory.getConversationProperties().toMap().get("temp"));
        assertTrue(memory.getConversationProperties().containsKey("keep"));
    }
}
