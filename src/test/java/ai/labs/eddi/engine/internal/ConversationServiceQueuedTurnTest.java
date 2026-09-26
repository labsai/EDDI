/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.ConversationEndedException;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
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
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The turn that waited: a say() is loaded at request time and queued behind the
 * turns of the same conversation that were already running.
 * <ul>
 * <li><b>H13a</b> — when it finally runs, the turn must run on the conversation
 * as it is THEN, not on the snapshot it was loaded with.</li>
 * <li><b>E2</b> — a pause whose commit is refused must not stay in the state
 * cache.</li>
 * <li><b>M-E1</b> — an ended conversation is refused before the lazy
 * redeploy.</li>
 * </ul>
 */
class ConversationServiceQueuedTurnTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-queued";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-queued-1";
    private static final String USER_ID = "user-queued";

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

    /** Every memory a Conversation was built over, in order. */
    private final List<IConversationMemory> builtOver = new ArrayList<>();
    /** The renderer handed to each of those Conversations, in the same order. */
    private final List<IConversationOutputRenderer> renderers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        doReturn(new HashMap<String, String>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
        doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        // The snapshots below are loaded at revision 1; unless a test says otherwise
        // the
        // store still holds it, i.e. nothing committed while the turn was queued.
        doReturn(1L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        stubRuntimeInline();

        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization, new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(),
                new CallerIdentityContext(null, null), 30);
    }

    @Test
    @DisplayName("H13a: a queued turn whose snapshot was superseded runs on the current document")
    void queuedTurnRunsOnTheCurrentDocument() throws Exception {
        // Loaded at request time: revision 1, one step.
        var atRequest = snapshot(ConversationState.READY, 1L, 1);
        // What the turn queued ahead of this one committed: revision 2, a second step,
        // and a conversation property this turn must not overwrite with its stale view.
        var current = snapshot(ConversationState.READY, 2L, 2);
        current.getConversationProperties().put("fromTurnOne", new Property("fromTurnOne", "set by turn 1", Property.Scope.conversation));
        doReturn(atRequest, current).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(2L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);

        IConversation staleConversation = mock(IConversation.class);
        IConversation currentConversation = mock(IConversation.class);
        stubContinueConversation(staleConversation, currentConversation);

        runQueuedSay();

        verify(staleConversation, never()).say(anyString(), any());
        verify(currentConversation).say(eq("second message"), any());
        assertEquals(2, builtOver.size(), "the turn must be rebuilt over the reloaded memory");
        IConversationMemory ranOn = builtOver.get(1);
        assertEquals(2L, ranOn.getRevision(), "the turn must run on the revision the store now holds");
        assertEquals(2, ranOn.size(), "the turn must see the step the earlier turn appended");
        assertNotNull(ranOn.getConversationProperties().get("fromTurnOne"),
                "the turn must see the property the earlier turn wrote");

        // And what it persists is built on the current revision, so the commit is a
        // clean append rather than a merge that re-applies stale properties.
        ArgumentCaptor<ConversationMemorySnapshot> stored = ArgumentCaptor.forClass(ConversationMemorySnapshot.class);
        verify(conversationMemoryStore).storeConversationMemorySnapshot(stored.capture());
        assertEquals(2L, stored.getValue().getRevision());
        assertTrue(stored.getValue().getConversationProperties().containsKey("fromTurnOne"));
    }

    @Test
    @DisplayName("H13a: a turn whose snapshot is still current is not reloaded")
    void currentTurnIsNotReloaded() throws Exception {
        doReturn(snapshot(ConversationState.READY, 1L, 1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(1L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        IConversation conversation = mock(IConversation.class);
        stubContinueConversation(conversation);

        runQueuedSay();

        verify(conversationMemoryStore, times(1)).loadConversationMemorySnapshot(CONVERSATION_ID);
        assertEquals(1, builtOver.size());
        verify(conversation).say(eq("second message"), any());
    }

    @Test
    @DisplayName("H13a: a turn that cannot be rebuilt over the current document is skipped, not run on the stale one")
    void unrebuildableTurnIsSkipped() throws Exception {
        doReturn(snapshot(ConversationState.READY, 1L, 1), snapshot(ConversationState.READY, 2L, 2))
                .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(2L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        IConversation staleConversation = mock(IConversation.class);
        doReturn(false).when(staleConversation).isEnded();
        doAnswer(inv -> {
            builtOver.add(inv.getArgument(0));
            if (builtOver.size() == 1) {
                return staleConversation;
            }
            throw new IllegalAccessException("Agent deployment is still in progress!");
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

        ConversationResponseHandler handler = runQueuedSay();

        verify(staleConversation, never()).say(anyString(), any());
        verify(handler).onSkipped(any());
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
    }

    @Test
    @DisplayName("E2: a pause whose commit is refused does not stay AWAITING_HUMAN in the state cache")
    void refusedPauseCommitCorrectsTheStateCache() throws Exception {
        doReturn(snapshot(ConversationState.READY, 1L, 1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        IConversation conversation = mock(IConversation.class);
        stubContinueConversation(conversation);
        // The pipeline pauses this turn and renders it (which caches the state it
        // ended in) — but the pause commit loses to a concurrent writer.
        doAnswer(inv -> {
            IConversationMemory memory = builtOver.get(0);
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            renderers.get(0).renderOutput(memory);
            return null;
        }).when(conversation).say(anyString(), any());
        doReturn(false).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));

        runQueuedSay();

        InOrder order = inOrder(conversationStateCache);
        order.verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.AWAITING_HUMAN);
        order.verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.READY);
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    @DisplayName("M-E1: an ended conversation is refused before the lazy redeploy")
    void endedConversationIsRefusedBeforeRedeploy() throws Exception {
        doReturn(snapshot(ConversationState.ENDED, 1L, 1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        assertThrows(ConversationEndedException.class,
                () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(),
                        new InputData("hello?", Map.of()), false, mock(ConversationResponseHandler.class)));

        verify(agentFactory, never()).getAgent(any(), any(), any());
        verify(agentFactory, never()).deployAgent(any(), any(), any(), any());
        verify(tenantQuotaService, never()).acquireApiCallSlot();
    }

    // ---------------------------------------------------------------------------

    /**
     * A rerun re-executes the step the caller saw. Rebuilt over a memory another
     * turn has since extended, it would re-execute THAT turn's step — its tool
     * calls and LLM spend included — so a superseded rerun is skipped instead.
     */
    @Test
    @DisplayName("a queued rerun whose snapshot was superseded is skipped, not rebuilt over a different step")
    void supersededRerunIsSkipped() throws Exception {
        doReturn(snapshot(ConversationState.READY, 1L, 1), snapshot(ConversationState.READY, 2L, 2))
                .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(2L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        IConversation conversation = mock(IConversation.class);
        stubContinueConversation(conversation);

        ConversationResponseHandler handler = mock(ConversationResponseHandler.class);
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(), new InputData("", Map.of()), true, handler);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        captor.getValue().call();

        verify(conversation, never()).rerun(any());
        assertEquals(1, builtOver.size(), "a rerun must not be rebuilt over the newer memory");
        verify(handler).onSkipped(any());
        verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
    }

    @Test
    @DisplayName("a queued rerun whose snapshot is current still runs")
    void currentRerunRuns() throws Exception {
        doReturn(snapshot(ConversationState.READY, 1L, 1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        IConversation conversation = mock(IConversation.class);
        stubContinueConversation(conversation);

        ConversationResponseHandler handler = mock(ConversationResponseHandler.class);
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(), new InputData("", Map.of()), true, handler);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        captor.getValue().call();

        verify(conversation).rerun(any());
        verify(handler, never()).onSkipped(any());
    }

    @Test
    @DisplayName("H13a: a superseded streaming turn is rebuilt with its event sink, and completes the stream")
    void supersededStreamingTurnIsRebuiltAndCompletesTheStream() throws Exception {
        doReturn(snapshot(ConversationState.READY, 1L, 1), snapshot(ConversationState.READY, 2L, 2))
                .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(2L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        IConversation staleConversation = mock(IConversation.class);
        IConversation currentConversation = mock(IConversation.class);
        stubContinueConversation(staleConversation, currentConversation);
        doAnswer(inv -> {
            IConversationMemory memory = builtOver.get(1);
            memory.getEventSink().onToken("hello");
            renderers.get(1).renderOutput(memory);
            return null;
        }).when(currentConversation).say(anyString(), any());

        StreamingResponseHandler streamingHandler = mock(StreamingResponseHandler.class);
        conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("streamed", Map.of()),
                streamingHandler);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        captor.getValue().call();

        verify(staleConversation, never()).say(anyString(), any());
        assertNotNull(builtOver.get(1).getEventSink(), "the rebuilt memory must carry the stream's event sink");
        verify(streamingHandler).onToken("hello");
        ArgumentCaptor<SimpleConversationMemorySnapshot> completed = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
        verify(streamingHandler).onComplete(completed.capture());
        assertEquals(2, completed.getValue().getConversationSteps().size(), "the stream completes with the rebuilt turn's memory");
    }

    @Test
    @DisplayName("a store blip while reporting a conflict does not escape the completion callback, and the cache is still dropped")
    void conflictReportSurvivesAStoreBlip() throws Exception {
        doReturn(snapshot(ConversationState.READY, 1L, 1)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        IConversation conversation = mock(IConversation.class);
        stubContinueConversation(conversation);
        doThrow(new ConcurrentConversationModificationException(CONVERSATION_ID, 1L))
                .when(conversationMemoryStore).storeConversationMemorySnapshot(any());
        // READY for the queued-turn guard, then the store is unreachable.
        doReturn(ConversationState.READY).doThrow(new IllegalStateException("store blip"))
                .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

        assertDoesNotThrow(this::runQueuedSay);

        verify(conversationStateCache).remove(CONVERSATION_ID);
    }

    private ConversationResponseHandler runQueuedSay() throws Exception {
        ConversationResponseHandler handler = mock(ConversationResponseHandler.class);
        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(),
                new InputData("second message", Map.of()), false, handler);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        captor.getValue().call();
        return handler;
    }

    private void stubContinueConversation(IConversation... conversations) throws Exception {
        for (IConversation conversation : conversations) {
            lenient().doReturn(false).when(conversation).isEnded();
        }
        doAnswer(inv -> {
            builtOver.add(inv.getArgument(0));
            renderers.add(inv.getArgument(2));
            return conversations[Math.min(builtOver.size(), conversations.length) - 1];
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
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

    private static ConversationMemorySnapshot snapshot(ConversationState state, long revision, int steps) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(state);
        snapshot.setEnvironment(ENV);
        snapshot.setRevision(revision);
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
