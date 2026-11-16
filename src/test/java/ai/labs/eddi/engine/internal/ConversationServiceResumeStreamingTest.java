/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
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
import ai.labs.eddi.engine.runtime.ExecutionAbandonedException;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The streamed resume ({@code resumeConversationStreaming}): refusals are
 * thrown before the stream's handler is touched, the decision is announced once
 * before the continued turn, the turn's events reach the handler, and every
 * ending is routed to exactly one terminal call.
 */
class ConversationServiceResumeStreamingTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-resume-stream";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-resume-stream-1";
    private static final String USER_ID = "user-resume-stream";
    private static final Instant PAUSED_AT = Instant.parse("2026-11-03T10:00:00Z");

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

    private ConversationService conversationService;
    private StreamingResponseHandler handler;

    private final AtomicReference<IConversationMemory> memoryRef = new AtomicReference<>();
    private final AtomicReference<IConversation.IConversationOutputRenderer> rendererRef = new AtomicReference<>();
    private IConversation conversation;
    private ConversationMemorySnapshot stored;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);

        doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        lenient().when(runtime.submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any()))
                .thenAnswer(invocation -> {
                    Callable<Object> callable = invocation.getArgument(0);
                    IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
                    try {
                        Object result = callable.call();
                        listener.onComplete(result);
                        return CompletableFuture.completedFuture(result);
                    } catch (Exception e) {
                        listener.onFailure(e);
                        return CompletableFuture.failedFuture(e);
                    }
                });
        lenient().when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), any())).thenReturn(true);

        doReturn(true).when(conversationMemoryStore).compareAndSetState(
                CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        stored = pausedSnapshot();
        doReturn(stored).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        IAgent agent = mock(IAgent.class);
        conversation = mock(IConversation.class);
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doAnswer(inv -> {
            memoryRef.set(inv.getArgument(0));
            rendererRef.set(inv.getArgument(2));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

        handler = mock(StreamingResponseHandler.class);
    }

    private HitlDecision approved() {
        HitlDecision decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer");
        return decision;
    }

    @SuppressWarnings("unchecked")
    private Callable<Void> queuedResume(HitlDecision decision) throws Exception {
        conversationService.resumeConversationStreaming(CONVERSATION_ID, decision, handler, false, true, List.of());
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // Refusals happen before the handler is touched
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a decision without a verdict is refused (400) before the handler is touched")
    void missingVerdictIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> conversationService.resumeConversationStreaming(CONVERSATION_ID, new HitlDecision(), handler, false, true, List.of()));
        verifyNoInteractions(handler);
        verifyNoInteractions(conversationCoordinator);
    }

    @Test
    @DisplayName("an unknown conversation is refused (404) before the handler is touched")
    void unknownConversationIsRefused() throws Exception {
        doReturn(null).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        assertThrows(ResourceNotFoundException.class,
                () -> conversationService.resumeConversationStreaming(CONVERSATION_ID, approved(), handler, false, true, List.of()));
        verifyNoInteractions(handler);
    }

    @Test
    @DisplayName("a conversation that is not paused is refused (409) before the handler is touched")
    void notPausedIsRefused() throws Exception {
        doReturn(false).when(conversationMemoryStore).compareAndSetState(
                CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        assertThrows(IllegalStateException.class,
                () -> conversationService.resumeConversationStreaming(CONVERSATION_ID, approved(), handler, false, true, List.of()));
        verifyNoInteractions(handler);
        verifyNoInteractions(conversationCoordinator);
    }

    @Test
    @DisplayName("a decision for a pause that is no longer current is refused (409) before the handler is touched")
    void pauseMismatchIsRefused() {
        HitlDecision decision = approved();
        decision.setPauseId("1");
        assertThrows(IConversationService.PauseMismatchException.class,
                () -> conversationService.resumeConversationStreaming(CONVERSATION_ID, decision, handler, false, true, List.of()));
        verifyNoInteractions(handler);
        verifyNoInteractions(conversationCoordinator);
    }

    @Test
    @DisplayName("an agent that is not deployed is refused (409) before the handler is touched, and the pause is restored")
    void undeployedAgentIsRefused() throws Exception {
        doReturn(null).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        assertThrows(IllegalStateException.class,
                () -> conversationService.resumeConversationStreaming(CONVERSATION_ID, approved(), handler, false, true, List.of()));
        verifyNoInteractions(handler);
    }

    @Test
    @DisplayName("shutting down is refused before the handler is touched")
    void shutdownIsRefused() throws Exception {
        // the same gate as the plain resume: a coordinator that cannot take the resume
        doThrow(new RejectedExecutionException("saturated")).when(conversationCoordinator)
                .submitInOrder(eq(CONVERSATION_ID), any());
        assertThrows(Exception.class,
                () -> conversationService.resumeConversationStreaming(CONVERSATION_ID, approved(), handler, false, true, List.of()));
        verifyNoInteractions(handler);
    }

    @Test
    @DisplayName("a streaming resume needs a handler")
    void handlerIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> conversationService.resumeConversationStreaming(CONVERSATION_ID, approved(), null, false, true, List.of()));
    }

    // ------------------------------------------------------------------
    // The accepted resume
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the decision is announced once, with the pause id and verdict, before any event of the continued turn")
    void resumeIsAnnouncedBeforeTheTurnsEvents() throws Exception {
        doAnswer(inv -> {
            // the continued turn reports through the sink the service installed
            memoryRef.get().getEventSink().onToken("hello");
            memoryRef.get().setConversationState(ConversationState.READY);
            rendererRef.get().renderOutput(memoryRef.get());
            return null;
        }).when(conversation).resume(any(HitlDecision.class));

        Callable<Void> queued = queuedResume(approved());
        // accepted, but nothing announced until the resume actually runs
        verifyNoInteractions(handler);
        queued.call();

        InOrder order = inOrder(handler);
        order.verify(handler).onResumeAccepted(HitlDecision.pauseIdOf(PAUSED_AT), HitlVerdict.APPROVED);
        order.verify(handler).onToken("hello");
        order.verify(handler).onComplete(any(SimpleConversationMemorySnapshot.class));
        verify(handler, times(1)).onResumeAccepted(any(), any());
        verify(handler, never()).onError(any());
    }

    @Test
    @DisplayName("a resume that settles ends with onComplete")
    void settledResumeCompletes() throws Exception {
        doAnswer(inv -> {
            memoryRef.get().setConversationState(ConversationState.READY);
            rendererRef.get().renderOutput(memoryRef.get());
            return null;
        }).when(conversation).resume(any(HitlDecision.class));

        queuedResume(approved()).call();

        ArgumentCaptor<SimpleConversationMemorySnapshot> snapshot = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
        verify(handler).onComplete(snapshot.capture());
        assertEquals(ConversationState.READY, snapshot.getValue().getConversationState());
        verify(handler, never()).onSkipped(any());
        verify(handler, never()).onError(any());
    }

    @Test
    @DisplayName("a re-pause is delivered only after it is persisted")
    void rePauseIsDeliveredAfterItIsPersisted() throws Exception {
        doAnswer(inv -> {
            memoryRef.get().setConversationState(ConversationState.AWAITING_HUMAN);
            memoryRef.get().setHitlPausedAt(Instant.parse("2026-11-03T10:05:00Z"));
            rendererRef.get().renderOutput(memoryRef.get());
            // still inside the resumed turn: the pause is not stored yet, so the client
            // must not have been told about it
            verify(handler, never()).onComplete(any());
            return null;
        }).when(conversation).resume(any(HitlDecision.class));

        queuedResume(approved()).call();

        InOrder order = inOrder(conversationMemoryStore, handler);
        order.verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
        ArgumentCaptor<SimpleConversationMemorySnapshot> snapshot = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
        order.verify(handler).onComplete(snapshot.capture());
        assertEquals(ConversationState.AWAITING_HUMAN, snapshot.getValue().getConversationState());
        assertEquals(Instant.parse("2026-11-03T10:05:00Z"), snapshot.getValue().getHitlPausedAt());
    }

    @Test
    @DisplayName("a resume cancelled or ended before it ran is skipped, and never announced")
    void endedBeforeRunIsSkipped() throws Exception {
        Callable<Void> queued = queuedResume(approved());
        stored.setConversationState(ConversationState.ENDED);
        doReturn(ConversationState.ENDED).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

        queued.call();

        ArgumentCaptor<SimpleConversationMemorySnapshot> snapshot = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
        verify(handler).onSkipped(snapshot.capture());
        assertEquals(ConversationState.ENDED, snapshot.getValue().getConversationState());
        verify(handler, never()).onResumeAccepted(any(), any());
        verify(handler, never()).onComplete(any());
        verify(conversation, never()).resume(any());
    }

    @Test
    @DisplayName("a resume that throws ends with onError, not with a snapshot")
    void thrownResumeIsAnError() throws Exception {
        RuntimeException boom = new IllegalStateException("provider exploded");
        doThrow(boom).when(conversation).resume(any(HitlDecision.class));

        queuedResume(approved()).call();

        verify(handler).onError(boom);
        verify(handler, never()).onComplete(any());
    }

    @Test
    @DisplayName("a resume the runtime abandons ends with onError")
    void abandonedResumeIsAnError() throws Exception {
        ExecutionAbandonedException abandoned = new ExecutionAbandonedException("watchdog");
        lenient().when(runtime.submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any()))
                .thenAnswer(invocation -> {
                    IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
                    listener.onFailure(abandoned);
                    return CompletableFuture.failedFuture(abandoned);
                });

        queuedResume(approved()).call();

        verify(handler).onError(abandoned);
        verify(handler, never()).onComplete(any());
    }

    @Test
    @DisplayName("a streaming resume installs the event sink on the resumed memory; a plain resume does not")
    void sinkIsInstalledOnlyForAStreamedResume() throws Exception {
        doAnswer(inv -> null).when(conversation).resume(any(HitlDecision.class));
        queuedResume(approved());
        assertNotNull(memoryRef.get().getEventSink());

        // plain resume
        memoryRef.set(null);
        reset(conversationCoordinator);
        doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        doReturn(pausedSnapshot()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        conversationService.resumeConversation(CONVERSATION_ID, approved(), null);
        assertNull(memoryRef.get().getEventSink());
    }

    private static ConversationMemorySnapshot pausedSnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setEnvironment(ENV);
        snapshot.setHitlPausedWorkflowId("workflow-1");
        snapshot.setHitlPausedAbsoluteTaskIndex(2);
        snapshot.setHitlPausedAt(PAUSED_AT);
        snapshot.setHitlPauseReason("high-risk action");
        snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
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
