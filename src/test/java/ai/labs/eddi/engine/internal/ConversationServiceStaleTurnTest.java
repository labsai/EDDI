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
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.BaseRuntime;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C3 — a turn that exceeds the agent-timeout watchdog must never persist its
 * outcome, because by then the conversation may already have moved on (a newer
 * turn, a cancel, an end).
 *
 * <p>
 * The guard used to be the thread's interrupt flag. That is not a safe
 * completion guard: the pipeline consumes the interrupt via
 * {@code Thread.interrupted()}, which CLEARS the flag, so the abandoned turn
 * reported success and wrote its full stale snapshot over whatever came after
 * it. This test wires the REAL {@link BaseRuntime} into the service so the
 * abandonment token is exercised end to end.
 * </p>
 */
class ConversationServiceStaleTurnTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "stale-agent-id";
    private static final String CONVERSATION_ID = "stale-conversation-id";
    private static final String USER_ID = "stale-user-id";
    /** Seconds — the smallest watchdog the service accepts. */
    private static final int AGENT_TIMEOUT = 1;

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationCoordinator conversationCoordinator;
    private IConversation conversation;

    private BaseRuntime runtime;
    private ExecutorService pool;
    private ConversationService conversationService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);
        conversation = mock(IConversation.class);

        runtime = new BaseRuntime("TestProject", "1.0.0");
        pool = Executors.newFixedThreadPool(4);
        ManagedExecutor managedExecutor = mock(ManagedExecutor.class);
        when(managedExecutor.submit(any(Callable.class))).thenAnswer(inv -> pool.submit((Callable<?>) inv.getArgument(0)));
        var executorField = BaseRuntime.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        executorField.set(runtime, managedExecutor);

        var conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        var conversationSetup = mock(IConversationSetup.class);
        var cacheFactory = mock(ICacheFactory.class);
        var conversationStateCache = (ICache<String, ConversationState>) mock(ICache.class);
        var contextLogger = mock(IContextLogger.class);
        var auditLedgerService = mock(AuditLedgerService.class);
        var gdprComplianceService = mock(GdprComplianceService.class);
        var tenantQuotaService = mock(TenantQuotaService.class);
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
                jsonSerialization, new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), AGENT_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        runtime.getScheduledExecutorService().shutdownNow();
        pool.shutdownNow();
    }

    @Test
    @Timeout(60)
    @DisplayName("C3: a turn that outlives the watchdog never persists its snapshot")
    @SuppressWarnings("unchecked")
    void abandonedTurnDoesNotPersistOverANewerTurn() throws Exception {
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

        IAgent agent = mock(IAgent.class);
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(agent.continueConversation(any(), any(), any())).thenReturn(conversation);
        when(conversation.isEnded()).thenReturn(false);

        CountDownLatch pipelineStarted = new CountDownLatch(1);
        CountDownLatch releasePipeline = new CountDownLatch(1);
        CountDownLatch pipelineFinished = new CountDownLatch(1);
        // Any persistence attempt by the abandoned turn trips this latch.
        CountDownLatch stalePersistAttempted = new CountDownLatch(1);

        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenAnswer(inv -> {
            stalePersistAttempted.countDown();
            return CONVERSATION_ID;
        });

        doAnswer(inv -> {
            pipelineStarted.countDown();
            // A pipeline that swallows the watchdog's interrupt — exactly what
            // Thread.interrupted() in the lifecycle code does — and then completes.
            while (releasePipeline.getCount() > 0) {
                try {
                    releasePipeline.await();
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
            pipelineFinished.countDown();
            return null;
        }).when(conversation).say(anyString(), anyMap());

        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, mock(ConversationResponseHandler.class));

        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());

        // Runs the turn: it blocks on the hung pipeline until the watchdog expires.
        captor.getValue().call();

        assertTrue(pipelineStarted.await(10, TimeUnit.SECONDS), "the pipeline should have started");
        // The watchdog parked the conversation — this is the state the abandoned turn
        // must not overwrite.
        verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());

        // Now the abandoned pipeline finishes anyway.
        releasePipeline.countDown();
        assertTrue(pipelineFinished.await(10, TimeUnit.SECONDS), "the pipeline should have completed");

        assertFalse(stalePersistAttempted.await(2, TimeUnit.SECONDS),
                "a turn abandoned by the watchdog must never persist its stale snapshot — "
                        + "that is what overwrites a newer turn");
        // ...and it must not downgrade the watchdog's EXECUTION_INTERRUPTED to ERROR.
        verify(conversationMemoryStore, never()).setConversationState(CONVERSATION_ID, ConversationState.ERROR);
    }
}
