/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
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
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@code eddi.conversation.max-in-flight-turns}: once turns no longer queue for
 * a worker thread, this limit is what refuses the turn past capacity — with the
 * retryable 503 {@code capacity_exceeded} every surface already maps a
 * {@link RejectedExecutionException} to. The permit is the C11
 * {@code ProcessingTurn} token, so every test here checks one exit path gives
 * it back.
 */
class ConversationServiceTurnAdmissionTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "6a0f5d783ab1ce8997018ce1";
    private static final String CONVERSATION_ID = "6a0f5d783ab1ce8997018ce2";
    private static final String USER_ID = "admission-user";
    private static final String GAUGE = "eddi_processing_conversation_count";

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationCoordinator conversationCoordinator;
    private TenantQuotaService tenantQuotaService;
    private SimpleMeterRegistry meterRegistry;
    private ConversationService conversationService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);
        tenantQuotaService = mock(TenantQuotaService.class);
        meterRegistry = new SimpleMeterRegistry();

        var cacheFactory = mock(ICacheFactory.class);
        var contextLogger = mock(IContextLogger.class);
        var auditLedgerService = mock(AuditLedgerService.class);
        doReturn(mock(ICache.class)).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                mock(IConversationDescriptorStore.class), mock(IUserMemoryStore.class), conversationCoordinator,
                mock(IConversationSetup.class), cacheFactory, mock(IRuntime.class), contextLogger, auditLedgerService,
                mock(GdprComplianceService.class), tenantQuotaService, mock(IScheduleStore.class), mock(IAgentStore.class),
                mock(IJsonSerialization.class), meterRegistry,
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);

        IAgent agent = mock(IAgent.class);
        IConversation conversation = mock(IConversation.class);
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenAnswer(inv -> snapshot());
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(agent.continueConversation(any(), any(), any())).thenReturn(conversation);
        when(conversation.isEnded()).thenReturn(false);
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

    private void say() throws Exception {
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, mock(ConversationResponseHandler.class));
    }

    private double inFlight() {
        return meterRegistry.get(GAUGE).gauge().value();
    }

    private double rejected() {
        Counter counter = meterRegistry.find("eddi.turn.rejected").tag("reason", "in_flight_limit").counter();
        return counter == null ? 0 : counter.count();
    }

    @SuppressWarnings("unchecked")
    private List<Callable<Void>> queuedTurns() {
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator, atLeastOnce()).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        return captor.getAllValues();
    }

    @Test
    @Timeout(30)
    @DisplayName("the turn past the limit is refused as capacity, counted, and spends no quota")
    void turnPastTheLimitIsRefused() throws Exception {
        conversationService.maxInFlightTurns = 2;

        say();
        say();
        TurnCapacityExceededException refused = assertThrows(TurnCapacityExceededException.class, this::say);

        assertInstanceOf(RejectedExecutionException.class, refused, "must reach the existing 503 capacity_exceeded mapping");
        assertEquals(2.0, inFlight(), "the refused turn must not be counted");
        assertEquals(1.0, rejected());
        verify(conversationCoordinator, times(2)).submitInOrder(eq(CONVERSATION_ID), any());
        verify(tenantQuotaService, times(2)).acquireApiCallSlot();
    }

    @Test
    @Timeout(30)
    @DisplayName("a turn that finishes gives its permit back")
    void finishedTurnReleasesItsPermit() throws Exception {
        conversationService.maxInFlightTurns = 1;
        say();
        assertThrows(TurnCapacityExceededException.class, this::say);

        // The queued turn runs and is skipped (the conversation ended meanwhile):
        // one of the exits that completes the caller.
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.ENDED);
        queuedTurns().getFirst().call();
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);

        assertEquals(0.0, inFlight());
        assertDoesNotThrow(this::say);
    }

    @Test
    @Timeout(30)
    @DisplayName("a turn the coordinator refuses gives its permit back")
    void coordinatorRefusalReleasesThePermit() throws Exception {
        conversationService.maxInFlightTurns = 1;
        doThrow(new RejectedExecutionException("coordinator full")).when(conversationCoordinator).submitInOrder(any(), any());

        assertThrows(RejectedExecutionException.class, this::say);
        assertEquals(0.0, inFlight());

        doNothing().when(conversationCoordinator).submitInOrder(any(), any());
        assertDoesNotThrow(this::say);
        assertEquals(0.0, rejected(), "a coordinator refusal is not an in-flight-limit refusal");
    }

    @Test
    @Timeout(30)
    @DisplayName("a quota denial gives the permit back")
    void quotaDenialReleasesThePermit() throws Exception {
        conversationService.maxInFlightTurns = 1;
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.denied("over quota"));

        assertThrows(QuotaExceededException.class, this::say);
        assertEquals(0.0, inFlight());

        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        assertDoesNotThrow(this::say);
    }

    @Test
    @Timeout(30)
    @DisplayName("a turn the coordinator dropped without running gives its permit back")
    void discardedTurnReleasesThePermit() throws Exception {
        conversationService.maxInFlightTurns = 1;
        say();

        ((IDiscardableTask) queuedTurns().getFirst()).onDiscarded(new RejectedExecutionException("dropped"));

        assertEquals(0.0, inFlight());
        assertDoesNotThrow(this::say);
    }

    @Test
    @Timeout(30)
    @DisplayName("left unset, the limit resolves from the heap (auto) and is exported as a gauge")
    void unsetLimitResolvesFromTheHeap() {
        int expected = InFlightTurnLimit.resolve("auto", Runtime.getRuntime().maxMemory(), 2);

        assertEquals(expected, conversationService.inFlightTurnLimit());
        assertEquals(expected, meterRegistry.get("eddi.conversation.max_in_flight_turns").gauge().value());
    }

    @Test
    @Timeout(30)
    @DisplayName("admission uses the configured setting, not only a value set by hand")
    void admissionResolvesTheSetting() throws Exception {
        conversationService.maxInFlightTurnsSetting = "1";

        say();
        assertThrows(TurnCapacityExceededException.class, this::say);
    }

    @Test
    @Timeout(30)
    @DisplayName("a setting that is neither auto nor a number fails at startup")
    void invalidSettingFailsAtStartup() {
        conversationService.maxInFlightTurnsSetting = "lots";
        assertThrows(IllegalArgumentException.class, conversationService::resolveInFlightTurnLimit);
    }

    @Test
    @Timeout(30)
    @DisplayName("a limit of 0 admits everything")
    void zeroDisablesTheLimit() throws Exception {
        conversationService.maxInFlightTurns = 0;
        for (int i = 0; i < 25; i++) {
            say();
        }
        assertEquals(25.0, inFlight());
        assertEquals(0.0, rejected());
    }

    @Test
    @Timeout(30)
    @DisplayName("concurrent admissions never overshoot the limit")
    void concurrentAdmissionsNeverOvershoot() throws Exception {
        final int limit = 5;
        AtomicInteger gauge = new AtomicInteger();
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger highest = new AtomicInteger();
        AtomicInteger refusals = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                futures.add(threads.submit(() -> {
                    start.await();
                    for (int i = 0; i < 2000; i++) {
                        try {
                            ConversationService.ProcessingTurn turn = ConversationService.ProcessingTurn.admit(gauge, counter, limit);
                            highest.accumulateAndGet(counter.get(), Math::max);
                            turn.finish();
                            turn.finish(); // idempotent: a second finish must not free a second permit
                        } catch (TurnCapacityExceededException e) {
                            refusals.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }
        assertTrue(highest.get() <= limit, "observed " + highest.get() + " turns in flight with a limit of " + limit);
        assertEquals(0, counter.get(), "every permit must be back");
        assertEquals(0, gauge.get(), "and the gauge with them");
    }

    /**
     * The client gets its answer before the turn is persisted (the completion
     * consumer runs first, persistence after it on the same thread). The gauge
     * drops then; the permit must not, or new turns run while earlier ones are
     * still writing and the node exceeds its limit.
     */
    @Test
    @Timeout(30)
    @DisplayName("the client-visible release leaves the permit held until the turn task finishes")
    void clientCompletionDoesNotReturnThePermit() {
        AtomicInteger gauge = new AtomicInteger();
        AtomicInteger permits = new AtomicInteger();
        ConversationService.ProcessingTurn turn = ConversationService.ProcessingTurn.admit(gauge, permits, 1);

        turn.release(); // completeTurn: the answer is out
        assertEquals(0, gauge.get(), "the gauge follows the client-visible completion");
        assertThrows(TurnCapacityExceededException.class, () -> ConversationService.ProcessingTurn.admit(gauge, permits, 1),
                "the turn is still persisting, so its permit is still held");

        turn.finish(); // the turn task's finally
        assertEquals(0, permits.get());
        ConversationService.ProcessingTurn.admit(gauge, permits, 1).finish();
        assertEquals(0, gauge.get());
    }

    @Test
    @Timeout(30)
    @DisplayName("a new conversation's start turn is refused at the limit and spends no conversation quota")
    void startConversationCountsAgainstTheLimit() throws Exception {
        conversationService.maxInFlightTurns = 1;
        say();
        when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(mock(IAgent.class));

        assertThrows(TurnCapacityExceededException.class,
                () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, new HashMap<>()));
        verify(tenantQuotaService, never()).acquireConversationSlot();
        assertEquals(1.0, rejected());
    }

    @Test
    @Timeout(30)
    @DisplayName("a start turn gives its permit back however it ends")
    void startConversationReturnsItsPermit() throws Exception {
        conversationService.maxInFlightTurns = 1;
        IAgent agent = mock(IAgent.class);
        when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(agent);
        when(tenantQuotaService.acquireConversationSlot()).thenReturn(QuotaCheckResult.OK);
        when(agent.startConversation(any(), any(), any(), any())).thenThrow(new IllegalStateException("start pipeline failed"));

        assertThrows(IllegalStateException.class,
                () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, new HashMap<>()));

        assertEquals(0.0, inFlight());
        assertDoesNotThrow(this::say, "the failed start must not keep the only permit");
    }
}
