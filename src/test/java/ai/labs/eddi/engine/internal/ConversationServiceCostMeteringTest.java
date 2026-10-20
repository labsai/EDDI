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
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
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
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.TurnCostMeter;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.memory.model.Data;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Settle-point wiring of the tenant monthly cost budget through the real
 * {@code say(conversationId, …)} path: a priced turn is charged once, at the
 * pipeline's completion callback, and never for a turn that did not settle
 * here.
 */
class ConversationServiceCostMeteringTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "0123456789abcdef01234567";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "0123456789abcdef89abcdef";
    private static final String USER_ID = "user-cost";

    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private IConversationMemoryStore conversationMemoryStore;
    @Mock
    private IConversationDescriptorStore conversationDescriptorStore;
    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private IConversationCoordinator conversationCoordinator;
    @Mock
    private IConversationSetup conversationSetup;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private IRuntime runtime;
    @Mock
    private IContextLogger contextLogger;
    @Mock
    private AuditLedgerService auditLedgerService;
    @Mock
    private GdprComplianceService gdprComplianceService;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private ICache<String, ConversationState> conversationStateCache;
    @Mock
    private IAgent agent;
    @Mock
    private IConversation conversation;

    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());
    private ConversationService conversationService;
    private final List<IConversationMemory> builtOver = new ArrayList<>();
    private final List<IConversationOutputRenderer> renderers = new ArrayList<>();
    /** The priced spend the next pipeline run adds to the current step. */
    private double turnCost = 0.25;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        doReturn(new HashMap<String, String>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
        doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        doReturn(1L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        doReturn(snapshot(1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(false).when(conversation).isEnded();
        doAnswer(inv -> {
            builtOver.add(inv.getArgument(0));
            renderers.add(inv.getArgument(2));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
        // The "pipeline": records the turn's priced spend on the step, settles,
        // renders.
        doAnswer(inv -> {
            IConversationMemory memory = builtOver.getLast();
            if (turnCost > 0) {
                memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, turnCost));
            }
            memory.setConversationState(ConversationState.READY);
            renderers.getLast().renderOutput(memory);
            return null;
        }).when(conversation).say(anyString(), any());
        stubRuntimeInline();

        conversationService = new ConversationService(agentFactory, conversationMemoryStore, conversationDescriptorStore, userMemoryStore,
                conversationCoordinator, conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore, jsonSerialization, new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
        conversationService.turnIdempotency = new TurnIdempotencyService(600, 30, new SimpleMeterRegistry(), now::get);
        doReturn("default").when(tenantQuotaService).getDefaultTenantId();
        doReturn(QuotaCheckResult.OK).when(tenantQuotaService).recordCost(anyString(), anyDouble());
        conversationService.turnCostMeter = new TurnCostMeter(tenantQuotaService, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("a priced turn is charged to the tenant once, and the persisted step records what was charged")
    void pricedTurnIsChargedAtSettle() throws Exception {
        say(null, mock(ConversationResponseHandler.class));
        runQueuedTurns(1);

        verify(tenantQuotaService, times(1)).recordCost("default", 0.25);
        assertEquals(0.25, meteredCostOf(persistedSnapshot()), 1e-12);
    }

    @Test
    @DisplayName("an unpriced turn never touches the cost counter")
    void unpricedTurnIsNotCharged() throws Exception {
        turnCost = 0.0;
        say(null, mock(ConversationResponseHandler.class));
        runQueuedTurns(1);

        verify(tenantQuotaService, never()).recordCost(anyString(), anyDouble());
    }

    @Test
    @DisplayName("a replayed idempotent turn is answered from the stored step and is not charged again")
    void idempotentReplayIsNotChargedAgain() throws Exception {
        say("k-cost", mock(ConversationResponseHandler.class));
        runQueuedTurns(1);
        ConversationMemorySnapshot persisted = persistedSnapshot();

        conversationService.turnIdempotency = new TurnIdempotencyService(600, 30, new SimpleMeterRegistry(), now::get);
        doReturn(persisted).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        ConversationResponseHandler duplicate = mock(ConversationResponseHandler.class);
        say("k-cost", duplicate);

        verify(duplicate, timeout(2000)).onComplete(any());
        verify(tenantQuotaService, times(1)).recordCost(anyString(), anyDouble());
    }

    @Test
    @DisplayName("a turn stopped by lease loss is not charged: nothing of it is stored, and the new owner runs it again")
    void leaseLostTurnIsNotCharged() throws Exception {
        conversationService = spy(conversationService);
        doReturn(true).when(conversationService).stoppedByLeaseLoss(anyString(), any(), any());

        say(null, mock(ConversationResponseHandler.class));
        runQueuedTurns(1);

        verify(conversationService, atLeastOnce()).stoppedByLeaseLoss(anyString(), any(), any());
        verify(tenantQuotaService, never()).recordCost(anyString(), anyDouble());
    }

    private void say(String key, ConversationResponseHandler handler) throws Exception {
        var input = new InputData("hello", new HashMap<>());
        if (key != null) {
            input.setIdempotencyKey(key);
        }
        conversationService.say(CONVERSATION_ID, false, true, List.of(), input, false, handler);
    }

    private void runQueuedTurns(int expected) throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator, times(expected)).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        for (Callable<Void> turn : captor.getAllValues()) {
            turn.call();
        }
    }

    private ConversationMemorySnapshot persistedSnapshot() throws Exception {
        ArgumentCaptor<ConversationMemorySnapshot> stored = ArgumentCaptor.forClass(ConversationMemorySnapshot.class);
        verify(conversationMemoryStore, atLeastOnce()).storeConversationMemorySnapshot(stored.capture());
        return stored.getValue();
    }

    private static double meteredCostOf(ConversationMemorySnapshot snapshot) {
        return snapshot.getConversationSteps().getLast().getWorkflows().stream().flatMap(w -> w.getLifecycleTasks().stream())
                .filter(r -> MemoryKeys.AUDIT_METERED_COST.equals(r.getKey())).map(r -> ((Number) r.getResult()).doubleValue()).findFirst()
                .orElseThrow(() -> new AssertionError("no audit:metered_cost on the persisted step"));
    }

    @SuppressWarnings("unchecked")
    private void stubRuntimeInline() {
        doAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(0);
            IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
            Object result = callable.call();
            listener.onComplete(result);
            return CompletableFuture.completedFuture(result);
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    private static ConversationMemorySnapshot snapshot(int steps) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setEnvironment(ENV);
        snapshot.setRevision(1L);
        for (int i = 0; i < steps; i++) {
            var stepSnapshot = new ConversationStepSnapshot();
            var workflowRun = new WorkflowRunSnapshot();
            workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "message " + i, null, new Date(), null, true));
            stepSnapshot.getWorkflows().add(workflowRun);
            snapshot.getConversationSteps().add(stepSnapshot);
            var output = new ConversationOutput();
            output.put("input", "message " + i);
            snapshot.getConversationOutputs().add(output);
        }
        return snapshot;
    }
}
