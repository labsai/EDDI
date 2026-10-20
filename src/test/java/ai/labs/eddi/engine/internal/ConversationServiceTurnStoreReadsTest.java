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
import ai.labs.eddi.engine.memory.IConversationMemoryStore.StateAndRevision;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How many times one ordinary turn reads the conversation document — the round
 * trips the throughput plan (WS3a) is about. Every read waits for a database
 * connection, and under load that wait is most of a turn's latency.
 * <p>
 * Runs a whole {@code say} through the real {@link BaseRuntime} — request-time
 * load, the queued-turn guards when the turn starts, the pipeline (stubbed),
 * the completion and the persist — and counts the conversation-store calls.
 */
class ConversationServiceTurnStoreReadsTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "aaaabbbbccccddddeeeeffff";
    private static final String CONVERSATION_ID = "111122223333444455556666";
    private static final long REVISION = 7L;

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

        var cacheFactory = mock(ICacheFactory.class);
        var conversationStateCache = (ICache<String, ConversationState>) mock(ICache.class);
        var contextLogger = mock(IContextLogger.class);
        var auditLedgerService = mock(AuditLedgerService.class);
        var tenantQuotaService = mock(TenantQuotaService.class);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                mock(IConversationDescriptorStore.class), mock(IUserMemoryStore.class), conversationCoordinator,
                mock(IConversationSetup.class), cacheFactory, runtime, contextLogger, auditLedgerService,
                mock(GdprComplianceService.class), tenantQuotaService, mock(IScheduleStore.class), mock(IAgentStore.class),
                mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot());

        // The pipeline: hand the memory straight back as the turn's output, which is
        // what fires the completion (response, timestamp) before the persist.
        IAgent agent = mock(IAgent.class);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        AtomicReference<IConversationMemory> liveMemory = new AtomicReference<>();
        AtomicReference<IConversationOutputRenderer> renderer = new AtomicReference<>();
        when(agent.continueConversation(any(), any(), any())).thenAnswer(inv -> {
            liveMemory.set(inv.getArgument(0));
            renderer.set(inv.getArgument(2));
            return conversation;
        });
        doAnswer(inv -> {
            renderer.get().renderOutput(liveMemory.get());
            return null;
        }).when(conversation).say(anyString(), anyMap());
    }

    @AfterEach
    void tearDown() {
        runtime.getScheduledExecutorService().shutdownNow();
        pool.shutdownNow();
    }

    private static ConversationMemorySnapshot snapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId("user-1");
        snapshot.setAgentVersion(1);
        snapshot.setEnvironment(ENV);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setRevision(REVISION);
        var step = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();
        workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "hello", null, new Date(), null, true));
        step.getWorkflows().add(workflowRun);
        snapshot.getConversationSteps().add(step);
        var output = new ConversationOutput();
        output.put("input", "hello");
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private void runOneTurn() throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        var handler = mock(ConversationResponseHandler.class);
        doAnswer(inv -> {
            answered.countDown();
            return null;
        }).when(handler).onComplete(any());

        conversationService.say(CONVERSATION_ID, false, true, List.of(), new InputData("hello", Map.of()), false, handler);

        ArgumentCaptor<Callable<Void>> turn = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), turn.capture());
        turn.getValue().call();

        assertTrue(answered.await(20, TimeUnit.SECONDS), "the turn should have answered");
        // The persist runs after the answer, on the turn's own callback.
        verify(conversationMemoryStore, timeout(20_000)).storeConversationMemorySnapshot(any());
    }

    @Test
    @Timeout(60)
    @DisplayName("an ordinary turn reads the conversation twice: the document once, state and revision once")
    void anOrdinaryTurnReadsTheConversationTwice() throws Exception {
        when(conversationMemoryStore.getStateAndRevision(CONVERSATION_ID)).thenReturn(new StateAndRevision(ConversationState.READY, REVISION));

        runOneTurn();

        verify(conversationMemoryStore, times(1)).loadConversationMemorySnapshot(CONVERSATION_ID);
        verify(conversationMemoryStore, times(1)).getStateAndRevision(CONVERSATION_ID);
        // The two separate projections the combined read replaces, and the committed
        // state only a paused turn needs.
        verify(conversationMemoryStore, never()).getConversationState(anyString());
        verify(conversationMemoryStore, never()).getRevision(anyString());
    }

    @Test
    @Timeout(60)
    @DisplayName("a store that cannot read state and revision together is asked for each, as before")
    void aStoreWithoutTheCombinedReadIsAskedSeparately() throws Exception {
        // getStateAndRevision is left unstubbed: null, the interface default.
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        when(conversationMemoryStore.getRevision(CONVERSATION_ID)).thenReturn(REVISION);

        runOneTurn();

        verify(conversationMemoryStore, times(1)).loadConversationMemorySnapshot(CONVERSATION_ID);
        verify(conversationMemoryStore, times(1)).getConversationState(CONVERSATION_ID);
        verify(conversationMemoryStore, times(1)).getRevision(CONVERSATION_ID);
    }

    @Test
    @Timeout(60)
    @DisplayName("a turn queued behind a committed one is still rebuilt over the current document")
    void aSupersededTurnIsStillReloaded() throws Exception {
        when(conversationMemoryStore.getStateAndRevision(CONVERSATION_ID))
                .thenReturn(new StateAndRevision(ConversationState.READY, REVISION + 1));

        runOneTurn();

        // Request-time load, then the reload the newer revision demands.
        verify(conversationMemoryStore, times(2)).loadConversationMemorySnapshot(CONVERSATION_ID);
        verify(conversationMemoryStore, never()).getRevision(anyString());
    }
}
