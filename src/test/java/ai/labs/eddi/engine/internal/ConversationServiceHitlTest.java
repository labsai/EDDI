/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
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
    private ICache<String, ConversationState> conversationStateCache;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                new SimpleMeterRegistry(), AGENT_TIMEOUT);
    }

    // =========================================================================
    // cancelConversation
    // =========================================================================

    @Nested
    @DisplayName("cancelConversation")
    class CancelConversation {

        @Test
        @DisplayName("when in AWAITING_HUMAN → CAS succeeds, state set to EXECUTION_INTERRUPTED")
        void awaitingHuman_casSucceeds() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);

            conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            // Should NOT try the IN_PROGRESS CAS since the first one succeeded
            verify(conversationMemoryStore, never()).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("when in IN_PROGRESS → first CAS fails, second CAS succeeds")
        void inProgress_fallbackCas() throws Exception {
            doReturn(false).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);

            conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.EXECUTION_INTERRUPTED);
        }

        @Test
        @DisplayName("when store throws ResourceStoreException → no exception propagated")
        void storeException_noPropagation() throws Exception {
            doThrow(new ResourceStoreException("db error")).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);

            assertDoesNotThrow(() -> conversationService.cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL));
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
            doReturn(List.of()).when(conversationMemoryStore)
                    .findConversationIdsByState(ConversationState.AWAITING_HUMAN);

            List<PendingApprovalSummary> result = conversationService.listPendingApprovals();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns correct summaries for AWAITING_HUMAN conversations")
        void awaitingHumanConversations_returnsCorrectSummaries() throws Exception {
            doReturn(List.of("conv-a", "conv-b")).when(conversationMemoryStore)
                    .findConversationIdsByState(ConversationState.AWAITING_HUMAN);

            var snapshotA = createHitlSnapshot("conv-a", "agent-1",
                    Instant.parse("2026-06-30T10:00:00Z"), "high-risk action", "AUTO_APPROVE");
            var snapshotB = createHitlSnapshot("conv-b", "agent-2",
                    Instant.parse("2026-06-30T11:00:00Z"), "financial transaction", "WAIT_INDEFINITELY");

            doReturn(snapshotA).when(conversationMemoryStore).loadConversationMemorySnapshot("conv-a");
            doReturn(snapshotB).when(conversationMemoryStore).loadConversationMemorySnapshot("conv-b");

            List<PendingApprovalSummary> result = conversationService.listPendingApprovals();

            assertEquals(2, result.size());

            PendingApprovalSummary first = result.get(0);
            assertEquals("conv-a", first.getConversationId());
            assertEquals("agent-1", first.getAgentId());
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
        @DisplayName("skips conversations that throw when loading snapshot")
        void loadException_skipsConversation() throws Exception {
            doReturn(List.of("conv-ok", "conv-broken")).when(conversationMemoryStore)
                    .findConversationIdsByState(ConversationState.AWAITING_HUMAN);

            var snapshotOk = createHitlSnapshot("conv-ok", "agent-1",
                    Instant.now(), "reason", "AUTO_REJECT");
            doReturn(snapshotOk).when(conversationMemoryStore).loadConversationMemorySnapshot("conv-ok");
            doThrow(new RuntimeException("deleted")).when(conversationMemoryStore)
                    .loadConversationMemorySnapshot("conv-broken");

            List<PendingApprovalSummary> result = conversationService.listPendingApprovals();

            assertEquals(1, result.size());
            assertEquals("conv-ok", result.get(0).getConversationId());
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
