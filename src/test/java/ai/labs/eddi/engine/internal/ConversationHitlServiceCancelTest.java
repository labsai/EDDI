/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The paused-only cancel an approver gets (L9 of the 2026-10-15 security
 * review): one compare-and-set on {@code AWAITING_HUMAN}, and nothing else.
 */
@SuppressWarnings("unchecked")
class ConversationHitlServiceCancelTest {

    private static final String ID = "conv-1";

    private ConversationService conversationService;
    private IConversationMemoryStore store;
    private ConcurrentHashMap<String, IConversationMemory> inFlight;
    private ConversationHitlService service;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        store = mock(IConversationMemoryStore.class);
        inFlight = new ConcurrentHashMap<>();
        service = new ConversationHitlService(conversationService, store, mock(IConversationCoordinator.class), mock(IRuntime.class),
                mock(IContextLogger.class), new CallerIdentityContext(null, null), mock(AuditLedgerService.class),
                mock(IScheduleStore.class), mock(IAgentStore.class), mock(IJsonSerialization.class), mock(Event.class),
                mock(Counter.class), mock(Counter.class), new SimpleMeterRegistry(), inFlight);
    }

    @Test
    @DisplayName("a paused conversation is cancelled through the AWAITING_HUMAN CAS")
    void cancelsAPause() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.AWAITING_HUMAN);
        when(store.compareAndSetState(ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED)).thenReturn(true);

        assertEquals(IConversationService.CancelOutcome.CANCELLED, service.cancelPausedConversation(ID, "approver"));

        verify(store).compareAndSetState(ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
        verify(store, never()).compareAndSetState(eq(ID), eq(ConversationState.IN_PROGRESS), any());
    }

    @Test
    @DisplayName("an executing turn is neither signalled nor interrupted — the race the state read alone left open")
    void neverTouchesAnExecutingTurn() throws Exception {
        // The state read says paused, but by the time the CAS runs the owner has
        // resumed and the turn is executing on this node.
        when(store.getConversationState(ID)).thenReturn(ConversationState.AWAITING_HUMAN);
        when(store.compareAndSetState(eq(ID), any(), any())).thenReturn(false);
        IConversationMemory running = mock(IConversationMemory.class);
        inFlight.put(ID, running);

        assertEquals(IConversationService.CancelOutcome.NOTHING_TO_CANCEL, service.cancelPausedConversation(ID, "approver"));

        verify(running, never()).setCancelled(true);
        verify(conversationService, never()).signalRemoteInFlight(anyString());
        verify(store, never()).compareAndSetState(eq(ID), eq(ConversationState.IN_PROGRESS), any());
    }

    @Test
    @DisplayName("the full cancel still signals an executing turn — the owner's and admin's path is unchanged")
    void fullCancelSignalsAnExecutingTurn() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.IN_PROGRESS);
        when(store.compareAndSetState(eq(ID), any(), any())).thenReturn(false);
        IConversationMemory running = mock(IConversationMemory.class);
        inFlight.put(ID, running);

        assertEquals(IConversationService.CancelOutcome.CANCELLED,
                service.cancelConversation(ID, ControlSignal.CANCEL_GRACEFUL, "owner"));

        verify(running).setCancelled(true);
    }

    @Test
    @DisplayName("cancelling a self-continuing job between two turns withdraws its next turn and reports CANCELLED")
    void cancelBetweenContinuationTurns() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.READY);
        when(store.compareAndSetState(eq(ID), any(), any())).thenReturn(false);
        when(conversationService.disarmContinuations(ID)).thenReturn(1);

        assertEquals(IConversationService.CancelOutcome.CANCELLED,
                service.cancelConversation(ID, ControlSignal.CANCEL_GRACEFUL, "owner"));
        verify(conversationService).disarmContinuations(ID);
    }

    @Test
    @DisplayName("an idle conversation without a pending continuation still has nothing to cancel")
    void idleWithoutContinuation() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.READY);
        when(store.compareAndSetState(eq(ID), any(), any())).thenReturn(false);
        when(conversationService.disarmContinuations(ID)).thenReturn(0);

        assertEquals(IConversationService.CancelOutcome.NOTHING_TO_CANCEL,
                service.cancelConversation(ID, ControlSignal.CANCEL_GRACEFUL, "owner"));
    }

    @Test
    @DisplayName("cancelling a running continuation turn also withdraws the next one")
    void cancelRunningContinuationTurn() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.IN_PROGRESS);
        when(store.compareAndSetState(eq(ID), any(), any())).thenReturn(false);
        inFlight.put(ID, mock(IConversationMemory.class));

        service.cancelConversation(ID, ControlSignal.CANCEL_GRACEFUL, "owner");

        verify(conversationService).disarmContinuations(ID);
    }

    @Test
    @DisplayName("the approver's paused-only cancel never touches a pending continuation")
    void approverNeverWithdrawsContinuations() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.READY);
        when(store.compareAndSetState(eq(ID), any(), any())).thenReturn(false);
        when(conversationService.disarmContinuations(ID)).thenReturn(1);

        assertEquals(IConversationService.CancelOutcome.NOTHING_TO_CANCEL, service.cancelPausedConversation(ID, "approver"));
        verify(conversationService, never()).disarmContinuations(anyString());
    }

    @Test
    @DisplayName("an unknown conversation is NOT_FOUND")
    void unknownIsNotFound() throws Exception {
        when(store.getConversationState(ID)).thenReturn(null);

        assertEquals(IConversationService.CancelOutcome.NOT_FOUND, service.cancelPausedConversation(ID, "approver"));
    }
}
