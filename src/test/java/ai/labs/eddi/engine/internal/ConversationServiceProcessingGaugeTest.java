/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.AgentNotReadyException;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.ExecutionAbandonedException;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.GracefulShutdownService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C11 — the {@code eddi_processing_conversation_count} gauge, and B3's accept
 * gate on the conversation entry points.
 *
 * <p>
 * The gauge used to be backed by a {@code CopyOnWriteArrayList} of
 * {@code agentId:conversationId} strings. Two defects followed from that: a
 * turn that never reached its completion consumer (watchdog timeout, cancelled
 * inner future) leaked its entry forever, and because the string is IDENTICAL
 * for two concurrent turns on the same conversation, a failing turn deleted a
 * healthy concurrent turn's entry.
 * </p>
 */
class ConversationServiceProcessingGaugeTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "gauge-agent-id";
    private static final String CONVERSATION_ID = "gauge-conversation-id";
    private static final String USER_ID = "gauge-user-id";
    private static final int AGENT_TIMEOUT = 30;
    private static final String GAUGE = "eddi_processing_conversation_count";

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationCoordinator conversationCoordinator;
    private IRuntime runtime;
    private TenantQuotaService tenantQuotaService;
    private SimpleMeterRegistry meterRegistry;

    private ConversationService conversationService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);
        runtime = mock(IRuntime.class);
        tenantQuotaService = mock(TenantQuotaService.class);
        meterRegistry = new SimpleMeterRegistry();

        var conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        var conversationSetup = mock(IConversationSetup.class);
        var cacheFactory = mock(ICacheFactory.class);
        var conversationStateCache = (ICache<String, ConversationState>) mock(ICache.class);
        var contextLogger = mock(IContextLogger.class);
        var auditLedgerService = mock(AuditLedgerService.class);
        var gdprComplianceService = mock(GdprComplianceService.class);
        var scheduleStore = mock(IScheduleStore.class);
        var agentStore = mock(IAgentStore.class);
        var userMemoryStore = mock(IUserMemoryStore.class);
        var jsonSerialization = mock(IJsonSerialization.class);

        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                conversationDescriptorStore, userMemoryStore, conversationCoordinator,
                conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization, meterRegistry,
                ConversationServiceTestFixtures.hitlResumeEvent(), AGENT_TIMEOUT);
    }

    private double gauge() {
        return meterRegistry.get(GAUGE).gauge().value();
    }

    private ConversationMemorySnapshot snapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setEnvironment(ENV);
        snapshot.setConversationState(ConversationState.READY);

        var stepSnapshot = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();
        workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "hello", null, new Date(), null, true));
        stepSnapshot.getWorkflows().add(workflowRun);
        snapshot.getConversationSteps().add(stepSnapshot);
        var output = new ConversationOutput();
        output.put("input", "hello");
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }

    private void stubHealthySay() throws Exception {
        IAgent agent = mock(IAgent.class);
        IConversation conversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot());
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(agent.continueConversation(any(), any(), any())).thenReturn(conversation);
        when(conversation.isEnded()).thenReturn(false);
    }

    private void say() throws Exception {
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, mock(ConversationResponseHandler.class));
    }

    @SuppressWarnings("unchecked")
    private Callable<Void> captureQueuedTurn() {
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator, atLeastOnce()).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        return captor.getValue();
    }

    /**
     * C11 acceptance — the watchdog cancels the inner execution, so the completion
     * consumer (which used to be the only place the entry was removed) is never
     * invoked. The gauge must still return to zero.
     */
    @Test
    @Timeout(30)
    @DisplayName("gauge returns to zero after a turn the watchdog cancelled")
    @SuppressWarnings("unchecked")
    void gaugeReturnsToZeroAfterACancelledTurn() throws Exception {
        stubHealthySay();

        // The inner execution never finishes — future.get() times out, which is what
        // the agent-timeout watchdog reacts to.
        Future<Void> hungExecution = mock(Future.class);
        when(hungExecution.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("pipeline hung"));
        doReturn(hungExecution).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), isNull());

        say();

        assertEquals(1.0, gauge(), "an admitted turn must be counted while it is in flight");

        // Run the queued turn on this thread; it hits the watchdog and is abandoned.
        captureQueuedTurn().call();

        verify(hungExecution).cancel(true);
        assertEquals(0.0, gauge(),
                "the gauge must return to zero after a cancelled turn — the completion consumer never runs for one");
    }

    /**
     * C11 (cross-delete) — the old removal keyed on {@code agentId:conversationId},
     * which is identical for two concurrent turns of the same conversation. A turn
     * rejected BEFORE it was ever admitted therefore deleted a healthy in-flight
     * turn's entry and under-reported the gauge.
     */
    @Test
    @Timeout(30)
    @DisplayName("a rejected turn does not decrement a healthy concurrent turn's entry")
    void rejectedTurnDoesNotCancelOutAHealthyConcurrentTurn() throws Exception {
        stubHealthySay();

        say();
        assertEquals(1.0, gauge());

        // A second turn on the SAME conversation is rejected before admission.
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(null);
        assertThrows(AgentNotReadyException.class, this::say);

        assertEquals(1.0, gauge(),
                "the still-running first turn must remain counted — a rejected turn shares its metrics key");
    }

    /**
     * C10 + C11 — the coordinator can DROP a queued turn: when handing it to the
     * runtime is rejected while draining the queue there is no caller left to roll
     * back to, so the task is dead-lettered and its body is never invoked. The
     * release inside that body therefore never runs.
     *
     * <p>
     * Without a discard hook this re-creates exactly the leak C11 removed — the
     * gauge entry survives for the JVM's lifetime — AND leaves the HTTP caller
     * waiting for a response that can never arrive.
     * </p>
     */
    @Test
    @Timeout(30)
    @DisplayName("a turn the coordinator dropped releases the gauge and completes the caller")
    void aDroppedTurnReleasesTheGaugeAndCompletesTheCaller() throws Exception {
        stubHealthySay();

        ConversationResponseHandler handler = mock(ConversationResponseHandler.class);
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, handler);

        assertEquals(1.0, gauge(), "an admitted turn must be counted while it is in flight");

        Callable<Void> queued = captureQueuedTurn();
        assertInstanceOf(IDiscardableTask.class, queued,
                "the coordinator must be able to tell a queued turn that it was dropped without running");

        // The coordinator could not schedule it and dead-letters it — the body is
        // NEVER called.
        ((IDiscardableTask) queued).onDiscarded(new RejectedExecutionException("pool saturated"));

        assertEquals(0.0, gauge(),
                "a dropped turn must release its in-flight reference — its own finally block never runs");
        verify(handler).onSkipped(any());
        verify(handler, never()).onComplete(any());
    }

    /**
     * C3 — a GENUINE {@code InterruptedException} out of the callable body means
     * the turn never finished and no watchdog recorded anything, so it must leave
     * an ERROR record. Matching the bare {@code InterruptedException} type
     * conflated it with the abandonment signal and silently swallowed real failures
     * at WARN.
     */
    @Test
    @Timeout(30)
    @DisplayName("C3: a genuine InterruptedException from the body is recorded as an error")
    void genuineInterruptedExceptionIsRecordedAsAnError() throws Exception {
        runTurnThenFailWith(new InterruptedException("pipeline thread interrupted"));

        verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ERROR);
    }

    /**
     * C3 (the other half) — the abandonment signal must NOT be flipped to ERROR:
     * the watchdog already persisted the accurate EXECUTION_INTERRUPTED, and a late
     * ERROR write from the zombie turn is the stale overwrite the token exists to
     * prevent.
     */
    @Test
    @Timeout(30)
    @DisplayName("C3: an abandoned turn that completed anyway is not recorded as an error")
    void anAbandonedTurnIsNotRecordedAsAnError() throws Exception {
        runTurnThenFailWith(new ExecutionAbandonedException("Execution completed after cancellation — result discarded"));

        verify(conversationMemoryStore, never()).setConversationState(CONVERSATION_ID, ConversationState.ERROR);
    }

    /**
     * Runs a queued turn to the point where the inner execution reports failure,
     * then raises {@code failure} on the captured completion callback.
     */
    @SuppressWarnings("unchecked")
    private void runTurnThenFailWith(Throwable failure) throws Exception {
        stubHealthySay();

        Future<Void> innerExecution = mock(Future.class);
        doReturn(innerExecution).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), isNull());

        say();
        captureQueuedTurn().call();

        ArgumentCaptor<IRuntime.IFinishedExecution<Void>> callbackCaptor = ArgumentCaptor.forClass(IRuntime.IFinishedExecution.class);
        verify(runtime).submitCallable(any(Callable.class), callbackCaptor.capture(), isNull());
        callbackCaptor.getValue().onFailure(failure);
    }

    @Test
    @Timeout(30)
    @DisplayName("gauge returns to zero after a turn skipped by the queued-say guard")
    void gaugeReturnsToZeroAfterASkippedTurn() throws Exception {
        stubHealthySay();
        say();
        assertEquals(1.0, gauge());

        // By the time the queued turn runs, the conversation has ended → skipped.
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.ENDED);
        captureQueuedTurn().call();

        assertEquals(0.0, gauge());
    }

    /**
     * B3 — once the shutdown gate is closed, the conversation entry points must
     * refuse new turns rather than admitting work the JVM is about to drop.
     */
    @Test
    @Timeout(30)
    @DisplayName("B3: new turns are rejected once shutdown has been signalled")
    void newTurnsAreRejectedDuringShutdown() throws Exception {
        stubHealthySay();

        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenReturn(Map.of());
        // The event→flag→drain wiring is covered by GracefulShutdownServiceTest; here
        // we only assert that the service consults the gate. (drainTimeoutSeconds,
        // readinessGraceSeconds, drainPollMillis)
        AtomicBoolean signalled = new AtomicBoolean(false);
        conversationService.gracefulShutdownService = new GracefulShutdownService(coordinator, 1, 0, 1L) {
            @Override
            public boolean isShuttingDown() {
                return signalled.get();
            }
        };

        // Before the signal, turns are admitted normally.
        say();
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());

        signalled.set(true);

        assertThrows(RejectedExecutionException.class, this::say);
        assertThrows(RejectedExecutionException.class,
                () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                        new InputData("hello", Map.of()), null));
        assertThrows(RejectedExecutionException.class,
                () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, Map.of()));

        // A rejected turn must not be counted, and must not have been queued.
        verify(conversationCoordinator, times(1)).submitInOrder(eq(CONVERSATION_ID), any());
        assertEquals(1.0, gauge(), "the rejected turns must not touch the in-flight gauge");
    }
}
