/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.ConversationAwaitingApprovalException;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Batch-3 branch-coverage tests for the HITL error/edge paths in
 * {@link ConversationService} that the batch-1/batch-2 coverage tests
 * ({@code ConversationServiceHitlCoverageTest}) and the feature tests
 * ({@code ConversationServiceSayHitlTest} / {@code ResumeTest} /
 * {@code ToolTimeoutTest} / {@code ToolDecisionValidationTest}) do NOT reach.
 *
 * <p>
 * Strictly additive — copies the @Mock wiring and helper builders from
 * {@code ConversationServiceSayHitlTest}. Covers DIFFERENT branches:
 * </p>
 * <ul>
 * <li>{@code resumeConversation} 404 (getConversationState null) BEFORE any
 * CAS;</li>
 * <li>toolDecisions present but the pre-CAS snapshot is null → validation
 * skipped;</li>
 * <li>transient snapshot-load failure after the CAS → pause restored,
 * wrapped;</li>
 * <li>coordinator submit failure after the CAS → pause restored + re-armed,
 * wrapped;</li>
 * <li>{@code restorePauseAfterFailedResume} CAS-restore miss (concurrent
 * writer);</li>
 * <li>{@code sayStreaming} fast-fail on AWAITING_HUMAN;</li>
 * <li>{@code say(String, …)} conversation-only overload fast-fail;</li>
 * <li>{@code waitForExecutionFinishOrTimeout} timeout → EXECUTION_INTERRUPTED,
 * and the AWAITING_HUMAN guard that suppresses it;</li>
 * <li>{@code listPendingApprovals} non-clamped mid-range limit + owner-inbox
 * high clamp.</li>
 * </ul>
 */
