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
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore.StateAndRevision;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.ExecutionAbandonedException;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * RT-5: the late outcome of a turn the agent-timeout watchdog gave up on.
 * <p>
 * Once the watchdog gives up, the turn's task returns and the coordinator runs
 * the conversation's next turn, while a body whose interrupt was swallowed
 * keeps running. When that body finally ends, the runtime hands its outcome to
 * the turn's callbacks. The watchdog already recorded the abandoned turn's
 * state and its caller is gone, so the outcome must not touch the conversation,
 * which by then belongs to the next turn.
 * <p>
 * Driven through {@code say()} with an agent timeout of 0 and a runtime that
 * never finishes the first turn, so the watchdog gives up at once.
 */
class ConversationStepRunnerAbandonedTurnTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-abandoned";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "6ac11c7a3dc075f0b3dbb8ed";

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

    private ConversationService conversationService;

    /**
     * The completion callbacks of the turns the runtime was handed, in order.
     */
    private final List<IRuntime.IFinishedExecution<Object>> callbacks = new ArrayList<>();

    /** A cluster lease whose expiry the test controls. */
    private static final class TestLease implements LeaseHandle {
        private final Long fence;
        volatile boolean lost;

        TestLease(Long fence) {
            this.fence = fence;
        }

        @Override
        public String key() {
            return "c." + CONVERSATION_ID;
        }

        @Override
        public String conversationId() {
            return CONVERSATION_ID;
        }

        @Override
        public Long fence() {
            return fence;
        }

        @Override
        public boolean isLost() {
            return lost;
        }

        @Override
        public void onLost(Runnable callback) {
            // A lease that expires after its turn was released calls nobody back.
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        doReturn(new HashMap<String, String>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
        doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(snapshot()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(new StateAndRevision(ConversationState.READY, 1L)).when(conversationMemoryStore).getStateAndRevision(CONVERSATION_ID);
        // READY throughout: the watchdog reads it, and later it is what the next turn
        // committed.
        doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        IConversation conversation = mock(IConversation.class);
        lenient().doReturn(false).when(conversation).isEnded();
        doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

        // The first turn never finishes (its body hangs with a swallowed interrupt);
        // every later turn runs inline and completes.
        doAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(0);
            IRuntime.IFinishedExecution<Object> callback = invocation.getArgument(1);
            callbacks.add(callback);
            if (callbacks.size() == 1) {
                return new CompletableFuture<>();
            }
            Object result = callable.call();
            callback.onComplete(result);
            return CompletableFuture.completedFuture(result);
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());

        // agentTimeout 0: the watchdog gives up on a turn that has not finished at
        // once.
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization, new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(),
                new CallerIdentityContext(null, null), 0);
    }

    @Test
    @DisplayName("single node: the late outcome of an abandoned turn does not stamp EXECUTION_INTERRUPTED over the next turn's READY")
    void lateOutcomeLeavesTheNextTurnsStateAlone() throws Exception {
        ConversationStepRunner.TurnTask abandoned = submitTurn(1);
        assertDoesNotThrow(abandoned::call, "the watchdog gives up and the task ends");
        // The watchdog recorded the abandoned turn's state; the next turn has run and
        // committed READY since.
        clearInvocations(conversationMemoryStore, conversationStateCache);

        callbacks.get(0).onFailure(new ExecutionAbandonedException("abandoned by the watchdog"));

        verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), any());
        verify(conversationMemoryStore, never()).setConversationState(anyString(), any());
        verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.READY);
    }

    @Test
    @DisplayName("single node: a late completion of an abandoned turn is inert as well")
    void lateCompletionIsInert() throws Exception {
        assertDoesNotThrow(submitTurn(1)::call);
        clearInvocations(conversationMemoryStore);

        callbacks.get(0).onComplete(null);

        verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), any());
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
    }

    @Test
    @DisplayName("cluster: the late outcome of an abandoned turn whose lease has since expired does not dead-letter the next turn")
    void lateOutcomeDoesNotDeadLetterTheNextTurn() throws Exception {
        ConversationStepRunner.TurnTask abandoned = submitTurn(1);
        TestLease firstLease = new TestLease(42L);
        abandoned.bindLease(firstLease);
        assertDoesNotThrow(abandoned::call);
        // Released with the task; nothing renews it, so its validity runs out.
        firstLease.lost = true;
        clearInvocations(conversationMemoryStore);

        callbacks.get(0).onFailure(new ExecutionAbandonedException("abandoned by the watchdog"));

        ConversationStepRunner.TurnTask next = submitTurn(2);
        next.bindLease(new TestLease(43L));
        assertDoesNotThrow(next::call, "the next turn answered and committed; it must not be dead-lettered as lease-lost");
        verify(conversationMemoryStore, never()).compareAndSetState(CONVERSATION_ID, ConversationState.READY,
                ConversationState.EXECUTION_INTERRUPTED);
    }

    /** Submits a say and returns the coordinator's task for it (the n-th one). */
    private ConversationStepRunner.TurnTask submitTurn(int n) throws Exception {
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(), new InputData("message " + n, Map.of()), false,
                mock(ConversationResponseHandler.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator, times(n)).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        List<Callable<Void>> tasks = captor.getAllValues();
        assertEquals(n, tasks.size());
        return (ConversationStepRunner.TurnTask) tasks.get(n - 1);
    }

    private static ConversationMemorySnapshot snapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId("user-abandoned");
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setEnvironment(ENV);
        snapshot.setRevision(1L);
        return snapshot;
    }
}
