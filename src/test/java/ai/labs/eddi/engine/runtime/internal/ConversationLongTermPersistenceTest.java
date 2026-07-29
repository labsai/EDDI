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
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * The baseline must model what the USER MEMORY STORE holds, not what the
     * conversation document holds. A HITL pause persists the document (properties
     * included) without ever upserting, so a resume that re-captured the document
     * as its baseline would see "unchanged" and drop the property forever.
     */
    @Test
    @DisplayName("G6 — a longTerm property set in a turn that HITL-pauses is still persisted on resume")
    void longTermPropertySetBeforeAPauseIsPersistedOnResume() throws Exception {
        IExecutableWorkflow workflow = mock(IExecutableWorkflow.class);
        ILifecycleManager lifecycleManager = mock(ILifecycleManager.class);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        // The turn sets the property and THEN trips the human-approval gate.
        doAnswer(invocation -> {
            memory.getConversationProperties().put("dietary_restriction",
                    new Property("dietary_restriction", "vegan", Scope.longTerm));
            throw new ConversationPauseException("wf1", 2, "needs approval");
        }).when(lifecycleManager).executeLifecycle(any(), any());

        turnWith(workflow).say("I am vegan", new LinkedHashMap<>());

        // The pause skips the post-conversation tasks, so nothing reached the store —
        // but the AWAITING_HUMAN snapshot DID carry the property into the document.
        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));

        // Resume: a FRESH Conversation over the hydrated memory, as
        // Agent#continueConversation builds for the resume request.
        HitlDecision decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.REJECTED);
        turnWith(workflow).resume(decision);

        ArgumentCaptor<UserMemoryEntry> entry = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore).upsert(entry.capture());
        assertEquals("dietary_restriction", entry.getValue().key());
        assertEquals("vegan", entry.getValue().value());
    }

    /**
     * Same divergence, reached through a turn that ended in ERROR: the post-tasks
     * never ran, so the restored document is not a persisted baseline either.
     */
    @Test
    @DisplayName("G6 — a longTerm property left over from an ERRORed turn is persisted on the next turn")
    void longTermPropertyFromAnErroredTurnIsPersistedOnTheNextTurn() throws Exception {
        memory.getConversationProperties().put("favorite_color", new Property("favorite_color", "blue", Scope.longTerm));
        memory.setConversationState(ConversationState.ERROR);

        nextTurn().say("hello again", new LinkedHashMap<>());

        ArgumentCaptor<UserMemoryEntry> entry = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore).upsert(entry.capture());
        assertEquals("favorite_color", entry.getValue().key());
        assertEquals("blue", entry.getValue().value());
    }

    private Conversation turnWith(IExecutableWorkflow workflow) {
        return new Conversation(List.of(workflow), memory, propertiesHandler,
                (IConversation.IConversationOutputRenderer) null);
    }

    /**
     * G10 narrowed the step scope too far: the mirror that backs
     * {@code {memory.current.properties.X}} was suppressed outright, so a
     * step-scoped property was invisible to templates even during the turn that set
     * it. The documented contract is that step scope lives FOR the turn and is
     * cleared at the END of it — so the mirror is written and then stripped again
     * when the property is dropped, before the step is persisted.
     */
    @Test
    @DisplayName("G10 — a step-scoped property resolves via {memory.current.properties.X} during its turn only")
    void stepScopedPropertyIsMirroredForTheTurnAndUnmirroredAfterIt() throws Exception {
        IExecutableWorkflow workflow = mock(IExecutableWorkflow.class);
        ILifecycleManager lifecycleManager = mock(ILifecycleManager.class);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);

        Map<String, Object> visibleDuringTurn = new LinkedHashMap<>();
        doAnswer(invocation -> {
            memory.getConversationProperties().put("tmp", new Property("tmp", "scratch", Scope.step));
            memory.getConversationProperties().put("keep", new Property("keep", "kept", Scope.conversation));
            visibleDuringTurn.putAll(mirroredProperties());
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());

        turnWith(workflow).say("hi", new LinkedHashMap<>());

        assertEquals("scratch", visibleDuringTurn.get("tmp"),
                "{memory.current.properties.tmp} must resolve during the turn that set it");
        assertEquals("kept", visibleDuringTurn.get("keep"));

        Map<String, Object> afterTurn = mirroredProperties();
        assertNull(afterTurn.get("tmp"), "the step-scoped mirror must not survive into the persisted step");
        assertEquals("kept", afterTurn.get("keep"));
    }

    /** The {@code properties} conversation output of the current step. */
    private Map<String, Object> mirroredProperties() {
        Object mirrored = memory.getCurrentStep().getConversationOutput().get("properties");
        if (mirrored instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        return new LinkedHashMap<>();
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
