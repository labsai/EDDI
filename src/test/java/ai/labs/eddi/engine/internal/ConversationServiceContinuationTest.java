/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.continuation.ContinuationFireHandler;
import ai.labs.eddi.engine.continuation.ContinuationPolicy;
import ai.labs.eddi.engine.continuation.ConversationContinuations;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.ConversationMemory;
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
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Self-continuation through the real {@code say(conversationId, …)} path: when
 * the next turn is armed (only after the commit), what the committed step says,
 * and that a re-delivered continuation runs once — through the stored document,
 * not through in-memory state.
 */
class ConversationServiceContinuationTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "0123456789abcdef01234567";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "0123456789abcdef89abcdef";
    private static final String USER_ID = "user-job";

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
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ConversationService conversationService;
    private final List<IConversationMemory> builtOver = new ArrayList<>();
    private final List<IConversationOutputRenderer> renderers = new ArrayList<>();

    /** What the agent's deployed version sets. */
    private ContinuationPolicy policy = new ContinuationPolicy(3, "go on");
    /** What the next pipeline run emits and how it settles. */
    private List<String> actions = List.of("work", IConversation.CONTINUE_CONVERSATION);
    private ConversationState outcomeState = ConversationState.READY;

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
        doReturn(60_000L).when(agent).getTurnDeadlineMs();
        doAnswer(inv -> {
            IConversationMemory memory = inv.getArgument(0);
            // What Agent.continueConversation does: the deployed version's settings.
            memory.setContinuationPolicy(policy);
            builtOver.add(memory);
            renderers.add(inv.getArgument(2));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
        // The "pipeline": emits the actions, settles, renders.
        doAnswer(inv -> {
            IConversationMemory memory = builtOver.getLast();
            // As Conversation.say does: the turn gets a step of its own.
            ((ConversationMemory) memory).startNextStep();
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ACTIONS.key(), new ArrayList<>(actions)));
            memory.setConversationState(outcomeState);
            renderers.getLast().renderOutput(memory);
            return null;
        }).when(conversation).say(anyString(), any());
        stubRuntimeInline();

        conversationService = new ConversationService(agentFactory, conversationMemoryStore, conversationDescriptorStore, userMemoryStore,
                conversationCoordinator, conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore, jsonSerialization, meters,
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
        conversationService.turnIdempotency = new TurnIdempotencyService(600, 30, new SimpleMeterRegistry(), now::get);
        conversationService.conversationContinuations = new ConversationContinuations(scheduleStore, meters);
    }

    @Nested
    @DisplayName("arming")
    class Arming {

        @Test
        @DisplayName("the next turn is armed only after the turn that asked for it was committed")
        void armedAfterTheCommit() throws Exception {
            say(new InputData("start the job", new HashMap<>()));
            runQueuedTurns(1);

            InOrder order = inOrder(conversationMemoryStore, scheduleStore);
            order.verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
            order.verify(scheduleStore).createSchedule(any());
            assertEquals(1, ((Number) stepValue(persistedSnapshot(), ConversationContinuations.STEP_NEXT)).intValue(),
                    "the committed step says which continuation it scheduled");

            ScheduleConfiguration armed = armedSchedule();
            assertEquals("go on", armed.getMessage());
            assertEquals(1, armed.getMetadata().get(ConversationContinuations.METADATA_NUMBER_KEY));
            assertEquals(USER_ID, armed.getUserId());
        }

        @Test
        @DisplayName("default off: nothing is armed and the committed step records why")
        void defaultOff() throws Exception {
            policy = ContinuationPolicy.OFF;

            say(new InputData("start the job", new HashMap<>()));
            runQueuedTurns(1);

            verify(scheduleStore, never()).createSchedule(any());
            assertEquals(ConversationContinuations.REASON_DISABLED, stepValue(persistedSnapshot(), ConversationContinuations.STEP_REFUSED));
        }

        @Test
        @DisplayName("a turn that pauses for a human arms nothing, whatever it emitted")
        void pausedTurn() throws Exception {
            outcomeState = ConversationState.AWAITING_HUMAN;
            doReturn(true).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), any());

            say(new InputData("start the job", new HashMap<>()));
            runQueuedTurns(1);

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("a turn whose commit failed arms nothing")
        void commitFailed() throws Exception {
            doThrow(new ResourceStoreException("down")).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

            say(new InputData("start the job", new HashMap<>()));
            runQueuedTurns(1);

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("a turn that failed arms nothing")
        void failedTurn() throws Exception {
            outcomeState = ConversationState.ERROR;

            say(new InputData("start the job", new HashMap<>()));
            runQueuedTurns(1);

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("ending the conversation withdraws its pending continuation")
        void endWithdraws() throws Exception {
            conversationService.endConversation(CONVERSATION_ID);

            verify(scheduleStore).deleteSchedulesByName(ConversationContinuations.scheduleName(CONVERSATION_ID));
        }
    }

    @Nested
    @DisplayName("the continuation turn")
    class ContinuationTurn {

        @Test
        @DisplayName("is marked with its number on the committed step, runs under the agent's turn deadline, and arms the next")
        void markedAndChained() throws Exception {
            say(continuationInput(1));
            runQueuedTurns(1);

            ConversationMemorySnapshot persisted = persistedSnapshot();
            assertTrue(ConversationContinuations.ranIn(persisted, 1));
            assertNotNull(builtOver.getLast().getTurnDeadline(), "every continuation is a turn with the agent's deadline");
            assertEquals(2, ((Number) stepValue(persisted, ConversationContinuations.STEP_NEXT)).intValue());
        }

        @Test
        @DisplayName("the last allowed continuation runs but arms no further one")
        void lastOneArmsNothing() throws Exception {
            doReturn(snapshotWithContinuations(2)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            say(continuationInput(3));
            runQueuedTurns(1);

            assertTrue(ConversationContinuations.ranIn(persistedSnapshot(), 3));
            verify(scheduleStore, never()).createSchedule(any());
            assertEquals(ConversationContinuations.REASON_LIMIT, stepValue(persistedSnapshot(), ConversationContinuations.STEP_REFUSED));
        }

        @Test
        @DisplayName("a failed continuation turn is not marked: its retry runs it again")
        void failedNotMarked() throws Exception {
            outcomeState = ConversationState.ERROR;

            say(continuationInput(1));
            runQueuedTurns(1);

            assertFalse(ConversationContinuations.ranIn(persistedSnapshot(), 1));
        }
    }

    @Nested
    @DisplayName("once only, through the store")
    class OnceOnly {

        @Test
        @DisplayName("fire twice (a stolen claim): the second fire reads the committed marker and the pipeline runs once")
        void fireTwiceRunsOnce() throws Exception {
            runCoordinatorInline();
            // A store that reads back what it was last given: the fire confirms the turn
            // committed by finding its marker there.
            var latest = new AtomicReference<>(snapshot(1));
            doAnswer(inv -> latest.get()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doAnswer(inv -> {
                latest.set(inv.getArgument(0));
                return CONVERSATION_ID;
            }).when(conversationMemoryStore).storeConversationMemorySnapshot(any());
            var handler = new ContinuationFireHandler(conversationService, conversationMemoryStore,
                    conversationService.conversationContinuations);
            var metadata = ConversationContinuations.metadata(CONVERSATION_ID, 1, 3);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata, USER_ID, "go on", Duration.ofSeconds(5)).status());
            // What the store now holds is what the first turn committed.
            doReturn(persistedSnapshot()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            // A fresh node: nothing in memory to remember the first fire by.
            conversationService.turnIdempotency = new TurnIdempotencyService(600, 30, new SimpleMeterRegistry(), now::get);
            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata, USER_ID, "go on", Duration.ofSeconds(5)).status());

            verify(conversation, times(1)).say(anyString(), any());
        }

        @Test
        @DisplayName("cluster: two deliveries queued before either ran — the lease serializes them and the second does not run")
        void leaseSerializesDuplicates() throws Exception {
            say(continuationInput(1));
            runQueuedTurns(1);
            ConversationMemorySnapshot committed = persistedSnapshot();
            committed.setRevision(2L);

            // The second delivery was admitted on another node from the document BEFORE
            // the first committed.
            conversationService.turnIdempotency = new TurnIdempotencyService(600, 30, new SimpleMeterRegistry(), now::get);
            doReturn(snapshot(1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            ConversationResponseHandler duplicate = mock(ConversationResponseHandler.class);
            conversationService.say(CONVERSATION_ID, false, true, List.of(), continuationInput(1), false, duplicate);

            // When it holds the lease the document has moved on to the first's commit.
            doReturn(committed).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(2L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator, times(2)).submitInOrder(eq(CONVERSATION_ID), captor.capture());
            captor.getAllValues().getLast().call();

            verify(conversation, times(1)).say(anyString(), any());
            verify(duplicate, timeout(2000)).onSkipped(any());
        }

        @Test
        @DisplayName("the lease check holds on the continuation marker alone, with the Idempotency-Key feature off")
        void leaseCheckWithoutIdempotencyKeys() throws Exception {
            conversationService.turnIdempotency = null;
            say(continuationInput(1));
            runQueuedTurns(1);
            ConversationMemorySnapshot committed = persistedSnapshot();
            committed.setRevision(2L);

            doReturn(snapshot(1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            ConversationResponseHandler duplicate = mock(ConversationResponseHandler.class);
            conversationService.say(CONVERSATION_ID, false, true, List.of(), continuationInput(1), false, duplicate);
            doReturn(committed).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(2L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator, times(2)).submitInOrder(eq(CONVERSATION_ID), captor.capture());
            captor.getAllValues().getLast().call();

            verify(conversation, times(1)).say(anyString(), any());
            verify(duplicate, timeout(2000)).onSkipped(any());
        }
    }

    // --- helpers
    // ------------------------------------------------------------------

    private static InputData continuationInput(int number) {
        return ContinuationFireHandler.inputData(number, 3, "go on");
    }

    private void say(InputData input) throws Exception {
        conversationService.say(CONVERSATION_ID, false, true, List.of(), input, false, mock(ConversationResponseHandler.class));
    }

    private void runQueuedTurns(int expected) throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator, times(expected)).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        for (Callable<Void> turn : captor.getAllValues()) {
            turn.call();
        }
    }

    private void runCoordinatorInline() throws Exception {
        doAnswer(inv -> {
            Callable<Void> turn = inv.getArgument(1);
            turn.call();
            return null;
        }).when(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
    }

    private ConversationMemorySnapshot persistedSnapshot() throws Exception {
        ArgumentCaptor<ConversationMemorySnapshot> stored = ArgumentCaptor.forClass(ConversationMemorySnapshot.class);
        verify(conversationMemoryStore, atLeastOnce()).storeConversationMemorySnapshot(stored.capture());
        return stored.getValue();
    }

    private ScheduleConfiguration armedSchedule() throws Exception {
        ArgumentCaptor<ScheduleConfiguration> captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(captor.capture());
        return captor.getValue();
    }

    private static Object stepValue(ConversationMemorySnapshot snapshot, String key) {
        return snapshot.getConversationSteps().getLast().getWorkflows().stream().flatMap(w -> w.getLifecycleTasks().stream())
                .filter(r -> key.equals(r.getKey())).map(ResultSnapshot::getResult).findFirst().orElse(null);
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

    private static ConversationMemorySnapshot snapshotWithContinuations(int ran) {
        var snapshot = snapshot(1);
        for (int i = 1; i <= ran; i++) {
            var step = new ConversationStepSnapshot();
            var workflowRun = new WorkflowRunSnapshot();
            workflowRun.getLifecycleTasks().add(new ResultSnapshot(ConversationContinuations.STEP_NUMBER, i, null, new Date(), null, false));
            step.getWorkflows().add(workflowRun);
            snapshot.getConversationSteps().add(step);
            snapshot.getConversationOutputs().add(new ConversationOutput());
        }
        return snapshot;
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
