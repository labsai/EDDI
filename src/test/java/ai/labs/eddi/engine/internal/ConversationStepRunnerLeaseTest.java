/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a turn that runs under a cluster lease is wired: its memory carries the
 * lease's fencing token (so the stores refuse a stale write), the fence is
 * raised when the turn starts, and losing the lease cancels it. No other test
 * drives {@code runConversationStep} with a lease; the stores' own tests only
 * prove that a token, once set, is enforced.
 */
@DisplayName("ConversationStepRunner under a cluster lease")
class ConversationStepRunnerLeaseTest {

    private static final String ID = "6ac11c7a3dc075f0b3dbb8eb";

    /** A lease whose lost-callbacks the test can fire. */
    private static final class TestLease implements LeaseHandle {
        private final Long fence;
        final List<Runnable> lostCallbacks = new CopyOnWriteArrayList<>();

        TestLease(Long fence) {
            this.fence = fence;
        }

        @Override
        public String key() {
            return "c." + ID;
        }

        @Override
        public String conversationId() {
            return ID;
        }

        @Override
        public Long fence() {
            return fence;
        }

        @Override
        public boolean isLost() {
            return false;
        }

        @Override
        public void onLost(Runnable callback) {
            lostCallbacks.add(callback);
        }
    }

    private IConversationMemoryStore store;
    private IConversationMemory memory;
    private IRuntime runtime;
    private ConversationStepRunner runner;

    private static void setFinal(Object target, String name, Object value) throws Exception {
        Field field = ConversationService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        ConversationService service = mock(ConversationService.class);
        setFinal(service, "conversationHitlService", mock(ConversationHitlService.class));
        setFinal(service, "contextLogger", mock(IContextLogger.class));
        store = mock(IConversationMemoryStore.class);
        when(store.getConversationState(ID)).thenReturn(ConversationState.READY);
        memory = mock(IConversationMemory.class);
        @SuppressWarnings("unchecked")
        ICache<String, ConversationState> stateCache = mock(ICache.class);
        runtime = mock(IRuntime.class);
        runner = new ConversationStepRunner(service, store, mock(IConversationDescriptorStore.class), runtime, stateCache,
                new ConcurrentHashMap<>(), 60);
    }

    private void runTurn(LeaseHandle lease) {
        runner.runConversationStep(Environment.production, memory, ID, Map.of(), () -> null, null, m -> {
        }, lease);
    }

    @Test
    @DisplayName("the turn's memory carries the lease's fencing token and the fence is raised")
    void turnCarriesTheToken() throws Exception {
        runTurn(new TestLease(42L));
        verify(memory).setFenceToken(42L);
        verify(store).raiseFence(ID, 42L);
    }

    @Test
    @DisplayName("losing the lease cancels the running turn")
    void lostLeaseCancelsTheTurn() {
        TestLease lease = new TestLease(42L);
        runTurn(lease);
        assertEquals(1, lease.lostCallbacks.size());
        lease.lostCallbacks.forEach(Runnable::run);
        verify(memory).setCancelled(true);
    }

    @SuppressWarnings("unchecked")
    private IRuntime.IFinishedExecution<Void> completion() {
        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> captor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(any(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("a turn cancelled because its lease was lost does not stamp a state on the conversation")
    void lostLeaseTurnLeavesTheStateAlone() throws Exception {
        TestLease lease = new TestLease(42L);
        runTurn(lease);
        lease.lostCallbacks.forEach(Runnable::run);
        when(memory.isCancelled()).thenReturn(true);
        completion().onComplete(null);
        verify(store, never()).compareAndSetState(anyString(), any(), any());
    }

    @Test
    @DisplayName("a turn cancelled by an operator still marks the conversation interrupted")
    void operatorCancelStillInterrupts() throws Exception {
        runTurn(new TestLease(42L));
        when(memory.isCancelled()).thenReturn(true);
        completion().onComplete(null);
        verify(store).compareAndSetState(ID, ConversationState.READY, ConversationState.EXECUTION_INTERRUPTED);
    }

    @Test
    @DisplayName("a turn that cannot raise the fence still runs, fenced by its token")
    void raiseFailureDoesNotStopTheTurn() throws Exception {
        doThrow(new IllegalStateException("db down")).when(store).raiseFence(anyString(), anyLong());
        runTurn(new TestLease(42L));
        verify(memory).setFenceToken(42L);
    }

    @Test
    @DisplayName("without a lease (single node) nothing is fenced and the store is not touched")
    void noLeaseNoFence() throws Exception {
        runTurn(null);
        verify(memory, never()).setFenceToken(any());
        verify(store, never()).raiseFence(anyString(), anyLong());
    }

    @Test
    @DisplayName("an unfenced lease (degraded mode) is not fenced either")
    void unfencedLeaseNoFence() throws Exception {
        runTurn(new TestLease(null));
        verify(memory, never()).setFenceToken(any());
        verify(store, never()).raiseFence(anyString(), anyLong());
    }

    @Test
    @DisplayName("a dead letter of a secretInput turn records neither the input nor its context, and is not replayable")
    void secretInputNeverReachesADeadLetter() throws Exception {
        ConversationService service = mock(ConversationService.class, CALLS_REAL_METHODS);
        setFinal(service, "captureDeadLetterInput", true);
        IConversationMemory turnMemory = mock(IConversationMemory.class);
        when(turnMemory.getAgentId()).thenReturn("agent1");

        var secret = new InputData("my-password", Map.of("secretInput", new Context(Context.ContextType.string, "true")));
        Map<String, Object> turn = service.describeTurn(Environment.production, turnMemory, ID, secret, false);
        assertFalse(turn.containsKey("input"), "the secret must not be kept");
        assertFalse(turn.containsKey("context"));
        assertEquals(true, turn.get("secretInput"));
        assertFalse(new DeadLetterEntry("1", ID, "e", 1, "{}", turn).isReplayable());

        var plain = new InputData("hello", Map.of("lang", new Context(Context.ContextType.string, "en")));
        Map<String, Object> plainTurn = service.describeTurn(Environment.production, turnMemory, ID, plain, false);
        assertEquals("hello", plainTurn.get("input"), "an ordinary turn keeps its input for replay");
        assertTrue(new DeadLetterEntry("2", ID, "e", 1, "{}", plainTurn).isReplayable());
    }
}
