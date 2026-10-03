/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.internal.LifecycleManager;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.memory.IConversationMemory;
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
import ai.labs.eddi.engine.runtime.BaseRuntime;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The agent-timeout watchdog must actually stop a turn it gives up on.
 * <p>
 * Before the fix the watchdog only cancelled the future: an interrupt, which
 * any lower layer can swallow, plus the abandonment token that suppresses the
 * turn's final persist. A pipeline that swallowed the interrupt therefore kept
 * running every remaining lifecycle task — model calls, tool calls, user-memory
 * writes — for an outcome nobody would read. And because the turn left the
 * in-flight registry as soon as the watchdog returned, neither {@code /cancel}
 * nor the GDPR {@code stopInFlightWork} sweep could reach it any more.
 * <p>
 * Wired against the REAL {@link BaseRuntime} and a REAL
 * {@link LifecycleManager} so the cancel flag is exercised at the actual task
 * boundary.
 */
class ConversationWatchdogAbandonTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "watchdog-agent-id";
    private static final String CONVERSATION_ID = "watchdog-conversation-id";
    private static final String USER_ID = "watchdog-user-id";
    /** Seconds — the smallest watchdog the service accepts. */
    private static final int AGENT_TIMEOUT = 1;

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationCoordinator conversationCoordinator;

    private BaseRuntime runtime;
    private ExecutorService pool;
    private ConversationService conversationService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);

        runtime = new BaseRuntime("TestProject", "1.0.0");
        pool = Executors.newFixedThreadPool(4);
        ManagedExecutor managedExecutor = mock(ManagedExecutor.class);
        when(managedExecutor.submit(any(Callable.class))).thenAnswer(inv -> pool.submit((Callable<?>) inv.getArgument(0)));
        var executorField = BaseRuntime.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        executorField.set(runtime, managedExecutor);

        var cacheFactory = mock(ICacheFactory.class);
        var contextLogger = mock(IContextLogger.class);
        var auditLedgerService = mock(AuditLedgerService.class);
        var tenantQuotaService = mock(TenantQuotaService.class);
        doReturn(mock(ICache.class)).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                mock(IConversationDescriptorStore.class), mock(IUserMemoryStore.class), conversationCoordinator,
                mock(IConversationSetup.class), cacheFactory, runtime, contextLogger, auditLedgerService,
                mock(GdprComplianceService.class), tenantQuotaService, mock(IScheduleStore.class), mock(IAgentStore.class),
                mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), AGENT_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        runtime.getScheduledExecutorService().shutdownNow();
        pool.shutdownNow();
    }

    @Test
    @Timeout(60)
    @DisplayName("a timed-out turn stops at the next task boundary, writes nothing, and stays reachable until it ends")
    void timedOutTurnStopsAtNextBoundaryAndStaysReachable() throws Exception {
        CountDownLatch firstTaskRunning = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        CountDownLatch pipelineReturned = new CountDownLatch(1);
        AtomicInteger laterTaskRuns = new AtomicInteger();
        AtomicBoolean stoppedAtBoundary = new AtomicBoolean();
        AtomicReference<IConversationMemory> turnMemory = new AtomicReference<>();

        // Task 1: a hung model call whose client library swallows the watchdog's
        // interrupt — it clears the flag and keeps waiting.
        ILifecycleTask hungTask = task("hung", memory -> {
            firstTaskRunning.countDown();
            while (releaseFirstTask.getCount() > 0) {
                try {
                    releaseFirstTask.await();
                } catch (InterruptedException swallowed) {
                    // deliberately swallowed, as lower layers do
                }
            }
        });
        // Task 2: anything with a side effect (a tool call, a user-memory write).
        ILifecycleTask sideEffectTask = task("side-effect", memory -> laterTaskRuns.incrementAndGet());

        IComponentCache componentCache = mock(IComponentCache.class);
        IResourceStore.IResourceId workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("workflow");
        when(workflowId.getVersion()).thenReturn(1);
        LifecycleManager lifecycleManager = new LifecycleManager(componentCache, workflowId);
        lifecycleManager.addLifecycleTask(hungTask);
        lifecycleManager.addLifecycleTask(sideEffectTask);

        IConversation conversation = mock(IConversation.class);
        IAgent agent = mock(IAgent.class);
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot());
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(agent.continueConversation(any(), any(), any())).thenAnswer(inv -> {
            turnMemory.set(inv.getArgument(0));
            return conversation;
        });
        when(conversation.isEnded()).thenReturn(false);
        doAnswer(inv -> {
            try {
                lifecycleManager.executeLifecycle(turnMemory.get(), null);
            } catch (ConversationStopException e) {
                stoppedAtBoundary.set(true);
            } finally {
                pipelineReturned.countDown();
            }
            return null;
        }).when(conversation).say(anyString(), anyMap());

        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, mock(ConversationResponseHandler.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());

        // Runs the turn: the coordinator thread blocks until the watchdog expires.
        captor.getValue().call();
        assertTrue(firstTaskRunning.await(10, TimeUnit.SECONDS), "the pipeline should have started");
        verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);

        // The watchdog raised the durable stop signal, not just an interrupt.
        assertTrue(turnMemory.get().isCancelled(), "the watchdog must flag the abandoned turn cancelled");
        // The zombie is still running, so the GDPR stop sweep (and /cancel) must still
        // find it — it used to be unregistered the moment the watchdog returned.
        assertEquals(1, conversationService.stopInFlightWork(USER_ID),
                "a timed-out turn that is still running must stay reachable for GDPR stop and /cancel");

        // The hung call returns at last.
        releaseFirstTask.countDown();
        assertTrue(pipelineReturned.await(10, TimeUnit.SECONDS), "the pipeline should have returned");

        assertTrue(stoppedAtBoundary.get(), "the pipeline must stop at the next task boundary");
        assertEquals(0, laterTaskRuns.get(), "no lifecycle task may run after the watchdog gave up on the turn");
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        verify(conversationMemoryStore, never()).setConversationState(CONVERSATION_ID, ConversationState.ERROR);

        // Once the body has really ended, the registration is released.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (conversationService.stopInFlightWork(USER_ID) != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, conversationService.stopInFlightWork(USER_ID), "the finished zombie must be unregistered");
    }

    @Test
    @Timeout(60)
    @DisplayName("a turn that finishes in time is unregistered as before")
    void turnThatFinishesInTimeIsUnregistered() throws Exception {
        IConversation conversation = mock(IConversation.class);
        IAgent agent = mock(IAgent.class);
        AtomicReference<IConversationMemory> turnMemory = new AtomicReference<>();
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot());
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(agent.continueConversation(any(), any(), any())).thenAnswer(inv -> {
            turnMemory.set(inv.getArgument(0));
            return conversation;
        });
        when(conversation.isEnded()).thenReturn(false);

        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, mock(ConversationResponseHandler.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        captor.getValue().call();

        assertFalse(turnMemory.get().isCancelled(), "a turn that finished in time is not abandoned");
        assertEquals(0, conversationService.stopInFlightWork(USER_ID));
        verify(conversationMemoryStore, never()).setConversationState(CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);
    }

    @FunctionalInterface
    private interface TaskBody {
        void run(IConversationMemory memory) throws Exception;
    }

    private static ILifecycleTask task(String id, TaskBody body) throws Exception {
        ILifecycleTask task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId(id));
        when(task.getType()).thenReturn(id);
        doAnswer(inv -> {
            body.run(inv.getArgument(0));
            return null;
        }).when(task).execute(any(), any());
        return task;
    }

    private static ConversationMemorySnapshot snapshot() {
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
}
