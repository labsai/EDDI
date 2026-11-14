/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The turn runner's failure-state writes (the agent-timeout watchdog and the
 * error path) must not resurrect a conversation that was ended while the turn
 * ran: they are compare-and-sets from the state they read, never blind writes.
 */
@DisplayName("ConversationStepRunner — failure states never overwrite ENDED")
class ConversationStepRunnerEndedStateTest {

    private static final String ID = "6ac11c7a3dc075f0b3dbb8ec";

    private IConversationMemoryStore store;
    private ICache<String, ConversationState> stateCache;
    private ConversationStepRunner runner;
    private ConversationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        service = mock(ConversationService.class);
        Field logger = ConversationService.class.getDeclaredField("contextLogger");
        logger.setAccessible(true);
        logger.set(service, mock(IContextLogger.class));
        store = mock(IConversationMemoryStore.class);
        stateCache = mock(ICache.class);
        // agentTimeout 0: the watchdog gives up on a turn that has not finished at
        // once.
        runner = new ConversationStepRunner(service, store, mock(IConversationDescriptorStore.class), mock(IRuntime.class), stateCache,
                new ConcurrentHashMap<>(), 0);
    }

    @Test
    @DisplayName("the watchdog leaves a conversation ended while the turn hung ENDED")
    void watchdogKeepsEnded() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.ENDED);
        IConversationMemory memory = mock(IConversationMemory.class);

        assertTrue(runner.waitForExecutionFinishOrTimeout(Map.of(), ID, memory, new CompletableFuture<>()));

        verify(memory).setCancelled(true);
        verify(store, never()).setConversationState(anyString(), any());
        verify(store, never()).compareAndSetState(anyString(), any(), any());
        verify(stateCache).put(ID, ConversationState.ENDED);
    }

    @Test
    @DisplayName("the watchdog records EXECUTION_INTERRUPTED by compare-and-set from the state it read")
    void watchdogUsesCompareAndSet() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.READY);
        when(store.compareAndSetState(ID, ConversationState.READY, ConversationState.EXECUTION_INTERRUPTED)).thenReturn(true);

        runner.waitForExecutionFinishOrTimeout(Map.of(), ID, mock(IConversationMemory.class), new CompletableFuture<>());

        verify(store).compareAndSetState(ID, ConversationState.READY, ConversationState.EXECUTION_INTERRUPTED);
        verify(store, never()).setConversationState(anyString(), any());
        verify(stateCache).put(ID, ConversationState.EXECUTION_INTERRUPTED);
    }

    @Test
    @DisplayName("a failed turn does not turn an ENDED conversation into ERROR")
    void errorKeepsEnded() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.ENDED);

        runner.logConversationError(Map.of(), ID, new IllegalStateException("boom"));

        verify(store, never()).setConversationState(anyString(), any());
        verify(store, never()).compareAndSetState(anyString(), any(), any());
    }

    @Test
    @DisplayName("a failed turn records ERROR by compare-and-set; a lost race keeps the store's state")
    void errorUsesCompareAndSet() throws Exception {
        when(store.getConversationState(ID)).thenReturn(ConversationState.READY, ConversationState.ENDED);
        when(store.compareAndSetState(ID, ConversationState.READY, ConversationState.ERROR)).thenReturn(false);

        runner.logConversationError(Map.of(), ID, new IllegalStateException("boom"));

        verify(store).compareAndSetState(eq(ID), eq(ConversationState.READY), eq(ConversationState.ERROR));
        verify(store, never()).setConversationState(anyString(), any());
        verify(stateCache).put(ID, ConversationState.ENDED);
    }

    @Test
    @DisplayName("a cancelled turn of an ended conversation tears down its sub-agents from the live memory; a plain cancel does not")
    void cancelledTurnOfEndedConversationTearsDownSubAgents() {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(store.getConversationState(ID)).thenReturn(ConversationState.ENDED);

        runner.tearDownSubAgentsIfEnded(ID, memory);
        verify(service).tearDownStandaloneSubAgents(ID, memory);

        IConversationMemory other = mock(IConversationMemory.class);
        when(store.getConversationState("other")).thenReturn(ConversationState.EXECUTION_INTERRUPTED);
        runner.tearDownSubAgentsIfEnded("other", other);
        verify(service, never()).tearDownStandaloneSubAgents("other", other);
    }

    @Test
    @DisplayName("a turn settling while the end is under way, before ENDED is stored, still tears down its sub-agents")
    void endUnderWayBeforeEndedIsStored() {
        IConversationMemory memory = mock(IConversationMemory.class);
        // The end has signalled the cancel but not stored ENDED yet.
        when(service.isEnding(ID)).thenReturn(true);
        when(store.getConversationState(ID)).thenReturn(ConversationState.EXECUTION_INTERRUPTED);

        runner.tearDownSubAgentsIfEnded(ID, memory);

        verify(service).tearDownStandaloneSubAgents(ID, memory);
    }
}