class ConversationServiceHitlCoverage2Test {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-hitl-cov2";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-hitl-cov2-1";
    private static final String USER_ID = "user-hitl-cov2";
    private static final int AGENT_TIMEOUT = 30;

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
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        lenient().doReturn(new HashMap<String, String>()).when(contextLogger)
                .createLoggingContext(any(), any(), any(), any());
        meterRegistry = new SimpleMeterRegistry();
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                meterRegistry, ConversationServiceTestFixtures.hitlResumeEvent(), AGENT_TIMEOUT);
    }

    // =========================================================================
    // resumeConversation — pre-CAS 404 + toolDecisions-with-null-snapshot skip
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation pre-CAS guards")
    class ResumePreCas {

        @Test
        @DisplayName("getConversationState null → ResourceNotFoundException (404) BEFORE any CAS or validation")
        void unknownConversation_notFound() throws Exception {
            doReturn(null).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            assertThrows(ResourceNotFoundException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));

            // Never reaches the pause-consuming CAS nor the pre-CAS snapshot load.
            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
            verify(conversationMemoryStore, never()).loadConversationMemorySnapshot(CONVERSATION_ID);
        }

        @Test
        @DisplayName("toolDecisions present but pre-CAS snapshot null → validation skipped, CAS still runs")
        void toolDecisionsButNullPreCasSnapshot_skipsValidation() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            // The pre-CAS snapshot read returns null → the `preCasSnapshot != null` guard
            // is false, so validateToolDecisions is skipped and the path proceeds to the
            // CAS (which we fail here to keep the test to the pre-CAS branch).
            doReturn(null).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(false).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of("call_abc", toolDecision(HitlVerdict.APPROVED)));

            // Validation did not throw (skipped on null snapshot); the CAS miss surfaces
            // as a wrong-state 409 conflict instead.
            assertThrows(IllegalStateException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        }
    }

    // =========================================================================
    // resumeConversation — post-CAS failure restore paths
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation post-CAS failure restore")
    class ResumePostCasRestore {

        @Test
        @DisplayName("transient snapshot-load failure after CAS → pause restored (no re-arm), wrapped as ResourceStoreException")
        void snapshotLoadThrowsAfterCas_restoresPause() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            // The post-CAS snapshot load hits a transient store failure (unchecked) →
            // inner catch restores the pause with rearmSchedule=false.
            doThrow(new RuntimeException("mongo hiccup"))
                    .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            assertThrows(ResourceStoreException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));

            // The pause was restored via the reverse CAS; the schedule is NOT re-armed on
            // this path (it was never deleted — the delete is deferred past the load).
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("coordinator submit throws after CAS → pause restored + re-armed, wrapped as ResourceStoreException")
        void coordinatorSubmitThrows_restoresAndReArms() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            var snapshot = rulePauseSnapshot();
            snapshot.setConversationState(ConversationState.IN_PROGRESS);
            // Finite policy on the bookmark so restore(rearm=true) actually arms a
            // schedule.
            snapshot.setHitlTimeoutPolicy("AUTO_REJECT");
            snapshot.setHitlApprovalTimeout("PT30M");
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            // The coordinator rejects the resume submission (e.g. saturation) → the inner
            // catch removes the in-flight entry, restores the pause with rearm=true, and
            // wraps the failure.
            doThrow(new java.util.concurrent.RejectedExecutionException("coordinator full"))
                    .when(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            assertThrows(ResourceStoreException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));

            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            // rearm=true + a finite bookmark policy → the schedule is re-armed.
            verify(scheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("restore CAS misses (concurrent terminal writer) → no re-arm despite rearm=true")
        void restoreCasMiss_noReArm() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            var snapshot = rulePauseSnapshot();
            snapshot.setConversationState(ConversationState.IN_PROGRESS);
            snapshot.setHitlTimeoutPolicy("AUTO_REJECT");
            snapshot.setHitlApprovalTimeout("PT30M");
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            doThrow(new java.util.concurrent.RejectedExecutionException("coordinator full"))
                    .when(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
            // A concurrent end/cancel already moved the persisted state off IN_PROGRESS →
            // the restore CAS misses, so the pause is NOT restored and nothing is re-armed.
            doReturn(false).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            assertThrows(ResourceStoreException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));

            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            // CAS-restore missed → the re-arm branch is skipped even though rearm=true.
            verify(scheduleStore, never()).createSchedule(any());
        }
    }

    // =========================================================================
    // sayStreaming — HITL fast-fail parity with say()
    // =========================================================================

    @Nested
    @DisplayName("sayStreaming fast-fail on AWAITING_HUMAN")
    class SayStreamingFastFail {

        @Test
        @DisplayName("AWAITING_HUMAN → ConversationAwaitingApprovalException BEFORE quota/submit; handler untouched")
        void awaitingHuman_fastFails() throws Exception {
            doReturn(snapshot(ConversationState.AWAITING_HUMAN))
                    .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            StreamingResponseHandler handler = mock(StreamingResponseHandler.class);

            assertThrows(ConversationAwaitingApprovalException.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(),
                            new InputData("hi again", Map.of()), handler));

            verify(tenantQuotaService, never()).acquireApiCallSlot();
            verify(conversationCoordinator, never()).submitInOrder(anyString(), any());
            verifyNoInteractions(handler);
        }
    }

    // =========================================================================
    // say(String, …) conversation-only overload — resolves env/agent, still
    // fast-fails on AWAITING_HUMAN through the delegated say(Environment, …).
    // =========================================================================

    @Nested
    @DisplayName("say(String,…) overload fast-fail")
    class SayOverloadFastFail {

        @Test
        @DisplayName("overload resolves env/agent from the snapshot then fast-fails on AWAITING_HUMAN")
        void overload_awaitingHuman_fastFails() throws Exception {
            // The overload loads the snapshot once to resolve env/agent, then say() loads
            // the live memory (both from the same stubbed snapshot).
            doReturn(snapshot(ConversationState.AWAITING_HUMAN))
                    .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            assertThrows(ConversationAwaitingApprovalException.class,
                    () -> conversationService.say(CONVERSATION_ID, false, true, List.of(),
                            new InputData("hi again", Map.of()), false, handler));

            verify(tenantQuotaService, never()).acquireApiCallSlot();
            verifyNoInteractions(handler);
        }
    }

    // =========================================================================
    // waitForExecutionFinishOrTimeout — timeout path (via say)
    // =========================================================================

    @Nested
    @DisplayName("waitForExecutionFinishOrTimeout timeout branches")
    class WaitTimeout {

        @Test
        @DisplayName("execution times out while persisted state is READY → EXECUTION_INTERRUPTED set")
        void timeout_notAwaitingHuman_setsInterrupted() throws Exception {
            var queued = acceptTurnAndCaptureCallable();
            // Persisted state at the queued-say guard is READY → turn proceeds.
            doReturn(ConversationState.READY)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            // The runtime future times out; the guard re-reads state (still READY here),
            // so it is NOT AWAITING_HUMAN and the watchdog flips EXECUTION_INTERRUPTED.
            stubRuntimeTimeout();

            queued.call();

            verify(conversationMemoryStore).setConversationState(
                    CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("execution times out but state is now AWAITING_HUMAN → guard suppresses EXECUTION_INTERRUPTED")
        void timeout_awaitingHuman_suppressed() throws Exception {
            var queued = acceptTurnAndCaptureCallable();
            // At the queued-say guard the persisted state is READY (turn proceeds), but by
            // the time the watchdog re-reads state after the timeout it reports
            // AWAITING_HUMAN — the guard must NOT overwrite the pause (Invariant 10).
            doReturn(ConversationState.READY, ConversationState.AWAITING_HUMAN)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            stubRuntimeTimeout();

            queued.call();

            verify(conversationMemoryStore, never()).setConversationState(
                    CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);
        }

        /**
         * Wires the happy say() path up to submit and returns the captured coordinator
         * callable. The runtime is NOT stubbed here — the caller stubs it (timeout).
         */
        private Callable<Void> acceptTurnAndCaptureCallable() throws Exception {
            doReturn(snapshot(ConversationState.READY))
                    .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
            doReturn(false).when(conversation).isEnded();

            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);
            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(),
                    new InputData("times out", Map.of()), false, handler);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
            return captor.getValue();
        }
    }

    // =========================================================================
    // listPendingApprovals — mid-range (non-clamped) + owner high-clamp
    // =========================================================================

    @Nested
    @DisplayName("listPendingApprovals non-clamped + owner high clamp")
    class ListPendingApprovals {

        @Test
        @DisplayName("all-inbox mid-range limit passes through unclamped")
        void allInbox_midRange_unclamped() throws Exception {
            doReturn(List.of()).when(conversationMemoryStore).findPendingApprovalSummaries(50);

            conversationService.listPendingApprovals(50);

            verify(conversationMemoryStore).findPendingApprovalSummaries(50);
        }

        @Test
        @DisplayName("owner-inbox high limit is clamped to <= 1000")
        void ownerInbox_highClamp() throws Exception {
            doReturn(List.of()).when(conversationMemoryStore).findPendingApprovalSummaries(USER_ID, 1000);

            conversationService.listPendingApprovals(USER_ID, 100000);

            verify(conversationMemoryStore).findPendingApprovalSummaries(USER_ID, 1000);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ToolCallDecision toolDecision(HitlVerdict verdict) {
        var d = new ToolCallDecision();
        d.setVerdict(verdict);
        return d;
    }

    /**
     * Runtime whose returned Future times out on get() — drives the
     * waitForExecutionFinishOrTimeout TimeoutException branch. The submitted
     * callable is executed inline first so the pipeline (which sets memory state)
     * still runs before the "timeout".
     */
    @SuppressWarnings("unchecked")
    private void stubRuntimeTimeout() throws Exception {
        doAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(0);
            callable.call();
            Future<Object> future = mock(Future.class);
            doThrow(new TimeoutException("watchdog")).when(future).get(anyLong(), any(TimeUnit.class));
            return future;
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    /**
     * Minimal snapshot loadable into a live ConversationMemory in the given state.
     */
    private ConversationMemorySnapshot snapshot(ConversationState state) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(state);
        snapshot.setEnvironment(ENV);

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

    /** A minimal RULE-pause snapshot loadable into a live memory. */
    private ConversationMemorySnapshot rulePauseSnapshot() {
        var snapshot = snapshot(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPausedWorkflowId("workflow-1");
        snapshot.setHitlPausedAbsoluteTaskIndex(2);
        snapshot.setHitlPauseReason("rule pause");
        snapshot.setHitlPauseType("RULE");
        snapshot.setHitlPausedAt(Instant.now());
        return snapshot;
    }
}
