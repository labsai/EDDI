/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
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
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.ConversationTooLargeException;
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
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.TurnError;
import ai.labs.eddi.engine.runtime.BaseRuntime;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IDescribedTask;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.internal.Agent;
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
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemorySnapshot;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * A1/A2/A5 regression: a turn whose conversation write fails is never reported
 * as done, and nothing it deferred until its write runs. Store failures used to
 * be mocked only as {@code ResourceStoreException}; the live failures were
 * unchecked driver exceptions (a MongoDB document over 16 MB), which bypassed
 * the failure handling, and the caller had its 200/READY long before the write
 * was even attempted.
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
    private IConversationSetup conversationSetup;
    private GdprComplianceService gdprComplianceService;
    private TenantQuotaService tenantQuotaService;
    private IConversation conversation;
    private SimpleMeterRegistry meterRegistry;
    private BaseRuntime runtime;
    private ExecutorService pool;
    private ConversationService conversationService;
    private TurnIdempotencyService turnIdempotency;
    private IAgent agent;

    /**
     * The order things happened in: "store attempted"/"stored", "long-term write",
     * "delivered".
     */
    private final List<String> events = new ArrayList<>();

    /** Extra work the stub pipeline does on the turn's memory before it renders. */
    private Consumer<IConversationMemory> pipelineWork = memory -> {
    };

    /**
     * Work the stub pipeline does after it rendered its output (the answer is out).
     */
    private Consumer<IConversationMemory> afterRender = memory -> {
    };

    /**
     * What the stub pipeline throws after it rendered its output, or {@code null}.
     */
    private LifecycleException pipelineFailure;

    @BeforeEach
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);
        userMemoryStore = mock(IUserMemoryStore.class);
        conversationSetup = mock(IConversationSetup.class);
        gdprComplianceService = mock(GdprComplianceService.class);
        tenantQuotaService = mock(TenantQuotaService.class);
        conversation = mock(IConversation.class);
        meterRegistry = new SimpleMeterRegistry();

        runtime = new BaseRuntime("TestProject", "1.0.0");
        pool = Executors.newFixedThreadPool(2);
        ManagedExecutor managedExecutor = mock(ManagedExecutor.class);
        when(managedExecutor.submit(any(Callable.class))).thenAnswer(inv -> pool.submit((Callable<?>) inv.getArgument(0)));
        var executorField = BaseRuntime.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        executorField.set(runtime, managedExecutor);

        conversationService = service(10);

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
                memory.getCurrentStep().addConversationOutputString("output", "the answer");
                pipelineWork.accept(memory);
                memory.deferUntilCommitted(() -> {
                    events.add("long-term write");
                    userMemoryStore.upsert(null);
                    // What storePropertiesPermanently does once the store took the keys.
                    memory.setPendingLongTermWrites(new LinkedHashSet<>());
                });
                renderer.renderOutput(memory);
                afterRender.accept(memory);
                if (pipelineFailure != null) {
                    throw pipelineFailure;
                }
                return null;
            }).when(conversation).say(anyString(), anyMap());
            return conversation;
        });
        when(conversation.isEnded()).thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private ConversationService service(int agentTimeoutSeconds) {
        var cacheFactory = mock(ICacheFactory.class);
        var conversationStateCache = (ICache<String, ConversationState>) mock(ICache.class);
        var contextLogger = mock(IContextLogger.class);
        var auditLedgerService = mock(AuditLedgerService.class);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);
        var service = new ConversationService(agentFactory, conversationMemoryStore,
                mock(IConversationDescriptorStore.class), userMemoryStore, conversationCoordinator,
                conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, mock(IScheduleStore.class), mock(IAgentStore.class),
                mock(IJsonSerialization.class), meterRegistry,
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), agentTimeoutSeconds);
        turnIdempotency = spy(new TurnIdempotencyService(600, 10, meterRegistry, System::currentTimeMillis));
        service.turnIdempotency = turnIdempotency;
        return service;
    }

    @AfterEach
    void tearDown() {
        runtime.getScheduledExecutorService().shutdownNow();
        pool.shutdownNow();
    }

    // ─── the write fails ───

    @Test
    @Timeout(30)
    @DisplayName("an unchecked store exception: no READY answer, no answer at all, the turn forgotten, ERROR recorded, nothing written to memory")
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
        assertFalse(error.retryable(), "not definitive: a blind retry could run the turn twice");
        assertTrue(error.message().contains("may not have been stored"), error.message());
        assertFalse(error.message().contains("db-7"), "no driver text reaches the caller");
        assertTrue(answer.getConversationOutputs().isEmpty(), "S4: the unstored answer is not delivered on any surface");
        verify(turnIdempotency).forgetUnpersisted(CONVERSATION_ID);
        verify(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.READY, ConversationState.ERROR);
        assertEquals(1.0, meterRegistry.counter("eddi.turn.not_stored", "reason", "store_failure").count());
        verify(userMemoryStore, never()).upsert(any());
        assertEquals(1.0, meterRegistry.counter("eddi.turn.long_term_writes", "outcome", "discarded").count(),
                "the dropped long-term write is visible");
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
    @DisplayName("a revision conflict is 'conflict' (409, retryable); the stored state is left to the winner")
    void conflictIsReportedAsConflict() throws Exception {
        when(conversationMemoryStore.storeConversationMemorySnapshot(any()))
                .thenThrow(new ConcurrentConversationModificationException(CONVERSATION_ID, 3L));

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals("conflict", answer.getError().errorType());
        assertTrue(answer.getError().retryable());
        assertEquals(409, TurnNotStored.reasonOf(answer.getError()).httpStatus());
        verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), eq(ConversationState.ERROR));
        verify(userMemoryStore, never()).upsert(any());
    }

    @Test
    @Timeout(30)
    @DisplayName("S8: a write refused because the conversation was ended meanwhile is 'conversation_ended' (410), not a conflict")
    void conflictWithAnEndIsConversationEnded() throws Exception {
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenAnswer(inv -> {
            when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.ENDED);
            throw new ConcurrentConversationModificationException(CONVERSATION_ID, 3L);
        });

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals("conversation_ended", answer.getError().errorType());
        assertEquals(410, TurnNotStored.reasonOf(answer.getError()).httpStatus());
    }

    @Test
    @Timeout(30)
    @DisplayName("S2: a turn whose lease was lost is discarded and answered 'fenced' — not 200 — and its dead letter is not replayable")
    void leaseLostTurnIsReportedAsFenced() throws Exception {
        // Lost after the answer was rendered, before the write: the turn is discarded.
        afterRender = memory -> memory.setLeaseLostCheck(() -> true);

        AtomicReference<SimpleConversationMemorySnapshot> delivered = new AtomicReference<>();
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("hello", Map.of()), false,
                handler(delivered));
        Callable<Void> task = runQueuedTurn();

        assertNotNull(delivered.get());
        assertEquals("fenced", delivered.get().getError().errorType());
        assertTrue(delivered.get().getConversationOutputs().isEmpty());
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        verify(userMemoryStore, never()).upsert(any());
        Map<String, Object> deadLetter = ((IDescribedTask) task).describe();
        assertEquals(Boolean.TRUE, deadLetter.get(DeadLetterEntry.TURN_ANSWERED_RETRY),
                "S3: the caller was told to send it again, so a replay would run it twice");
    }

    @Test
    @Timeout(30)
    @DisplayName("S1: a write that outlasts the watchdog is answered 'outcome_unknown', never as done")
    void writeOutlastingTheWatchdogIsNeverDone() throws Exception {
        conversationService = service(1);
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenAnswer(inv -> {
            long until = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException swallowed) {
                    // a driver that swallows the interrupt: the write goes on
                }
            }
            return CONVERSATION_ID;
        });

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals(ConversationState.ERROR, answer.getConversationState());
        assertEquals("outcome_unknown", answer.getError().errorType());
        assertTrue(answer.getError().message().contains("may not have been stored"), answer.getError().message());
        assertTrue(answer.getConversationOutputs().isEmpty());
    }

    // ─── the size limit ───

    @Test
    @Timeout(30)
    @DisplayName("A2: a turn that would grow the document over the limit is refused at the write, before the database is asked")
    void sizeGuardRefusesTheWrite() throws Exception {
        conversationService.maxConversationDocumentBytes = 8192;
        pipelineWork = memory -> memory.getCurrentStep().addConversationOutputString("bigOutput", "x".repeat(16_000));

        SimpleConversationMemorySnapshot answer = runSay();

        verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        assertEquals(ConversationState.ERROR, answer.getConversationState());
        assertEquals("conversation_too_large", answer.getError().errorType());
        verify(userMemoryStore, never()).upsert(any());
        verify(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.READY, ConversationState.ERROR);
    }

    @Test
    @Timeout(30)
    @DisplayName("S6: a conversation already over the limit is refused at admission — the pipeline never runs")
    void conversationAlreadyOverTheLimitIsRefusedAtAdmission() throws Exception {
        conversationService.maxConversationDocumentBytes = 2048;
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(storedSnapshotWithText("x".repeat(4096)));

        assertThrows(ConversationTooLargeException.class, () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false,
                List.of(), new InputData("hello", Map.of()), false, mock(ConversationResponseHandler.class)));
        verify(conversationCoordinator, never()).submitInOrder(anyString(), any());
        verify(conversation, never()).say(anyString(), anyMap());
    }

    @Test
    @DisplayName("S6: undo on a conversation over the limit still works — undo does not grow the document")
    void undoIsNotSizeGuarded() throws Exception {
        conversationService.maxConversationDocumentBytes = 2048;
        var big = storedSnapshotWithText("x".repeat(4096));
        big.getConversationSteps().add(big.getConversationSteps().getFirst());
        big.getConversationOutputs().add(big.getConversationOutputs().getFirst());
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(big);
        when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY))).thenReturn(true);

        assertTrue(conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID));
    }

    // ─── the write succeeds ───

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
        assertFalse(answer.getConversationOutputs().isEmpty());
        assertEquals(List.of("stored", "long-term write", "delivered"), events);
        verify(userMemoryStore).upsert(any());
        verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), eq(ConversationState.ERROR));
    }

    @Test
    @Timeout(30)
    @DisplayName("S5: the stored document keeps the owed keys until the long-term write succeeded, then a second write clears them")
    void owedMarkersAreStoredThenClearedAfterTheWrite() throws Exception {
        pipelineWork = memory -> memory.setPendingLongTermWrites(new LinkedHashSet<>(Set.of("favorite_color")));
        ArgumentCaptor<ConversationMemorySnapshot> first = ArgumentCaptor.forClass(ConversationMemorySnapshot.class);
        when(conversationMemoryStore.storeConversationMemorySnapshot(first.capture())).thenReturn(CONVERSATION_ID);
        ArgumentCaptor<ConversationMemorySnapshot> clearing = ArgumentCaptor.forClass(ConversationMemorySnapshot.class);
        when(conversationMemoryStore.storeConversationMemorySnapshotIfState(clearing.capture(), eq(ConversationState.READY))).thenReturn(true);

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals(ConversationState.READY, answer.getConversationState());
        assertEquals(Set.of("favorite_color"), first.getValue().getPendingLongTermWrites(),
                "the turn's own write still names what it owes the user memory store");
        assertTrue(clearing.getValue().getPendingLongTermWrites().isEmpty(), "cleared only once the user memory write succeeded");
        InOrder order = inOrder(conversationMemoryStore, userMemoryStore);
        order.verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        order.verify(userMemoryStore).upsert(any());
        order.verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
    }

    @Test
    @Timeout(30)
    @DisplayName("S5: a failing long-term write leaves a stored turn stored and READY; the owed keys stay recorded for the next turn")
    void failingLongTermWriteKeepsTheTurnStored() throws Exception {
        pipelineWork = memory -> memory.setPendingLongTermWrites(new LinkedHashSet<>(Set.of("favorite_color")));
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenReturn(CONVERSATION_ID);
        when(userMemoryStore.upsert(any())).thenThrow(new IllegalStateException("user memory store down"));

        SimpleConversationMemorySnapshot answer = runSay();

        assertEquals(ConversationState.READY, answer.getConversationState(), "a stored turn is reported as stored");
        assertNull(answer.getError());
        verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), eq(ConversationState.ERROR));
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshotIfState(any(), any());
        assertEquals(1.0, meterRegistry.counter("eddi.turn.long_term_writes", "outcome", "failed").count());
    }

    @Test
    @Timeout(30)
    @DisplayName("resume's commit point (the conditional store) runs the deferred long-term write after a successful write")
    void conditionalStoreRunsTheDeferredWrite() throws Exception {
        var memory = convertConversationMemorySnapshot(storedSnapshot(1));
        memory.setConversationState(ConversationState.READY);
        memory.deferUntilCommitted(() -> userMemoryStore.upsert(null));
        when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS))).thenReturn(true);

        assertTrue(conversationService.storeConversationMemoryIfState(memory, ENV, ConversationState.IN_PROGRESS));

        verify(userMemoryStore).upsert(any());
        assertNull(memory.takeAfterCommit());
    }

    // ─── M1: the conversation's start turn ───

    @Test
    @Timeout(30)
    @DisplayName("M1: a long-term property set on the CONVERSATION_START turn is written to user memory once the conversation is stored")
    void startTurnLongTermPropertyIsWritten() throws Exception {
        var startAgent = new Agent(AGENT_ID, 1);
        IExecutableWorkflow workflow = mock(IExecutableWorkflow.class);
        ILifecycleManager lifecycle = mock(ILifecycleManager.class);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(workflow.getLifecycleManager()).thenReturn(lifecycle);
        AtomicReference<IConversationMemory> started = new AtomicReference<>();
        doAnswer(inv -> {
            IConversationMemory memory = inv.getArgument(0);
            started.set(memory);
            memory.getConversationProperties().put("favorite_color",
                    new Property("favorite_color", "green", Property.Scope.longTerm));
            return null;
        }).when(lifecycle).executeLifecycle(any(), any());
        startAgent.addWorkflow(workflow);
        when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(startAgent);
        when(conversationSetup.computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull())).thenReturn(USER_ID);
        when(conversationSetup.computeAnonymousUserIdIfEmpty(eq(USER_ID), any())).thenReturn(USER_ID);
        when(tenantQuotaService.acquireConversationSlot()).thenReturn(new QuotaCheckResult(true, null));
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenAnswer(inv -> {
            events.add("stored");
            return CONVERSATION_ID;
        });
        when(userMemoryStore.upsert(any())).thenAnswer(inv -> {
            events.add("long-term write");
            return "entry-1";
        });

        conversationService.startConversation(ENV, AGENT_ID, USER_ID, new LinkedHashMap<>());

        verify(userMemoryStore).upsert(argThat((UserMemoryEntry entry) -> "favorite_color".equals(entry.key())));
        assertEquals(List.of("stored", "long-term write"), events, "written after, and only because, the conversation was stored");
        assertNull(started.get().takeAfterCommit(), "nothing left behind for a committer that never comes");
    }

    // ─── delivery order and surfaces ───

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
    @Timeout(30)
    @DisplayName("a streamed pipeline failure is reported after the turn's own (not-stored) terminal event, never before it")
    void pipelineFailureFollowsTheNotStoredEvent() throws Exception {
        pipelineFailure = new LifecycleException("task blew up");
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenThrow(new IllegalStateException("driver"));
        StreamingResponseHandler handler = mock(StreamingResponseHandler.class);

        conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("hello", Map.of()), handler);
        runQueuedTurn();

        ArgumentCaptor<Throwable> errors = ArgumentCaptor.forClass(Throwable.class);
        verify(handler, times(2)).onError(errors.capture());
        assertInstanceOf(TurnNotStored.TurnNotStoredException.class, errors.getAllValues().get(0));
        assertInstanceOf(LifecycleException.class, errors.getAllValues().get(1));
    }

    @Test
    @DisplayName("the sync REST answer to a not-stored turn: the reason's status, the error body, Retry-After only when retrying is safe")
    void restAnswerOfANotStoredTurn() {
        var tooLarge = RestAgentEngine.notStoredResponse(TurnNotStored.Reason.CONVERSATION_TOO_LARGE.error());
        assertEquals(507, tooLarge.getStatus());
        assertNull(tooLarge.getHeaderString("Retry-After"));
        assertEquals(Map.of("error", TurnNotStored.Reason.CONVERSATION_TOO_LARGE.error()), tooLarge.getEntity());

        assertEquals(422, RestAgentEngine.notStoredResponse(TurnNotStored.Reason.DATA_REJECTED.error()).getStatus());
        assertEquals(410, RestAgentEngine.notStoredResponse(TurnNotStored.Reason.CONVERSATION_ENDED.error()).getStatus());
        var conflict = RestAgentEngine.notStoredResponse(TurnNotStored.Reason.CONFLICT.error());
        assertEquals(409, conflict.getStatus());
        assertEquals("2", conflict.getHeaderString("Retry-After"));
        var storeFailure = RestAgentEngine.notStoredResponse(TurnNotStored.Reason.STORE_FAILURE.error());
        assertEquals(503, storeFailure.getStatus());
        assertNull(storeFailure.getHeaderString("Retry-After"), "it may have been stored: no invitation to retry blindly");
        assertEquals(503, RestAgentEngine.notStoredResponse(TurnNotStored.Reason.OUTCOME_UNKNOWN.error()).getStatus());
    }

    // ─── block 4 ───

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

    private ConversationResponseHandler handler(AtomicReference<SimpleConversationMemorySnapshot> delivered) {
        return new ConversationResponseHandler() {
            @Override
            public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                events.add("delivered");
                delivered.set(snapshot);
            }

            @Override
            public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                events.add("skipped");
            }
        };
    }

    private SimpleConversationMemorySnapshot runSay() throws Exception {
        AtomicReference<SimpleConversationMemorySnapshot> delivered = new AtomicReference<>();
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("hello", Map.of()), false,
                handler(delivered));
        runQueuedTurn();
        assertNotNull(delivered.get(), "the caller is always answered");
        return delivered.get();
    }

    @SuppressWarnings("unchecked")
    private Callable<Void> runQueuedTurn() throws Exception {
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        try {
            captor.getValue().call();
        } catch (Exception ignored) {
            // A failed turn may surface through the task; the answer is what is checked.
        }
        return captor.getValue();
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
