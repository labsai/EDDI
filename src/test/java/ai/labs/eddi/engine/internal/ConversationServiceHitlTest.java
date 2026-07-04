/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
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
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.agents.IAgentStore;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HITL lifecycle methods in {@link ConversationService}.
 */
class ConversationServiceHitlTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-hitl";
    private static final String CONVERSATION_ID = "conv-hitl-1";
    private static final String USER_ID = "user-hitl";
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
    // Held reference (not the shared no-op fixture) so the cancel-path HITL event
    // firing (G5) can be verified.
    private jakarta.enterprise.event.Event<ai.labs.eddi.engine.events.HitlResumeCompletedEvent> hitlResumeCompletedEvent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        hitlResumeCompletedEvent = mock(jakarta.enterprise.event.Event.class);
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                new SimpleMeterRegistry(), hitlResumeCompletedEvent, AGENT_TIMEOUT);
    }

    // =========================================================================
    // cancelConversation
    // =========================================================================

    @Nested
    @DisplayName("cancelConversation")
    class CancelConversation {

        @Test
        @DisplayName("when in AWAITING_HUMAN → CAS succeeds, state set to EXECUTION_INTERRUPTED, outcome CANCELLED")
        void awaitingHuman_casSucceeds() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);

            var outcome = conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            assertEquals(IConversationService.CancelOutcome.CANCELLED, outcome);
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            // Should NOT try the IN_PROGRESS CAS since the first one succeeded
            verify(conversationMemoryStore, never()).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);
            // MAJOR-3: cancel disarms the pending timeout schedule (a stale fire
            // would auto-decide an already-cancelled conversation) and clears the
            // now-terminal bookmark.
            verify(scheduleStore).deleteSchedulesByName("hitl-timeout-" + CONVERSATION_ID);
            verify(conversationMemoryStore).clearHitlBookmark(CONVERSATION_ID);
        }

        @Test
        @DisplayName("Task 14/4: cancelling a TOOL_CALL pause reaches clearHitlBookmark exactly like a RULE "
                + "pause — the store-level clear (verified in Mongo/PostgresConversationMemoryStoreTest) "
                + "then unsets hitlPauseType/hitlPendingToolCalls")
        void awaitingHuman_toolCallPause_reachesClearHitlBookmark() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);

            var outcome = conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            assertEquals(IConversationService.CancelOutcome.CANCELLED, outcome);
            // ConversationService itself is pause-type-agnostic on the cancel path — it
            // calls clearHitlBookmark unconditionally on every successful pause
            // cancellation. The store implementations (verified separately) unset BOTH
            // the RULE-style bookmark fields AND hitlPauseType/hitlPendingToolCalls in
            // that single call, so a TOOL_CALL pause's pending batch is cleared with no
            // extra plumbing here.
            verify(conversationMemoryStore).clearHitlBookmark(CONVERSATION_ID);
        }

        @Test
        @DisplayName("G5: cancelling a pause audits with the actor and fires the terminal resume-completed event (null verdict)")
        void awaitingHuman_auditsAndFiresEvent() throws Exception {
            doReturn(true).when(auditLedgerService).isEnabled();
            doReturn(ConversationState.AWAITING_HUMAN)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            // The terminal event fire loads the snapshot to build its payload.
            doReturn(createHitlSnapshot(CONVERSATION_ID, AGENT_ID, Instant.now(), "needs review", "WAIT_INDEFINITELY"))
                    .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            var outcome = conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, "alice");

            assertEquals(IConversationService.CancelOutcome.CANCELLED, outcome);

            // G4-parity audit: the cancel is attributed to the actor with a CANCELLED
            // verdict.
            ArgumentCaptor<ai.labs.eddi.engine.audit.model.AuditEntry> auditCaptor = ArgumentCaptor
                    .forClass(ai.labs.eddi.engine.audit.model.AuditEntry.class);
            verify(auditLedgerService).submit(auditCaptor.capture());
            assertEquals("alice", auditCaptor.getValue().output().get("decidedBy"));
            assertEquals("CANCELLED", auditCaptor.getValue().output().get("verdict"));

            // G5: the terminal resume-completed event fires with a null verdict and the
            // actor.
            ArgumentCaptor<ai.labs.eddi.engine.events.HitlResumeCompletedEvent> eventCaptor = ArgumentCaptor
                    .forClass(ai.labs.eddi.engine.events.HitlResumeCompletedEvent.class);
            verify(hitlResumeCompletedEvent).fireAsync(eventCaptor.capture());
            assertNull(eventCaptor.getValue().verdict());
            assertEquals("alice", eventCaptor.getValue().decidedBy());
            assertNotNull(eventCaptor.getValue().snapshot());
        }

        @Test
        @DisplayName("when in IN_PROGRESS → first CAS fails, second CAS succeeds")
        void inProgress_fallbackCas() throws Exception {
            doReturn(ConversationState.IN_PROGRESS)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doReturn(false).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);

            var outcome = conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            assertEquals(IConversationService.CancelOutcome.CANCELLED, outcome);
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("unknown conversation → NOT_FOUND, no CAS attempted")
        void unknownConversation_notFound() throws Exception {
            doReturn(null).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            var outcome = conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            assertEquals(IConversationService.CancelOutcome.NOT_FOUND, outcome);
            verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), any());
        }

        @Test
        @DisplayName("READY conversation with nothing running → NOTHING_TO_CANCEL")
        void readyConversation_nothingToCancel() throws Exception {
            doReturn(ConversationState.READY)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            // both CAS attempts miss (default false)

            var outcome = conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            assertEquals(IConversationService.CancelOutcome.NOTHING_TO_CANCEL, outcome);
        }

        @Test
        @DisplayName("when store throws ResourceStoreException → propagated to caller")
        void storeException_propagated() throws Exception {
            doReturn(ConversationState.AWAITING_HUMAN)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            doThrow(new ResourceStoreException("db error")).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);

            assertThrows(ResourceStoreException.class,
                    () -> conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL));
        }
    }

    // =========================================================================
    // listPendingApprovals
    // =========================================================================

    @Nested
    @DisplayName("listPendingApprovals")
    class ListPendingApprovals {

        @Test
        @DisplayName("no AWAITING_HUMAN conversations → returns empty list")
        void noAwaitingHuman_returnsEmptyList() throws Exception {
            doReturn(List.of()).when(conversationMemoryStore).findPendingApprovalSummaries(anyInt());

            List<PendingApprovalSummary> result = conversationService.listPendingApprovals(200);

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("delegates to the bounded store projection and returns its summaries")
        void awaitingHumanConversations_returnsCorrectSummaries() throws Exception {
            var summaryA = new PendingApprovalSummary("conv-a", "agent-1", USER_ID,
                    Instant.parse("2026-06-30T10:00:00Z"), "high-risk action", "AUTO_APPROVE");
            var summaryB = new PendingApprovalSummary("conv-b", "agent-2", USER_ID,
                    Instant.parse("2026-06-30T11:00:00Z"), "financial transaction", "WAIT_INDEFINITELY");
            doReturn(List.of(summaryA, summaryB)).when(conversationMemoryStore)
                    .findPendingApprovalSummaries(200);

            List<PendingApprovalSummary> result = conversationService.listPendingApprovals(200);

            assertEquals(2, result.size());

            PendingApprovalSummary first = result.get(0);
            assertEquals("conv-a", first.getConversationId());
            assertEquals("agent-1", first.getAgentId());
            assertEquals(USER_ID, first.getUserId());
            assertEquals(Instant.parse("2026-06-30T10:00:00Z"), first.getPausedAt());
            assertEquals("high-risk action", first.getPauseReason());
            assertEquals("AUTO_APPROVE", first.getTimeoutPolicy());

            PendingApprovalSummary second = result.get(1);
            assertEquals("conv-b", second.getConversationId());
            assertEquals("agent-2", second.getAgentId());
            assertEquals("financial transaction", second.getPauseReason());
            assertEquals("WAIT_INDEFINITELY", second.getTimeoutPolicy());
        }

        @Test
        @DisplayName("limit is clamped to [1, 1000] before reaching the store")
        void limitClamped() throws Exception {
            doReturn(List.of()).when(conversationMemoryStore).findPendingApprovalSummaries(anyInt());

            conversationService.listPendingApprovals(50_000);
            verify(conversationMemoryStore).findPendingApprovalSummaries(1000);

            conversationService.listPendingApprovals(-5);
            verify(conversationMemoryStore).findPendingApprovalSummaries(1);
        }
    }

    // =========================================================================
    // undo/redo HITL gate
    // =========================================================================

    @Nested
    @DisplayName("undo/redo HITL gate")
    class UndoRedoGate {

        private void stubSnapshotInState(ConversationState state) throws Exception {
            var snapshot = createHitlSnapshot(CONVERSATION_ID, AGENT_ID, Instant.now(),
                    "gate", "WAIT_INDEFINITELY");
            snapshot.setConversationState(state);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        }

        @Test
        @DisplayName("undo returns false while AWAITING_HUMAN — nothing stored")
        void undoBlockedWhilePaused() throws Exception {
            stubSnapshotInState(ConversationState.AWAITING_HUMAN);

            assertFalse(conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID),
                    "undo while paused would corrupt the HITL bookmark");
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("redo returns false while AWAITING_HUMAN — nothing stored")
        void redoBlockedWhilePaused() throws Exception {
            stubSnapshotInState(ConversationState.AWAITING_HUMAN);

            assertFalse(conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID),
                    "redo while paused would corrupt the HITL bookmark");
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("undo returns false while IN_PROGRESS (a resume is executing)")
        void undoBlockedWhileInProgress() throws Exception {
            stubSnapshotInState(ConversationState.IN_PROGRESS);

            assertFalse(conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID),
                    "undo during IN_PROGRESS would write a persisted IN_PROGRESS outside the resume CAS");
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("redo returns false while IN_PROGRESS (a resume is executing)")
        void redoBlockedWhileInProgress() throws Exception {
            stubSnapshotInState(ConversationState.IN_PROGRESS);

            assertFalse(conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID),
                    "redo during IN_PROGRESS would race the executing resume");
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a {@link ConversationMemorySnapshot} with HITL metadata.
     */
    private ConversationMemorySnapshot createHitlSnapshot(String conversationId, String agentId,
                                                          Instant pausedAt, String pauseReason,
                                                          String timeoutPolicy) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(conversationId);
        snapshot.setAgentId(agentId);
        snapshot.setAgentVersion(1);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setEnvironment(ENV);
        snapshot.setHitlPausedAt(pausedAt);
        snapshot.setHitlPauseReason(pauseReason);
        snapshot.setHitlTimeoutPolicy(timeoutPolicy);

        // One step with one workflow run
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
