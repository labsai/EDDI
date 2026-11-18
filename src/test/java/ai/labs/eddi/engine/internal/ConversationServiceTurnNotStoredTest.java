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
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.TurnError;
import ai.labs.eddi.engine.runtime.BaseRuntime;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import com.mongodb.MongoException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A1/A2 regression: a turn whose conversation write fails is never reported as
 * done. Store failures used to be mocked only as
 * {@code ResourceStoreException}; the live failures were unchecked driver
 * exceptions (a MongoDB document over 16 MB), which bypassed the failure
 * handling, and the caller had its 200/READY long before the write was even
 * attempted.
 * <p>
 * Wires the real {@link BaseRuntime}, so the completion callback that writes
 * the turn runs exactly as in production.
 */
class ConversationServiceTurnNotStoredTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "not-stored-agent";
    private static final String CONVERSATION_ID = "aabbccddeeff112233445599";
    private static final String USER_ID = "not-stored-user";

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationCoordinator conversationCoordinator;
    private IUserMemoryStore userMemoryStore;
    private IConversation conversation;
    private SimpleMeterRegistry meterRegistry;
    private BaseRuntime runtime;
    private ExecutorService pool;
    private ConversationService conversationService;
    private TurnIdempotencyService turnIdempotency;
    private IAgent agent;

    /** The order things happened in: "delivered" and "stored". */
    private final List<String> events = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);
        userMemoryStore = mock(IUserMemoryStore.class);
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
                mock(IConversationDescriptorStore.class), userMemoryStore, conversationCoordinator,
                mock(IConversationSetup.class), cacheFactory, runtime, contextLogger, auditLedgerService,
                mock(GdprComplianceService.class), tenantQuotaService, mock(IScheduleStore.class), mock(IAgentStore.class),
                mock(IJsonSerialization.class), meterRegistry,
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 10);
        turnIdempotency = spy(new TurnIdempotencyService(600, 10, meterRegistry, System::currentTimeMillis));
        conversationService.turnIdempotency = turnIdempotency;

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshot(1));
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        when(conversationMemoryStore.compareAndSetState(anyString(), any(), any())).thenReturn(true);
        agent = mock(IAgent.class);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(agent.getAgentVersion()).thenReturn(1);
        // The pipeline: settles the turn READY, defers a long-term memory write until
        // the turn is stored (as Conversation does), then renders its output — which
        // used to answer the caller before the write.
        when(agent.continueConversation(any(), any(), any())).thenAnswer(inv -> {
            IConversationMemory memory = inv.getArgument(0);
            IConversationOutputRenderer renderer = inv.getArgument(2);
            doAnswer(say -> {
                memory.setConversationState(ConversationState.READY);
                memory.deferUntilCommitted(() -> {
                    events.add("long-term write");
                    userMemoryStore.upsert(null);
                });
                renderer.renderOutput(memory);
                return null;
            }).when(conversation).say(anyString(), anyMap());
            return conversation;
        });
        when(conversation.isEnded()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        runtime.getScheduledExecutorService().shutdownNow();
        pool.shutdownNow();
    }

    @Test
    @Timeout(30)
    @DisplayName("an unchecked store exception: no READY answer, the turn is forgotten, ERROR recorded, the caller told TURN_NOT_STORED")
    void uncheckedStoreFailureIsReportedAsNotStored() throws Exception {
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenAnswer(inv -> {
            events.add("store attempted");
            throw new IllegalStateException("driver exploded: host db-7.internal, collection conversationmemories");
        });

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals(ConversationState.ERROR, answer.getConversationState(), "never READY for a turn that is not stored");
        TurnError error = answer.getError();
        assertNotNull(error);
        assertEquals(TurnNotStored.ERROR_CODE, error.code());
        assertEquals("store_failure", error.errorType());
        assertTrue(error.retryable());
        assertFalse(error.message().contains("db-7"), "no driver text reaches the caller");
        verify(turnIdempotency).forgetUnpersisted(CONVERSATION_ID);
        verify(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.READY, ConversationState.ERROR);
        assertEquals(1.0, meterRegistry.counter("eddi.turn.not_stored", "reason", "store_failure").count());
        verify(userMemoryStore, never()).upsert(any());
        assertEquals(List.of("store attempted", "delivered"), events, "answered after the write, and its memory write never ran");
    }

    @Test
    @Timeout(30)
    @DisplayName("MongoDB 'document too large' (BSONObjectTooLarge) is reported as conversation_too_large, not retryable")
    void mongoDocumentTooLargeIsTyped() throws Exception {
        when(conversationMemoryStore.storeConversationMemorySnapshot(any()))
                .thenThrow(new MongoException(10334, "BSONObj size: 31464793 (0x1E01F59) is invalid. Size must be between 0 and 16793600"));

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals(ConversationState.ERROR, answer.getConversationState());
        assertEquals("conversation_too_large", answer.getError().errorType());
        assertFalse(answer.getError().retryable());
        assertFalse(answer.getError().message().contains("BSONObj"));
        verify(turnIdempotency).forgetUnpersisted(CONVERSATION_ID);
        verify(userMemoryStore, never()).upsert(any());
        assertEquals(1.0, meterRegistry.counter("eddi.turn.not_stored", "reason", "conversation_too_large").count());
    }

    @Test
    @Timeout(30)
    @DisplayName("A2: the size guard refuses the write before the database is asked, on any backend")
    void sizeGuardRefusesBeforeTheWrite() throws Exception {
        conversationService.maxConversationDocumentBytes = 2048;
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshotWithText("x".repeat(4096)));

        SimpleConversationMemorySnapshot answer = runSay();

        verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        assertEquals(ConversationState.ERROR, answer.getConversationState());
        assertEquals("conversation_too_large", answer.getError().errorType());
        verify(userMemoryStore, never()).upsert(any());
        verify(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.READY, ConversationState.ERROR);
    }

    @Test
    @Timeout(30)
    @DisplayName("a stored turn is delivered after its write, as READY, and its deferred long-term write runs after the write")
    void storedTurnIsDeliveredAfterTheWrite() throws Exception {
        conversationService.maxConversationDocumentBytes = 14_680_064;
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenAnswer(inv -> {
            events.add("stored");
            return CONVERSATION_ID;
        });

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals(ConversationState.READY, answer.getConversationState());
        assertNull(answer.getError());
        assertEquals(List.of("stored", "long-term write", "delivered"), events);
        verify(userMemoryStore).upsert(any());
        verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), eq(ConversationState.ERROR));
    }

    @Test
    @Timeout(30)
    @DisplayName("A5: a failing long-term write after a stored turn marks the conversation ERROR, so the next turn rewrites it")
    void failingLongTermWriteMarksTheConversationError() throws Exception {
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenReturn(CONVERSATION_ID);
        when(userMemoryStore.upsert(any())).thenThrow(new IllegalStateException("user memory store down"));

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals(ConversationState.ERROR, answer.getConversationState());
        assertNotNull(answer.getError());
        verify(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.READY, ConversationState.ERROR);
    }

    @Test
    @Timeout(30)
    @DisplayName("a streamed turn that is not stored ends with an error, never with done")
    void streamedTurnNotStoredEndsWithAnError() throws Exception {
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenThrow(new IllegalStateException("driver"));
        StreamingResponseHandler handler = mock(StreamingResponseHandler.class);

        conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("hello", Map.of()), handler);
        runQueuedTurn();

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(handler).onError(error.capture());
        var notStored = assertInstanceOf(TurnNotStored.TurnNotStoredException.class, error.getValue());
        assertEquals("store_failure", notStored.getError().errorType());
        verify(handler, never()).onComplete(any());
    }

    @Test
    @DisplayName("the sync REST answer to a not-stored turn: the reason's status, the error body, Retry-After when retrying helps")
    void restAnswerOfANotStoredTurn() {
        var tooLarge = RestAgentEngine.notStoredResponse(TurnNotStored.Reason.CONVERSATION_TOO_LARGE.error());
        assertEquals(507, tooLarge.getStatus());
        assertNull(tooLarge.getHeaderString("Retry-After"));
        assertEquals(Map.of("error", TurnNotStored.Reason.CONVERSATION_TOO_LARGE.error()), tooLarge.getEntity());

        assertEquals(422, RestAgentEngine.notStoredResponse(TurnNotStored.Reason.DATA_REJECTED.error()).getStatus());
        var conflict = RestAgentEngine.notStoredResponse(TurnNotStored.Reason.CONFLICT.error());
        assertEquals(409, conflict.getStatus());
        assertEquals("2", conflict.getHeaderString("Retry-After"));
        var storeFailure = RestAgentEngine.notStoredResponse(TurnNotStored.Reason.STORE_FAILURE.error());
        assertEquals(503, storeFailure.getStatus());
        assertEquals("5", storeFailure.getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("A12: a turn on an agent whose deployment failed is 'not ready' (404), not a 500 from the placeholder")
    void failedDeploymentIsNotReady() {
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.ERROR);

        assertThrows(AgentNotReadyException.class, () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, mock(ConversationResponseHandler.class)));
        verify(conversationCoordinator, never()).submitInOrder(anyString(), any());
    }

    @Test
    @DisplayName("A9: undo and redo leave an ENDED conversation alone")
    void undoRedoDoNotEditAnEndedConversation() throws Exception {
        var ended = storedSnapshot(2);
        ended.setConversationState(ConversationState.ENDED);
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(ended);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.ENDED);

        assertFalse(conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID));
        assertFalse(conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID));
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshotIfState(any(), any());
    }

    private SimpleConversationMemorySnapshot runSay() throws Exception {
        AtomicReference<SimpleConversationMemorySnapshot> delivered = new AtomicReference<>();
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("hello", Map.of()), false,
                new ConversationResponseHandler() {
                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        events.add("delivered");
                        delivered.set(snapshot);
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        events.add("skipped");
                    }
                });
        runQueuedTurn();
        assertNotNull(delivered.get(), "the caller is always answered");
        return delivered.get();
    }

    @SuppressWarnings("unchecked")
    private void runQueuedTurn() throws Exception {
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        try {
            captor.getValue().call();
        } catch (Exception ignored) {
            // A failed turn may surface through the task; the answer is what is checked.
        }
    }

    private static ConversationMemorySnapshot storedSnapshotWithText(String text) {
        var snapshot = storedSnapshot(1);
        snapshot.getConversationOutputs().getFirst().put("output", text);
        return snapshot;
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
