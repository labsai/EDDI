/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.CancelOutcome;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
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
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch-coverage-focused unit tests for the
 * HITL/schedule/timeout/cancel/undo-redo error and edge paths in
 * {@link ConversationService} that integration tests do not exercise. Strictly
 * additive: copies the @Mock wiring and helper builders from
 * {@code ConversationServiceSayHitlTest} /
 * {@code ConversationServiceToolTimeoutTest}.
 */
class ConversationServiceHitlCoverageTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-hitl-cov";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-hitl-cov-1";
    private static final String USER_ID = "user-hitl-cov";
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
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        lenient().doReturn(new java.util.HashMap<String, String>()).when(contextLogger)
                .createLoggingContext(any(), any(), any(), any());
        meterRegistry = new SimpleMeterRegistry();
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                meterRegistry, ConversationServiceTestFixtures.hitlResumeEvent(), AGENT_TIMEOUT);

        // Runtime executes the submitted callable inline and drives the listener.
        lenient().when(runtime.submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any()))
                .thenAnswer(invocation -> {
                    Callable<Object> callable = invocation.getArgument(0);
                    IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
                    try {
                        Object result = callable.call();
                        listener.onComplete(result);
                        return java.util.concurrent.CompletableFuture.completedFuture(result);
                    } catch (Exception e) {
                        listener.onFailure(e);
                        return java.util.concurrent.CompletableFuture.failedFuture(e);
                    }
                });
        lenient().when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), any()))
                .thenReturn(true);
    }

    // =========================================================================
    // cancelConversation
    // =========================================================================

    @Nested
    @DisplayName("cancelConversation branches")
    class CancelConversation {

        @Test
        @DisplayName("null current state → NOT_FOUND")
        void nullState_notFound() throws Exception {
            doReturn(null).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            var outcome = conversationService.cancelConversation(
                    CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, "reviewer-1");

            assertEquals(CancelOutcome.NOT_FOUND, outcome);
            // never attempts a state CAS on an unknown conversation
            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
        }

        @Test
        @DisplayName("AWAITING_HUMAN pause → EXECUTION_INTERRUPTED CAS wins → CANCELLED + audit + bookmark cleared")
        void awaitingHuman_cancelled_auditsAndClearsBookmark() throws Exception {
            doReturn(true).when(auditLedgerService).isEnabled();
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);

            var outcome = conversationService.cancelConversation(
                    CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, "reviewer-1");

            assertEquals(CancelOutcome.CANCELLED, outcome);
            verify(auditLedgerService, atLeastOnce()).submit(any());
            verify(conversationMemoryStore).clearHitlBookmark(CONVERSATION_ID);
            // stale timeout schedule was disarmed
            verify(scheduleStore).deleteSchedulesByName(anyString());
        }

        @Test
        @DisplayName("clearHitlBookmark throws on cancel → swallowed, still CANCELLED")
        void awaitingHuman_bookmarkClearThrows_swallowed() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            doThrow(new RuntimeException("mongo down")).when(conversationMemoryStore).clearHitlBookmark(CONVERSATION_ID);

            var outcome = conversationService.cancelConversation(
                    CONVERSATION_ID, ControlSignal.CANCEL_IMMEDIATE, "reviewer-1");

            assertEquals(CancelOutcome.CANCELLED, outcome);
        }

        @Test
        @DisplayName("IN_PROGRESS (not paused) CAS wins → CANCELLED, no bookmark clear (pauseCancelled false)")
        void inProgress_cancelled_noBookmarkClear() throws Exception {
            doReturn(ConversationState.IN_PROGRESS).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            // First CAS (AWAITING_HUMAN) misses, second CAS (IN_PROGRESS) wins
            doReturn(false).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);

            var outcome = conversationService.cancelConversation(
                    CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, "reviewer-1");

            assertEquals(CancelOutcome.CANCELLED, outcome);
            verify(conversationMemoryStore, never()).clearHitlBookmark(any());
        }

        @Test
        @DisplayName("both CAS miss, nothing in flight → NOTHING_TO_CANCEL")
        void noCas_nothingInFlight_nothingToCancel() throws Exception {
            doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(false).when(conversationMemoryStore).compareAndSetState(any(), any(), any());

            var outcome = conversationService.cancelConversation(
                    CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, "reviewer-1");

            assertEquals(CancelOutcome.NOTHING_TO_CANCEL, outcome);
        }

        @Test
        @DisplayName("deleteSchedulesByName throws on cancel → swallowed, cancel still proceeds")
        void deleteScheduleThrows_swallowed() throws Exception {
            doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doThrow(new ResourceStoreException("schedule store down"))
                    .when(scheduleStore).deleteSchedulesByName(anyString());
            doReturn(false).when(conversationMemoryStore).compareAndSetState(any(), any(), any());

            var outcome = conversationService.cancelConversation(
                    CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, "reviewer-1");

            assertEquals(CancelOutcome.NOTHING_TO_CANCEL, outcome);
        }

        @Test
        @DisplayName("deleteSchedulesByName returns >0 → cleanup log branch, cancel proceeds")
        void deleteScheduleReturnsPositive_cleanupBranch() throws Exception {
            doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(3).when(scheduleStore).deleteSchedulesByName(anyString());
            doReturn(false).when(conversationMemoryStore).compareAndSetState(any(), any(), any());

            var outcome = conversationService.cancelConversation(
                    CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, "reviewer-1");

            assertEquals(CancelOutcome.NOTHING_TO_CANCEL, outcome);
            verify(scheduleStore).deleteSchedulesByName(anyString());
        }
    }

    // =========================================================================
    // endConversation — deleteHitlTimeoutSchedule + pause-terminating audit
    // =========================================================================

    @Nested
    @DisplayName("endConversation branches (disarm timeout, pause audit)")
    class EndConversation {

        @Test
        @DisplayName("ending an AWAITING_HUMAN conversation → audit + bookmark clear + schedule delete")
        void endAwaitingHuman_auditsAndClears() throws Exception {
            doReturn(true).when(auditLedgerService).isEnabled();
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            conversationService.endConversation(CONVERSATION_ID, "admin-1");

            verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
            verify(scheduleStore).deleteSchedulesByName(anyString());
            verify(auditLedgerService, atLeastOnce()).submit(any());
        }

        @Test
        @DisplayName("ending an AWAITING_HUMAN conversation with clearHitlBookmark throwing → swallowed")
        void endAwaitingHuman_bookmarkThrows_swallowed() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doThrow(new RuntimeException("boom")).when(conversationMemoryStore).clearHitlBookmark(CONVERSATION_ID);

            conversationService.endConversation(CONVERSATION_ID, "admin-1");

            verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
        }

        @Test
        @DisplayName("ending a READY conversation → no HITL audit/bookmark, schedule still disarmed unconditionally")
        void endReady_noHitlBookkeeping() throws Exception {
            doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            conversationService.endConversation(CONVERSATION_ID); // default actor overload

            verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
            verify(scheduleStore).deleteSchedulesByName(anyString());
            verifyNoInteractions(auditLedgerService);
        }
    }

    // =========================================================================
    // listPendingApprovals — owner vs. all + limit clamping
    // =========================================================================

    @Nested
    @DisplayName("listPendingApprovals owner vs all + limit clamp")
    class ListPendingApprovals {

        @Test
        @DisplayName("all-inbox limit is clamped to >= 1")
        void allInbox_limitClampedLow() throws Exception {
            doReturn(List.of()).when(conversationMemoryStore).findPendingApprovalSummaries(1);

            List<PendingApprovalSummary> result = conversationService.listPendingApprovals(0);

            assertNotNull(result);
            verify(conversationMemoryStore).findPendingApprovalSummaries(1);
        }

        @Test
        @DisplayName("all-inbox limit is clamped to <= 1000")
        void allInbox_limitClampedHigh() throws Exception {
            doReturn(List.of()).when(conversationMemoryStore).findPendingApprovalSummaries(1000);

            conversationService.listPendingApprovals(99999);

            verify(conversationMemoryStore).findPendingApprovalSummaries(1000);
        }

        @Test
        @DisplayName("owner-filtered inbox pushes ownerUserId + clamped limit into the query")
        void ownerInbox_pushesOwnerFilter() throws Exception {
            doReturn(List.of()).when(conversationMemoryStore).findPendingApprovalSummaries(USER_ID, 1);

            conversationService.listPendingApprovals(USER_ID, -5);

            verify(conversationMemoryStore).findPendingApprovalSummaries(USER_ID, 1);
        }
    }

    // =========================================================================
    // undo / redo — HITL state guards + CAS-miss
    // =========================================================================

    @Nested
    @DisplayName("undo/redo HITL state guards + CAS-miss")
    class UndoRedoGuards {

        @Test
        @DisplayName("undo rejected while AWAITING_HUMAN → false, no store")
        void undo_awaitingHuman_rejected() throws Exception {
            stubLoadedSnapshotState(ConversationState.AWAITING_HUMAN);

            boolean result = conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshotIfState(any(), any());
        }

        @Test
        @DisplayName("undo rejected while IN_PROGRESS → false, no store")
        void undo_inProgress_rejected() throws Exception {
            stubLoadedSnapshotState(ConversationState.IN_PROGRESS);

            boolean result = conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshotIfState(any(), any());
        }

        @Test
        @DisplayName("undo READY but nothing to undo → false")
        void undo_ready_noUndoAvailable() throws Exception {
            stubLoadedSnapshotState(ConversationState.READY); // single-step snapshot: no undo
            boolean result = conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID);
            assertFalse(result);
        }

        @Test
        @DisplayName("undo READY + undo available but state-CAS misses (concurrent writer) → false")
        void undo_casMiss_returnsFalse() throws Exception {
            // Two steps → undo is available
            stubLoadedSnapshotStateMultiStep(ConversationState.READY);
            doReturn(false).when(conversationMemoryStore)
                    .storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));

            boolean result = conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
            verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.READY));
        }

        @Test
        @DisplayName("redo rejected while AWAITING_HUMAN → false, no store")
        void redo_awaitingHuman_rejected() throws Exception {
            stubLoadedSnapshotState(ConversationState.AWAITING_HUMAN);

            boolean result = conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshotIfState(any(), any());
        }

        @Test
        @DisplayName("redo rejected while IN_PROGRESS → false, no store")
        void redo_inProgress_rejected() throws Exception {
            stubLoadedSnapshotState(ConversationState.IN_PROGRESS);

            boolean result = conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshotIfState(any(), any());
        }

        @Test
        @DisplayName("redo READY but nothing to redo → false")
        void redo_ready_noRedoAvailable() throws Exception {
            stubLoadedSnapshotState(ConversationState.READY);
            boolean result = conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID);
            assertFalse(result);
        }
    }

    // =========================================================================
    // scheduleHitlTimeout — WAIT_INDEFINITELY skip / blank / past-due clamp /
    // fresh / parse exception — driven through the resume re-pause path.
    // =========================================================================

    @Nested
    @DisplayName("scheduleHitlTimeout guard branches (via resume re-pause)")
    class ScheduleTimeout {

        @Test
        @DisplayName("WAIT_INDEFINITELY policy on re-pause → no schedule armed")
        void waitIndefinitely_skipsSchedule() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("PT30S");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            resumeWithRulePause(hitl, null);
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("blank approvalTimeout → no schedule armed")
        void blankTimeout_skipsSchedule() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("   ");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            resumeWithRulePause(hitl, null);
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("fresh finite pause (pausedAt ~ now) → schedule armed honoring configured timeout")
        void freshPause_armsSchedule() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("PT30M");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            resumeWithRulePause(hitl, Instant.now());

            ArgumentCaptor<ScheduleConfiguration> cap = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(cap.capture());
            assertEquals("AUTO_REJECT", cap.getValue().getMetadata().get("policy"));
            assertEquals("regular", cap.getValue().getMetadata().get("surface"));
        }

        @Test
        @DisplayName("past-due deadline (pausedAt far in past) → clamped to now+grace, still armed")
        void pastDueDeadline_clampedToGrace() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("PT1M");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            // pausedAt 1 hour ago + PT1M → already elapsed → clamp branch
            resumeWithRulePause(hitl, Instant.now().minusSeconds(3600));

            ArgumentCaptor<ScheduleConfiguration> cap = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(cap.capture());
            // fireAt clamped to a near-future value (now + 2m grace), not the elapsed past
            assertTrue(cap.getValue().getNextFire().isAfter(Instant.now()),
                    "past-due deadline must be clamped to a future fire time");
        }

        @Test
        @DisplayName("unparseable approvalTimeout → parse exception swallowed, no schedule armed")
        void unparseableTimeout_exceptionSwallowed() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("not-a-duration");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            resumeWithRulePause(hitl, Instant.now());
            // Duration.parse throws → caught in scheduleHitlTimeout → no schedule created
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("createSchedule itself throws → swallowed, resume still completes")
        void createScheduleThrows_swallowed() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("PT30M");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            doThrow(new ResourceStoreException("insert failed")).when(scheduleStore).createSchedule(any());

            // Must not propagate out of the resume
            assertDoesNotThrow(() -> resumeWithRulePause(hitl, Instant.now()));
        }
    }

    // =========================================================================
    // populateToolApprovalsConfig — null agent config / null hitlConfig / present
    // (observed via the resume path which calls it before continueConversation).
    // =========================================================================

    @Nested
    @DisplayName("populateToolApprovalsConfig branches")
    class PopulateToolApprovals {

        @Test
        @DisplayName("null agent config → tool-approvals carrier cleared to null")
        void nullAgentConfig_clearsCarrier() throws Exception {
            // Pinned read returns null AND current resource id null → readAgentConfigPinned
            // null
            doReturn(null).when(agentStore).read(AGENT_ID, AGENT_VERSION);
            doReturn(null).when(agentStore).getCurrentResourceId(AGENT_ID);

            AtomicReference<IConversationMemory> mem = drivePlainResume(null);

            assertNull(mem.get().getAgentToolApprovalsConfig());
        }

        @Test
        @DisplayName("null hitlConfig on agent config → carrier cleared to null")
        void nullHitlConfig_clearsCarrier() throws Exception {
            var agentConfig = new AgentConfiguration(); // no hitlConfig
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, AGENT_VERSION);

            AtomicReference<IConversationMemory> mem = drivePlainResume(agentConfig);

            assertNull(mem.get().getAgentToolApprovalsConfig());
        }

        @Test
        @DisplayName("present hitlConfig.toolApprovals → carrier populated")
        void presentToolApprovals_populatesCarrier() throws Exception {
            var agentConfig = new AgentConfiguration();
            var hitl = new AgentConfiguration.HitlConfig();
            var toolApprovals = new ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig();
            hitl.setToolApprovals(toolApprovals);
            agentConfig.setHitlConfig(hitl);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, AGENT_VERSION);

            AtomicReference<IConversationMemory> mem = drivePlainResume(agentConfig);

            assertSame(toolApprovals, mem.get().getAgentToolApprovalsConfig());
        }

        @Test
        @DisplayName("readAgentConfigPinned pinned read throws → falls back to current resource id")
        void pinnedReadThrows_fallsBackToCurrent() throws Exception {
            var agentConfig = new AgentConfiguration();
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setToolApprovals(new ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig());
            agentConfig.setHitlConfig(hitl);
            // pinned read throws → fallback path
            doThrow(new ResourceStoreException("pinned gone")).when(agentStore).read(AGENT_ID, AGENT_VERSION);
            var resId = mock(IResourceStore.IResourceId.class);
            doReturn(2).when(resId).getVersion();
            doReturn(resId).when(agentStore).getCurrentResourceId(AGENT_ID);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, 2);

            AtomicReference<IConversationMemory> mem = drivePlainResume(agentConfig);

            assertNotNull(mem.get().getAgentToolApprovalsConfig());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Drives a plain resume (no re-pause — the pipeline settles to READY) purely to
     * exercise populateToolApprovalsConfig / populateHitlTimeoutBookmark against
     * the given agent config, returning the live memory handed to
     * continueConversation. When {@code agentConfig} is null the caller has stubbed
     * agentStore itself.
     */
    private AtomicReference<IConversationMemory> drivePlainResume(AgentConfiguration agentConfig) throws Exception {
        doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        doReturn(true).when(conversationMemoryStore).compareAndSetState(
                CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        var snapshot = rulePauseSnapshot();
        snapshot.setConversationState(ConversationState.IN_PROGRESS);
        doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        IAgent agent = mock(IAgent.class);
        IConversation conversation = mock(IConversation.class);
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        var memoryRef = new AtomicReference<IConversationMemory>();
        doAnswer(inv -> {
            memoryRef.set(inv.getArgument(0));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
        doAnswer(inv -> {
            memoryRef.get().setConversationState(ConversationState.READY);
            return null;
        }).when(conversation).resume(any(HitlDecision.class));

        HitlDecision decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer-1");

        conversationService.resumeConversation(CONVERSATION_ID, decision, null);
        drainCoordinatorCallables();
        return memoryRef;
    }

    /**
     * Drives a full resume whose resumed turn RE-PAUSES on a fresh RULE pause with
     * the given agent hitlConfig and (optional) pausedAt so scheduleHitlTimeout's
     * clamp/fresh branches are exercised.
     */
    private void resumeWithRulePause(AgentConfiguration.HitlConfig hitlConfig, Instant rePausePausedAt)
            throws Exception {
        doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        doReturn(true).when(conversationMemoryStore).compareAndSetState(
                CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        var snapshot = rulePauseSnapshot();
        snapshot.setConversationState(ConversationState.IN_PROGRESS);
        doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        var agentConfig = new AgentConfiguration();
        agentConfig.setHitlConfig(hitlConfig);
        doReturn(agentConfig).when(agentStore).read(AGENT_ID, AGENT_VERSION);

        IAgent agent = mock(IAgent.class);
        IConversation conversation = mock(IConversation.class);
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        var memoryRef = new AtomicReference<IConversationMemory>();
        doAnswer(inv -> {
            memoryRef.set(inv.getArgument(0));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
        doAnswer(inv -> {
            var memory = memoryRef.get();
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("workflow-1");
            memory.setHitlPausedAbsoluteTaskIndex(4);
            memory.setHitlPauseReason("rule pause");
            memory.setHitlPauseType("RULE");
            if (rePausePausedAt != null) {
                memory.setHitlPausedAt(rePausePausedAt);
            }
            return null;
        }).when(conversation).resume(any(HitlDecision.class));

        HitlDecision decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer-1");

        conversationService.resumeConversation(CONVERSATION_ID, decision, null);
        drainCoordinatorCallables();
    }

    @SuppressWarnings("unchecked")
    private void drainCoordinatorCallables() throws Exception {
        int ran = 0;
        while (true) {
            ArgumentCaptor<Callable<Void>> cap = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator, atLeastOnce()).submitInOrder(eq(CONVERSATION_ID), cap.capture());
            var all = cap.getAllValues();
            if (ran >= all.size()) {
                return;
            }
            int start = ran;
            for (int i = start; i < all.size(); i++) {
                all.get(i).call();
                ran++;
            }
        }
    }

    /** A minimal RULE-pause snapshot loadable into a live memory. */
    private ConversationMemorySnapshot rulePauseSnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setEnvironment(ENV);
        snapshot.setHitlPausedWorkflowId("workflow-1");
        snapshot.setHitlPausedAbsoluteTaskIndex(2);
        snapshot.setHitlPauseReason("rule pause");
        snapshot.setHitlPauseType("RULE");

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

    /** Stubs a single-step snapshot (no undo/redo available) in the given state. */
    private void stubLoadedSnapshotState(ConversationState state) throws Exception {
        var snapshot = rulePauseSnapshot();
        snapshot.setConversationState(state);
        snapshot.setHitlPauseType(null);
        doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
    }

    /** Stubs a two-step snapshot so undo/redo is available. */
    private void stubLoadedSnapshotStateMultiStep(ConversationState state) throws Exception {
        var snapshot = rulePauseSnapshot();
        snapshot.setConversationState(state);
        snapshot.setHitlPauseType(null);
        var stepSnapshot2 = new ConversationStepSnapshot();
        var workflowRun2 = new WorkflowRunSnapshot();
        workflowRun2.getLifecycleTasks().add(new ResultSnapshot("input:second", "world", null, new Date(), null, true));
        stepSnapshot2.getWorkflows().add(workflowRun2);
        snapshot.getConversationSteps().add(stepSnapshot2);
        var output2 = new ConversationOutput();
        output2.put("input", "world");
        snapshot.getConversationOutputs().add(output2);
        doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
    }

}
