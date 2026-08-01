/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
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

    /** A workflow whose lifecycle does {@code action} and nothing else. */
    private IExecutableWorkflow workflowThat(ThrowingAction action) throws Exception {
        IExecutableWorkflow workflow = mock(IExecutableWorkflow.class);
        ILifecycleManager lifecycleManager = mock(ILifecycleManager.class);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        doAnswer(invocation -> {
            action.run();
            return null;
        }).when(lifecycleManager).executeLifecycle(any(), any());
        return workflow;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private Conversation turnWith(IExecutableWorkflow workflow) {
        return new Conversation(List.of(workflow), memory, propertiesHandler,
                (IConversation.IConversationOutputRenderer) null);
    }

    private static HitlDecision approved() {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        return decision;
    }

    @Test
    @DisplayName("a longTerm property set by a turn that PAUSES for approval is still written on resume")
    void longTermWriteSurvivesAHitlPause() throws Exception {
        IExecutableWorkflow pausing = workflowThat(() -> {
            memory.getConversationProperties().put("dietary_restriction",
                    new Property("dietary_restriction", "vegan", Scope.longTerm));
            throw new ConversationPauseException("wf1", 2, "needs approval");
        });

        turnWith(pausing).say("I am vegan", new LinkedHashMap<>());

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));

        // The human approves. ConversationService builds a NEW Conversation over the
        // memory it reloaded from the conversation document — which already carries
        // the un-persisted property, so a value diff alone can never see it as changed.
        turnWith(pausing).resume(approved());

        ArgumentCaptor<UserMemoryEntry> entry = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore).upsert(entry.capture());
        assertEquals("dietary_restriction", entry.getValue().key());
        assertEquals("vegan", entry.getValue().value());
    }

    /**
     * The baseline must model what the USER MEMORY STORE holds, not what the
     * conversation document holds. A HITL pause persists the document (properties
     * included) without ever upserting, so a resume that re-captured the document
     * as its baseline would see "unchanged" and drop the property forever.
     * <p>
     * Same scenario as {@link #longTermWriteSurvivesAHitlPause()}, but resumed with
     * a REJECTED verdict — the owed write must not depend on the verdict.
     */
    @Test
    @DisplayName("G6 — a longTerm property set in a turn that HITL-pauses is still persisted on resume")
    void longTermPropertySetBeforeAPauseIsPersistedOnResume() throws Exception {
        IExecutableWorkflow workflow = workflowThat(() -> {
            // The turn sets the property and THEN trips the human-approval gate.
            memory.getConversationProperties().put("dietary_restriction",
                    new Property("dietary_restriction", "vegan", Scope.longTerm));
            throw new ConversationPauseException("wf1", 2, "needs approval");
        });

        turnWith(workflow).say("I am vegan", new LinkedHashMap<>());

        // The pause skips the post-conversation tasks, so nothing reached the store —
        // but the AWAITING_HUMAN snapshot DID carry the property into the document.
        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));

        // Resume: a FRESH Conversation over the hydrated memory, as
        // Agent#continueConversation builds for the resume request.
        HitlDecision decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.REJECTED);
        turnWith(workflow).resume(decision);

        ArgumentCaptor<UserMemoryEntry> entry = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore).upsert(entry.capture());
        assertEquals("dietary_restriction", entry.getValue().key());
        assertEquals("vegan", entry.getValue().value());
    }

    @Test
    @DisplayName("a longTerm property set by a turn that ERRORS is still written by the next completed turn")
    void longTermWriteSurvivesAFailedTurn() throws Exception {
        IExecutableWorkflow failing = workflowThat(() -> {
            memory.getConversationProperties().put("allergy", new Property("allergy", "peanuts", Scope.longTerm));
            throw new LifecycleException("task blew up");
        });

        Conversation errored = turnWith(failing);
        assertThrows(LifecycleException.class, () -> errored.say("I am allergic to peanuts", new LinkedHashMap<>()));
        assertEquals(ConversationState.ERROR, memory.getConversationState());
        verify(userMemoryStore, never()).upsert(any(UserMemoryEntry.class));

        nextTurn().say("ok", new LinkedHashMap<>());

        ArgumentCaptor<UserMemoryEntry> entry = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore).upsert(entry.capture());
        assertEquals("allergy", entry.getValue().key());
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

    @Test
    @DisplayName("an owed write that the store rejects is retried by the following turn, not swallowed")
    void failedUpsertIsRetriedOnTheNextTurn() throws Exception {
        doThrow(new ResourceStoreException("mongo down"))
                .when(userMemoryStore).upsert(any(UserMemoryEntry.class));

        Conversation first = nextTurn();
        memory.getConversationProperties().put("home_city", new Property("home_city", "Vienna", Scope.longTerm));
        assertThrows(LifecycleException.class, () -> first.say("I live in Vienna", new LinkedHashMap<>()));

        reset(userMemoryStore);

        nextTurn().say("thanks", new LinkedHashMap<>());

        ArgumentCaptor<UserMemoryEntry> entry = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore).upsert(entry.capture());
        assertEquals("home_city", entry.getValue().key());
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
        Map<String, Object> visibleDuringTurn = new LinkedHashMap<>();
        IExecutableWorkflow workflow = workflowThat(() -> {
            memory.getConversationProperties().put("tmp", new Property("tmp", "scratch", Scope.step));
            memory.getConversationProperties().put("keep", new Property("keep", "kept", Scope.conversation));
            visibleDuringTurn.putAll(mirroredProperties());
        });

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
