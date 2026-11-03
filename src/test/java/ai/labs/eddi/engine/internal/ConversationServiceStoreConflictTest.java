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
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseUnavailableException;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.ConversationFencedException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.BaseRuntime;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.ILeaseAwareTask;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the service does with an optimistic-concurrency conflict once the store
 * has reported one. The store-level behaviour is covered against real databases
 * in {@code MongoConversationTurnConcurrencyTest} and
 * {@code PostgresConversationMemoryStoreTest}; this covers the two service
 * paths that must turn a refused write into something observable instead of
 * silence.
 * <p>
 * Wires the real {@link BaseRuntime} (as
 * {@code ConversationServiceStaleTurnTest} does) so the say-path completion
 * callback actually runs.
 */
class ConversationServiceStoreConflictTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "conflict-agent-id";
    private static final String CONVERSATION_ID = "aabbccddeeff112233445577";
    private static final String USER_ID = "conflict-user-id";
    private static final String CONFLICT_COUNTER = "eddi_conversation_store_conflict_count";

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationCoordinator conversationCoordinator;
    private IConversation conversation;
    private SimpleMeterRegistry meterRegistry;

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
        meterRegistry = new SimpleMeterRegistry();

        runtime = new BaseRuntime("TestProject", "1.0.0");
        pool = Executors.newFixedThreadPool(2);
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
                mock(IJsonSerialization.class), meterRegistry,
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 10);
    }

    @AfterEach
    void tearDown() {
        runtime.getScheduledExecutorService().shutdownNow();
        pool.shutdownNow();
    }

    /**
     * The say path cannot answer a conflict with a 409 — the reply was already
     * handed to the caller from inside the pipeline. It must at least count it, and
     * it must NOT break the conversation by flipping it to ERROR: the document on
     * disk belongs to the writer that won.
     */
    @Test
    @Timeout(30)
    @DisplayName("a say turn whose persist conflicts is counted, and the winner's conversation is not flipped to ERROR")
    @SuppressWarnings("unchecked")
    void sayPathConflictIsCountedAndDoesNotBreakTheConversation() throws Exception {
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshot(1));
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        IAgent agent = mock(IAgent.class);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(agent.continueConversation(any(), any(), any())).thenReturn(conversation);
        when(conversation.isEnded()).thenReturn(false);
        when(conversationMemoryStore.storeConversationMemorySnapshot(any()))
                .thenThrow(new ConcurrentConversationModificationException(CONVERSATION_ID, 3L));
        TurnIdempotencyService turnIdempotency = spy(new TurnIdempotencyService(600, 10, meterRegistry, System::currentTimeMillis));
        conversationService.turnIdempotency = turnIdempotency;

        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, mock(ConversationResponseHandler.class));

        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        captor.getValue().call();

        verify(conversation).say(anyString(), anyMap());
        verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        assertEquals(1.0, meterRegistry.counter(CONFLICT_COUNTER).count(),
                "a refused turn persist must be counted, or the loss is as invisible as before the guard");
        verify(conversationMemoryStore, never()).setConversationState(CONVERSATION_ID, ConversationState.ERROR);
        verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), eq(ConversationState.ERROR));
        // The answer the caller got is not in the conversation: an Idempotency-Key
        // retry must run the turn again, not be handed that answer from the cache.
        verify(turnIdempotency).forgetUnpersisted(CONVERSATION_ID);
    }

    @Test
    @Timeout(30)
    @DisplayName("a keyed say turn whose persist conflicts is forgotten from the replay cache: a same-key retry runs the turn again")
    @SuppressWarnings("unchecked")
    void keyedSayConflictIsNotReplayedToASameKeyRetry() throws Exception {
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshot(1));
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        IAgent agent = mock(IAgent.class);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(agent.continueConversation(any(), any(), any())).thenReturn(conversation);
        when(conversation.isEnded()).thenReturn(false);
        when(conversationMemoryStore.storeConversationMemorySnapshot(any()))
                .thenThrow(new ConcurrentConversationModificationException(CONVERSATION_ID, 3L));
        TurnIdempotencyService turnIdempotency = new TurnIdempotencyService(600, 10, meterRegistry, System::currentTimeMillis);
        conversationService.turnIdempotency = turnIdempotency;
        // The pipeline hands the answer to the caller (which remembers it under the
        // key) before the
        // persist is attempted: the cached answer is exactly what a same-key retry
        // would be served.
        doAnswer(invocation -> {
            var answer = new SimpleConversationMemorySnapshot();
            answer.setConversationState(ConversationState.READY); // only a settled answer is kept for replay
            turnIdempotency.complete(CONVERSATION_ID, "retry-key-1", new TurnIdempotencyService.Outcome(answer, false));
            return null;
        }).when(conversation).say(anyString(), anyMap());

        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        for (int attempt = 1; attempt <= 2; attempt++) {
            var input = new InputData("hello", Map.of());
            input.setIdempotencyKey("retry-key-1");
            // The overload that admits a keyed turn (the environment/agent one runs it
            // directly).
            conversationService.say(CONVERSATION_ID, false, false, List.of(), input, false, mock(ConversationResponseHandler.class));
            verify(conversationCoordinator, times(attempt)).submitInOrder(eq(CONVERSATION_ID), captor.capture());
            captor.getValue().call();
        }

        // Served from the cache, the second attempt would never have been queued, let
        // alone run.
        verify(conversation, times(2)).say(anyString(), anyMap());
    }

    /**
     * Undo, redo, resume and the pause commit all go through the conditional store,
     * which now misses for two reasons. When the state still matches, the miss was
     * a revision conflict and must be counted, not logged as a benign state
     * handover.
     */
    @Test
    @DisplayName("a conditional store that misses on the revision (state unchanged) is counted as a conflict")
    void conditionalStoreRevisionMissIsCounted() throws Exception {
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshot(2));
        when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY))).thenReturn(false);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);

        assertFalse(conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID), "a refused undo still reports no-op (409)");
        assertEquals(1.0, meterRegistry.counter(CONFLICT_COUNTER).count());
    }

    @Test
    @DisplayName("a conditional store that misses because the state moved is not counted as a conflict")
    void conditionalStoreStateMissIsNotCounted() throws Exception {
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshot(2));
        when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY))).thenReturn(false);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.ENDED);

        assertFalse(conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID));
        assertEquals(0.0, meterRegistry.counter(CONFLICT_COUNTER).count(),
                "a terminal state handover is the pre-existing, intended outcome — not a lost update");
    }

    /**
     * Cluster mode: the coordinator runs every queued task at once, under
     * {@code lease}.
     */
    private void clusterCoordinatorGranting(LeaseHandle lease) {
        IConversationLeaseManager leaseManager = mock(IConversationLeaseManager.class);
        when(leaseManager.isClustered()).thenReturn(true);
        conversationService.leaseManager = leaseManager;
        doAnswer(inv -> {
            Callable<Void> task = inv.getArgument(1);
            if (lease == null) {
                ((IDiscardableTask) task)
                        .onDiscarded(new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, "node-b", "held elsewhere"));
                return null;
            }
            ((ILeaseAwareTask) task).bindLease(lease);
            task.call();
            return null;
        }).when(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
    }

    private static LeaseHandle lease(long fence) {
        LeaseHandle lease = mock(LeaseHandle.class);
        when(lease.fence()).thenReturn(fence);
        return lease;
    }

    @Test
    @DisplayName("A4: in cluster mode an undo runs through the coordinator, raises the fence first and writes with the lease's token")
    void clusterUndoRunsUnderTheLease() throws Exception {
        clusterCoordinatorGranting(lease(9L));
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshot(2));
        ArgumentCaptor<ConversationMemorySnapshot> written = ArgumentCaptor.forClass(ConversationMemorySnapshot.class);
        when(conversationMemoryStore.storeConversationMemorySnapshotIfState(written.capture(), eq(ConversationState.READY)))
                .thenReturn(true);

        assertTrue(conversationService.undo(CONVERSATION_ID));

        var order = inOrder(conversationMemoryStore);
        order.verify(conversationMemoryStore).raiseFence(CONVERSATION_ID, 9L);
        order.verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
        assertEquals(9L, written.getValue().getFenceToken(), "the undo's write is fenced like a turn's");
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
    }

    @Test
    @DisplayName("A4: in cluster mode an undo whose lease never comes is a no-op (409) and writes nothing")
    void clusterUndoWithoutTheLeaseWritesNothing() throws Exception {
        clusterCoordinatorGranting(null);
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshot(2));

        assertFalse(conversationService.redo(CONVERSATION_ID));
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshotIfState(any(), any());
    }

    @Test
    @DisplayName("A6: an undo refused by the fence is a no-op (409), counted, never an ERROR")
    void fencedUndoIsANoOp() throws Exception {
        clusterCoordinatorGranting(lease(4L));
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshot(2));
        when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY)))
                .thenThrow(new ConversationFencedException(CONVERSATION_ID, 4L, 9L));

        assertFalse(conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID));
        assertEquals(1.0, meterRegistry.counter("eddi.cluster.fence.rejected").count());
        verify(conversationMemoryStore, never()).setConversationState(anyString(), eq(ConversationState.ERROR));
        verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), eq(ConversationState.ERROR));
    }

    private static ConversationMemorySnapshot storedSnapshot(int steps) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setEnvironment(ENV);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setRevision(3L);
        for (int i = 0; i < steps; i++) {
            var stepSnapshot = new ConversationStepSnapshot();
            var workflowRun = new WorkflowRunSnapshot();
            workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "turn " + i, null, new Date(), null, true));
            stepSnapshot.getWorkflows().add(workflowRun);
            snapshot.getConversationSteps().add(stepSnapshot);
            var output = new ConversationOutput();
            output.put("input", "turn " + i);
            snapshot.getConversationOutputs().add(output);
        }
        return snapshot;
    }
}
