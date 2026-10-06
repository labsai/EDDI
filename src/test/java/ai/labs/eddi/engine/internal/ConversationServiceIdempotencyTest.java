/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.ConversationMemory;
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
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.TurnError;
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
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@code Idempotency-Key} through the real {@code say(conversationId, …)} path:
 * one key is one turn, however often the request arrives.
 */
class ConversationServiceIdempotencyTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-idem";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-idem-1";
    private static final String USER_ID = "user-idem";

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
    /** What the next pipeline run does to the memory before rendering. */
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
        doAnswer(inv -> {
            builtOver.add(inv.getArgument(0));
            renderers.add(inv.getArgument(2));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
        // The "pipeline": settles the memory in the configured state and renders.
        doAnswer(inv -> {
            IConversationMemory memory = builtOver.getLast();
            memory.setConversationState(outcomeState);
            if (outcomeState == ConversationState.ERROR) {
                var failure = new LinkedHashMap<String, Object>();
                failure.put("type", "errorDigest");
                failure.put(TurnError.KEY_CODE, "RATE_LIMITED");
                failure.put(TurnError.KEY_RETRYABLE, true);
                failure.put(TurnError.KEY_RETRY_AFTER_MS, 1500L);
                failure.put(TurnError.KEY_MESSAGE, "slow down");
                memory.getCurrentStep().addConversationOutputList(MemoryKeys.TASK_ERRORS, List.of(failure));
            }
            renderers.getLast().renderOutput(memory);
            return null;
        }).when(conversation).say(anyString(), any());
        stubRuntimeInline();

        conversationService = new ConversationService(agentFactory, conversationMemoryStore, conversationDescriptorStore, userMemoryStore,
                conversationCoordinator, conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore, jsonSerialization, new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
        conversationService.turnIdempotency = service(600);
    }

    private TurnIdempotencyService service(long ttlSeconds) {
        return new TurnIdempotencyService(ttlSeconds, 30, new SimpleMeterRegistry(), now::get);
    }

    @Test
    @DisplayName("a duplicate that arrives while the turn runs gets that turn's answer; the pipeline runs once")
    void duplicateDuringTheTurn() throws Exception {
        ConversationResponseHandler first = mock(ConversationResponseHandler.class);
        ConversationResponseHandler duplicate = mock(ConversationResponseHandler.class);

        say("k-1", first);
        say("k-1", duplicate);
        runQueuedTurns(1);

        verify(conversation, times(1)).say(anyString(), any());
        var answered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
        verify(first).onComplete(answered.capture());
        var duplicated = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
        verify(duplicate, timeout(2000)).onComplete(duplicated.capture());
        assertSame(answered.getValue(), duplicated.getValue(), "the duplicate gets the very answer of the running turn");
        verify(duplicate, never()).onSkipped(any());
    }

    @Test
    @DisplayName("a duplicate after completion is answered from the stored step, without a second run — also on another node")
    void duplicateAfterCompletionIsServedFromTheStoredStep() throws Exception {
        say("k-2", mock(ConversationResponseHandler.class));
        runQueuedTurns(1);
        ConversationMemorySnapshot persisted = persistedSnapshot();
        assertTrue(hasStoredKey(persisted, "k-2"), "the key is recorded on the persisted step");

        // Another node (or a restart): no in-memory state, only the store.
        conversationService.turnIdempotency = service(600);
        doReturn(persisted).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        ConversationResponseHandler duplicate = mock(ConversationResponseHandler.class);
        say("k-2", duplicate);

        verify(duplicate, timeout(2000)).onComplete(any());
        verify(conversation, times(1)).say(anyString(), any());
        verify(conversationCoordinator, times(1)).submitInOrder(eq(CONVERSATION_ID), any());
    }

    @Test
    @DisplayName("a duplicate right after completion on the same node is served before the persist is visible")
    void duplicateInTheGraceWindow() throws Exception {
        say("k-3", mock(ConversationResponseHandler.class));
        runQueuedTurns(1);
        // The store still holds the OLD document: only the on-node copy can answer.
        doReturn(snapshot(1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        ConversationResponseHandler duplicate = mock(ConversationResponseHandler.class);
        say("k-3", duplicate);

        verify(duplicate).onComplete(any());
        verify(conversation, times(1)).say(anyString(), any());
    }

    @Test
    @DisplayName("a different key runs a new turn")
    void differentKeyRunsANewTurn() throws Exception {
        say("k-a", mock(ConversationResponseHandler.class));
        say("k-b", mock(ConversationResponseHandler.class));
        runQueuedTurns(2);

        verify(conversation, times(2)).say(anyString(), any());
    }

    @Test
    @DisplayName("without a key every request is a turn")
    void noKeyNoDeduplication() throws Exception {
        say(null, mock(ConversationResponseHandler.class));
        say(null, mock(ConversationResponseHandler.class));
        verify(conversationCoordinator, times(2)).submitInOrder(eq(CONVERSATION_ID), any());
    }

    @Test
    @DisplayName("the same key is a new turn once the TTL has passed")
    void expiredKeyRunsANewTurn() throws Exception {
        say("k-4", mock(ConversationResponseHandler.class));
        runQueuedTurns(1);
        ConversationMemorySnapshot persisted = persistedSnapshot();

        conversationService.turnIdempotency = service(600);
        // The stored step was stamped at wall-clock time, so age it by rewriting the
        // stamp.
        age(persisted, 601_000L + 60_000L);
        doReturn(persisted).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        say("k-4", mock(ConversationResponseHandler.class));

        verify(conversationCoordinator, times(2)).submitInOrder(eq(CONVERSATION_ID), any());
    }

    @Test
    @DisplayName("a failed turn carries a structured error and is NOT remembered: retrying the key runs it again")
    void failedTurnIsNotReplayed() throws Exception {
        outcomeState = ConversationState.ERROR;
        ConversationResponseHandler first = mock(ConversationResponseHandler.class);
        say("k-5", first);
        runQueuedTurns(1);

        var answered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
        verify(first).onComplete(answered.capture());
        TurnError error = answered.getValue().getError();
        assertNotNull(error);
        assertEquals("RATE_LIMITED", error.code());
        assertTrue(error.retryable());
        assertEquals(1500L, error.retryAfterMs());
        assertEquals(2L, error.retryAfterSeconds());
        assertFalse(hasStoredKey(persistedSnapshot(), "k-5"), "a failed turn leaves no key behind");

        outcomeState = ConversationState.READY;
        say("k-5", mock(ConversationResponseHandler.class));
        verify(conversationCoordinator, times(2)).submitInOrder(eq(CONVERSATION_ID), any());
    }

    @Test
    @DisplayName("a failed turn without a task entry still says why, generically")
    void failedTurnWithoutTaskEntry() {
        IConversationMemory memory = new ConversationMemory("a", 1, "u");
        memory.setConversationState(ConversationState.ERROR);
        memory.getCurrentStep();
        TurnError error = ConversationService.turnErrorOf(memory);
        assertEquals(TurnError.TURN_FAILED, error.code());
        assertFalse(error.retryable());
        assertNull(error.retryAfterMs());
    }

    @Test
    @DisplayName("a turn refused before it ran releases the key")
    void refusedTurnReleasesTheKey() throws Exception {
        doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        doThrow(new IllegalStateException("shutting down")).doNothing().when(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());

        assertThrows(IllegalStateException.class, () -> say("k-6", mock(ConversationResponseHandler.class)));
        say("k-6", mock(ConversationResponseHandler.class));

        verify(conversationCoordinator, times(2)).submitInOrder(eq(CONVERSATION_ID), any());
    }

    // ------------------------------------------------------------------

    private void say(String key, ConversationResponseHandler handler) throws Exception {
        var input = new InputData("hello", new HashMap<>());
        input.setIdempotencyKey(key);
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

    private static boolean hasStoredKey(ConversationMemorySnapshot snapshot, String key) {
        return snapshot.getConversationSteps().stream().flatMap(s -> s.getWorkflows().stream()).flatMap(w -> w.getLifecycleTasks().stream())
                .anyMatch(r -> TurnIdempotencyService.STEP_KEY.equals(r.getKey()) && key.equals(r.getResult()));
    }

    private static void age(ConversationMemorySnapshot snapshot, long ms) {
        snapshot.getConversationSteps().stream().flatMap(s -> s.getWorkflows().stream()).flatMap(w -> w.getLifecycleTasks().stream())
                .filter(r -> TurnIdempotencyService.STEP_KEY.equals(r.getKey()))
                .forEach(r -> r.setTimestamp(new Date(r.getTimestamp().getTime() - ms)));
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
