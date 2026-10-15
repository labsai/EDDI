/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Pins the ordering contract between a PAUSED turn's persistence and the moment
 * its client is told about it.
 * <p>
 * The bug: the completion callback fired from inside the pipeline, so the sync
 * response / streaming {@code done} reached the client BEFORE the runtime's
 * {@code onComplete} committed the {@code AWAITING_HUMAN} pause. A client that
 * reacted by reading {@code approval-status} found no pause (state READY or
 * IN_PROGRESS, empty pause id, no details) — the Manager rendered an approvable
 * card with nothing in it. The contract: a turn that ends AWAITING_HUMAN is
 * delivered only after the commit outcome is known, with the state the store
 * really holds, and exactly once on every path.
 */
class PausedTurnDeliveryOrderTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-order";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-order-1";
    private static final String USER_ID = "user-order";

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

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        doReturn(new HashMap<String, String>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
    }

    // =========================================================================
    // say() / sayStreaming()
    // =========================================================================

    /**
     * One say turn wired end to end: the pipeline mock sets the memory state and
     * fires the completion renderer exactly as {@code Conversation.runStep}'s
     * {@code finally} does.
     */
    private final class SayTurn {
        final AtomicReference<IConversationMemory> memory = new AtomicReference<>();
        final AtomicReference<IConversationOutputRenderer> renderer = new AtomicReference<>();
        final IConversation conversation = mock(IConversation.class);
        Callable<Void> queuedTurn;

        SayTurn(ConversationState pipelineEndState) throws Exception {
            doReturn(snapshot(ConversationState.READY, null)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();
            doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            IAgent agent = mock(IAgent.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(false).when(conversation).isEnded();
            doAnswer(inv -> {
                memory.set(inv.getArgument(0));
                renderer.set(inv.getArgument(2));
                return conversation;
            }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
            doAnswer(inv -> {
                memory.get().setConversationState(pipelineEndState);
                renderer.get().renderOutput(memory.get());
                return null;
            }).when(conversation).say(anyString(), any());
            stubRuntimeInline(null);
        }

        void sayAndRun(ConversationResponseHandler handler) throws Exception {
            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(), new InputData("go", Map.of()), false, handler);
            runQueued();
        }

        void sayStreamingAndRun(StreamingResponseHandler handler) throws Exception {
            conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(), new InputData("go", Map.of()), handler);
            runQueued();
        }

        private void runQueued() throws Exception {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
            queuedTurn = captor.getValue();
            queuedTurn.call();
        }
    }

    @Nested
    @DisplayName("sync say()")
    class SyncSay {

        @Test
        @DisplayName("paused turn: the pause is stored BEFORE the response is delivered, and the response says AWAITING_HUMAN")
        void pause_persistedBeforeResponse() throws Exception {
            var turn = new SayTurn(ConversationState.AWAITING_HUMAN);
            doReturn(true).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            turn.sayAndRun(handler);

            InOrder order = inOrder(conversationMemoryStore, handler);
            order.verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            order.verify(handler).onComplete(delivered.capture());
            assertEquals(ConversationState.AWAITING_HUMAN, delivered.getValue().getConversationState());
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.AWAITING_HUMAN);
        }

        @Test
        @DisplayName("the pipeline's completion callback alone does NOT answer a paused turn: the handler is untouched when the pipeline body returns")
        @SuppressWarnings("unchecked")
        void pause_callbackAloneDoesNotDeliver() throws Exception {
            var turn = new SayTurn(ConversationState.AWAITING_HUMAN);
            doReturn(true).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);
            AtomicBoolean untouchedAfterPipeline = new AtomicBoolean();
            doAnswer(invocation -> {
                Callable<Object> callable = invocation.getArgument(0);
                IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
                Object result = callable.call();
                // The pipeline (and its completion callback) is done; persistence has not run.
                untouchedAfterPipeline.set(mockingDetails(handler).getInvocations().isEmpty());
                listener.onComplete(result);
                return CompletableFuture.completedFuture(result);
            }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());

            turn.sayAndRun(handler);

            assertTrue(untouchedAfterPipeline.get(), "a paused turn must not be delivered from the pipeline callback");
            verify(handler, times(1)).onComplete(any());
        }

        @Test
        @DisplayName("pause commit lost (CAS miss): the response reports the stored state, never a phantom AWAITING_HUMAN; delivered once")
        void pause_commitLost_reportsStoredState() throws Exception {
            var turn = new SayTurn(ConversationState.AWAITING_HUMAN);
            doReturn(false).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            // A concurrent writer ended the conversation; that is what the store holds.
            AtomicBoolean pipelineRan = new AtomicBoolean();
            doAnswer(inv -> pipelineRan.get() ? ConversationState.ENDED : ConversationState.READY)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doAnswer(inv -> {
                pipelineRan.set(true);
                turn.memory.get().setConversationState(ConversationState.AWAITING_HUMAN);
                turn.renderer.get().renderOutput(turn.memory.get());
                return null;
            }).when(turn.conversation).say(anyString(), any());
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            turn.sayAndRun(handler);

            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            verify(handler, times(1)).onComplete(delivered.capture());
            assertEquals(ConversationState.ENDED, delivered.getValue().getConversationState());
            assertNotEquals(ConversationState.AWAITING_HUMAN, delivered.getValue().getConversationState());
            verify(conversationStateCache, atLeastOnce()).put(CONVERSATION_ID, ConversationState.ENDED);
            verify(conversationStateCache, never()).put(CONVERSATION_ID, ConversationState.AWAITING_HUMAN);
        }

        @Test
        @DisplayName("cancel races the pause commit: the response reports EXECUTION_INTERRUPTED, delivered once and after the conversion")
        void pause_cancelRaced_reportsInterrupted() throws Exception {
            var turn = new SayTurn(ConversationState.AWAITING_HUMAN);
            AtomicBoolean converted = new AtomicBoolean();
            doAnswer(inv -> {
                // The cancel lands between the top-of-onComplete check and the store.
                turn.memory.get().setCancelled(true);
                return true;
            }).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            doAnswer(inv -> {
                converted.set(true);
                return true;
            }).when(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.AWAITING_HUMAN,
                    ConversationState.EXECUTION_INTERRUPTED);
            doAnswer(inv -> converted.get() ? ConversationState.EXECUTION_INTERRUPTED : ConversationState.READY)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            turn.sayAndRun(handler);

            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            verify(handler, times(1)).onComplete(delivered.capture());
            assertEquals(ConversationState.EXECUTION_INTERRUPTED, delivered.getValue().getConversationState());
            verify(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.AWAITING_HUMAN,
                    ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("store failure while persisting the pause: the handler still terminates exactly once, not as AWAITING_HUMAN")
        void pause_storeFails_stillTerminatesOnce() throws Exception {
            var turn = new SayTurn(ConversationState.AWAITING_HUMAN);
            doAnswer(inv -> {
                throw new IResourceStore.ResourceStoreException("db down");
            }).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            turn.sayAndRun(handler);

            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            verify(handler, times(1)).onComplete(delivered.capture());
            assertNotEquals(ConversationState.AWAITING_HUMAN, delivered.getValue().getConversationState());
        }

        @Test
        @DisplayName("runtime routes the finished turn to onFailure (watchdog abandonment): deferred response is flushed once with the stored state")
        void pause_runtimeFailure_flushesOnce() throws Exception {
            var turn = new SayTurn(ConversationState.AWAITING_HUMAN);
            stubRuntimeInline(new ExecutionAbandonedException("abandoned"));
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            turn.sayAndRun(handler);

            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            verify(handler, times(1)).onComplete(delivered.capture());
            assertEquals(ConversationState.READY, delivered.getValue().getConversationState(),
                    "the failure path commits no pause, so the caller hears the stored state");
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshotIfState(any(), any());
        }

        @Test
        @DisplayName("non-pause turn: delivered immediately from the pipeline callback (latency unchanged), before the persist")
        void nonPause_deliveredBeforePersist_unchanged() throws Exception {
            var turn = new SayTurn(ConversationState.READY);
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            turn.sayAndRun(handler);

            InOrder order = inOrder(handler, conversationMemoryStore);
            order.verify(handler).onComplete(any());
            order.verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
            verify(handler, times(1)).onComplete(any());
        }
    }

    @Nested
    @DisplayName("sayStreaming()")
    class Streaming {

        @Test
        @DisplayName("paused turn: the pause is stored BEFORE the stream's done event, which says AWAITING_HUMAN; one terminal event")
        void pause_persistedBeforeDone() throws Exception {
            var turn = new SayTurn(ConversationState.AWAITING_HUMAN);
            doReturn(true).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            StreamingResponseHandler handler = mock(StreamingResponseHandler.class);

            turn.sayStreamingAndRun(handler);

            InOrder order = inOrder(conversationMemoryStore, handler);
            order.verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            order.verify(handler).onComplete(delivered.capture());
            assertEquals(ConversationState.AWAITING_HUMAN, delivered.getValue().getConversationState());
            verify(handler, times(1)).onComplete(any());
            verify(handler, never()).onError(any());
        }

        @Test
        @DisplayName("pause commit lost: done carries the stored state, not a phantom pause; terminates once")
        void pause_commitLost_doneReportsStoredState() throws Exception {
            var turn = new SayTurn(ConversationState.AWAITING_HUMAN);
            doReturn(false).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
            AtomicBoolean pipelineRan = new AtomicBoolean();
            doAnswer(inv -> pipelineRan.get() ? ConversationState.ENDED : ConversationState.READY)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doAnswer(inv -> {
                pipelineRan.set(true);
                turn.memory.get().setConversationState(ConversationState.AWAITING_HUMAN);
                turn.renderer.get().renderOutput(turn.memory.get());
                return null;
            }).when(turn.conversation).say(anyString(), any());
            StreamingResponseHandler handler = mock(StreamingResponseHandler.class);

            turn.sayStreamingAndRun(handler);

            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            verify(handler, times(1)).onComplete(delivered.capture());
            assertEquals(ConversationState.ENDED, delivered.getValue().getConversationState());
        }

        @Test
        @DisplayName("non-pause turn: done is still sent from the pipeline callback, before the persist")
        void nonPause_doneBeforePersist_unchanged() throws Exception {
            var turn = new SayTurn(ConversationState.READY);
            StreamingResponseHandler handler = mock(StreamingResponseHandler.class);

            turn.sayStreamingAndRun(handler);

            InOrder order = inOrder(handler, conversationMemoryStore);
            order.verify(handler).onComplete(any());
            order.verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        }
    }

    // =========================================================================
    // resume() that re-pauses
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation() that re-pauses")
    class ResumeRePause {

        private final AtomicReference<IConversationMemory> memory = new AtomicReference<>();
        private final AtomicReference<IConversationOutputRenderer> renderer = new AtomicReference<>();
        private Callable<Void> resumeTurn;

        private void prepareResume(ConversationState resumeEndState) throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            doReturn(snapshot(ConversationState.IN_PROGRESS, Instant.now())).when(conversationMemoryStore)
                    .loadConversationMemorySnapshot(CONVERSATION_ID);
            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doAnswer(inv -> {
                memory.set(inv.getArgument(0));
                renderer.set(inv.getArgument(2));
                return conversation;
            }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
            doAnswer(inv -> {
                memory.get().setConversationState(resumeEndState);
                renderer.get().renderOutput(memory.get());
                return null;
            }).when(conversation).resume(any());
            stubRuntimeInline(null);
        }

        private void resumeAndRun(ConversationResponseHandler handler) throws Exception {
            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setDecidedBy("reviewer-1");
            conversationService.resumeConversation(CONVERSATION_ID, decision, handler);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
            resumeTurn = captor.getValue();
            resumeTurn.call();
        }

        @Test
        @DisplayName("re-pause is stored BEFORE the resume response is delivered, and the response says AWAITING_HUMAN")
        void rePause_persistedBeforeResponse() throws Exception {
            prepareResume(ConversationState.AWAITING_HUMAN);
            doReturn(true).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            resumeAndRun(handler);

            InOrder order = inOrder(conversationMemoryStore, handler);
            order.verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            order.verify(handler).onComplete(delivered.capture());
            assertEquals(ConversationState.AWAITING_HUMAN, delivered.getValue().getConversationState());
            verify(handler, times(1)).onComplete(any());
        }

        @Test
        @DisplayName("re-pause commit lost: the response reports the stored state, never a phantom AWAITING_HUMAN; delivered once")
        void rePause_commitLost_reportsStoredState() throws Exception {
            prepareResume(ConversationState.AWAITING_HUMAN);
            doReturn(false).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
            // The resume's own queued-turn check passes; once the callable has run, a
            // concurrent writer has moved the conversation to READY.
            AtomicBoolean pipelineRan = new AtomicBoolean();
            doAnswer(inv -> pipelineRan.get() ? ConversationState.READY : ConversationState.AWAITING_HUMAN)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            conversationService.resumeConversation(CONVERSATION_ID, decision, handler);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
            pipelineRan.set(true);
            captor.getValue().call();

            ArgumentCaptor<SimpleConversationMemorySnapshot> delivered = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            verify(handler, times(1)).onComplete(delivered.capture());
            assertEquals(ConversationState.READY, delivered.getValue().getConversationState());
            verify(conversationStateCache, never()).put(CONVERSATION_ID, ConversationState.AWAITING_HUMAN);
        }

        @Test
        @DisplayName("resume that settles (no re-pause) is delivered immediately, as before")
        void settled_deliveredImmediately() throws Exception {
            prepareResume(ConversationState.READY);
            doReturn(true).when(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            resumeAndRun(handler);

            InOrder order = inOrder(handler, conversationMemoryStore);
            order.verify(handler).onComplete(any());
            order.verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
        }
    }

    // =========================================================================
    // Direct contract of the one-shot holder
    // =========================================================================

    @Nested
    @DisplayName("DeferredDelivery")
    class Holder {

        @Test
        @DisplayName("delivers at most once; a throwing delivery never escapes")
        void oneShotAndNeverThrows() {
            var delivery = new DeferredDelivery();
            List<ConversationState> seen = new ArrayList<>();
            Consumer<ConversationState> boom = state -> {
                seen.add(state);
                throw new IllegalStateException("handler blew up");
            };
            delivery.defer(boom);
            delivery.deliver(ConversationState.AWAITING_HUMAN);
            delivery.deliver(ConversationState.READY);
            assertEquals(List.of(ConversationState.AWAITING_HUMAN), seen);
            assertEquals(false, delivery.isPending());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Runs the pipeline callable inline, then reports completion — or, when
     * {@code failure} is set, routes the finished turn to {@code onFailure} the way
     * BaseRuntime does for an abandoned one.
     */
    @SuppressWarnings("unchecked")
    private void stubRuntimeInline(Throwable failure) {
        doAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(0);
            IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
            try {
                Object result = callable.call();
                if (failure != null) {
                    listener.onFailure(failure);
                } else {
                    listener.onComplete(result);
                }
                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                listener.onFailure(e);
                return CompletableFuture.failedFuture(e);
            }
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    private ConversationMemorySnapshot snapshot(ConversationState state, Instant pausedAt) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(state);
        snapshot.setEnvironment(ENV);
        if (pausedAt != null) {
            snapshot.setHitlPausedWorkflowId("workflow-1");
            snapshot.setHitlPausedAbsoluteTaskIndex(2);
            snapshot.setHitlPausedAt(pausedAt);
            snapshot.setHitlPauseReason("high-risk action");
            snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        }
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
