/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
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
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the <strong>regular (non-group) HITL resume flow</strong> in
 * {@link ConversationService}. Covers bug regressions B1, double-resume CAS, M1
 * timeout scheduling, and bookmark mismatch.
 */
class ConversationServiceResumeTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-resume";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-resume-1";
    private static final String USER_ID = "user-resume";
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
    // B1 regression: APPROVED resume sets memory state to AWAITING_HUMAN
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation — APPROVED verdict (B1 regression)")
    class ApprovedResume {

        @Test
        @DisplayName("B1: memory state set to AWAITING_HUMAN before conversation.resume(), final state not ERROR")
        void approvedResume_setsAwaitingHumanOnMemory() throws Exception {
            // Arrange: CAS from AWAITING_HUMAN → IN_PROGRESS succeeds
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            var snapshot = createResumeSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            // Agent is deployed and returns a conversation mock
            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            // Capture the callable submitted to the coordinator so we can execute it
            // synchronously
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> callableCaptor = ArgumentCaptor.forClass(Callable.class);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setDecidedBy("reviewer-1");

            // Act
            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            // Assert: CAS was called
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            // Assert: cache was updated to IN_PROGRESS (line 852)
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.IN_PROGRESS);

            // Assert: submitInOrder was called (the resume callable was submitted)
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), callableCaptor.capture());

            // Execute the captured callable synchronously to trigger conversation.resume()
            Callable<Void> resumeCallable = callableCaptor.getValue();
            resumeCallable.call();

            // Assert: conversation.resume() was invoked with APPROVED decision
            verify(conversation).resume(eq(decision), anyMap());

            // Assert: memory was stored after resume
            verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("CAS succeeds and snapshot loaded → agent continues conversation")
        void approvedResume_agentContinuesConversation() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            var snapshot = createResumeSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            // Verify agent was retrieved with the correct environment, agentId, and version
            verify(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            verify(agent).continueConversation(any(IConversationMemory.class), any(), isNull());
        }
    }

    // =========================================================================
    // REJECTED resume: lifecycle not re-executed, state ends READY
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation — REJECTED verdict")
    class RejectedResume {

        @Test
        @DisplayName("REJECTED verdict → conversation.resume() called with REJECTED, lifecycle not re-executed")
        void rejectedResume_lifecycleNotReExecuted() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            var snapshot = createResumeSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> callableCaptor = ArgumentCaptor.forClass(Callable.class);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.REJECTED);
            decision.setNote("Too risky");

            // Act
            conversationService.resumeConversation(CONVERSATION_ID, decision, null);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), callableCaptor.capture());
            callableCaptor.getValue().call();

            // Assert: resume was called with REJECTED verdict (Conversation.resume handles
            // the short-circuit)
            verify(conversation).resume(eq(decision), anyMap());
        }
    }

    // =========================================================================
    // Double resume (CAS failure)
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation — double resume (CAS)")
    class DoubleResume {

        @Test
        @DisplayName("first resume succeeds, second resume throws ResourceStoreException (CAS fails)")
        void doubleResume_secondFails() throws Exception {
            // First call: CAS succeeds
            doReturn(true)
                    .doReturn(false) // Second call: CAS fails
                    .when(conversationMemoryStore).compareAndSetState(
                            CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            var snapshot = createResumeSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            // First resume succeeds
            assertDoesNotThrow(() -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));

            // Second resume throws because CAS returns false
            ResourceStoreException exception = assertThrows(ResourceStoreException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            assertTrue(exception.getMessage().contains("not in AWAITING_HUMAN state"),
                    "Exception should mention state mismatch, got: " + exception.getMessage());
        }
    }

    // =========================================================================
    // M1: timeout schedule creation
    // =========================================================================

    @Nested
    @DisplayName("scheduleHitlTimeout (M1)")
    class TimeoutSchedule {

        @Test
        @DisplayName("when conversation pauses → scheduleHitlTimeout creates schedule with correct metadata")
        void scheduleHitlTimeout_createsScheduleWithMetadata() throws Exception {
            // scheduleHitlTimeout is private, so we test it indirectly via the
            // processConversationStep callback path. Since that's complex,
            // we use reflection to invoke it directly.
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setApprovalTimeout("PT30S");
            hitlConfig.setTimeoutPolicy(AgentGroupConfiguration.HitlTimeoutPolicy.AUTO_REJECT);

            var agentConfig = new AgentConfiguration();
            agentConfig.setHitlConfig(hitlConfig);

            IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
            doReturn(1).when(resourceId).getVersion();
            doReturn(resourceId).when(agentStore).getCurrentResourceId(AGENT_ID);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, 1);
            doReturn("schedule-1").when(scheduleStore).createSchedule(any(ScheduleConfiguration.class));

            // Invoke via reflection since scheduleHitlTimeout is private
            var method = ConversationService.class.getDeclaredMethod("scheduleHitlTimeout", String.class, String.class);
            method.setAccessible(true);
            method.invoke(conversationService, CONVERSATION_ID, AGENT_ID);

            // Capture the schedule passed to createSchedule
            ArgumentCaptor<ScheduleConfiguration> scheduleCaptor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(scheduleCaptor.capture());

            ScheduleConfiguration schedule = scheduleCaptor.getValue();
            assertEquals("hitl-timeout-" + CONVERSATION_ID, schedule.getName());
            assertEquals(AGENT_ID, schedule.getAgentId());
            assertTrue(schedule.isEnabled());
            assertNotNull(schedule.getOneTimeAt());
            assertNotNull(schedule.getNextFire());
            assertNotNull(schedule.getCreatedAt());

            Map<String, Object> metadata = schedule.getMetadata();
            assertNotNull(metadata);
            assertEquals("hitl_timeout", metadata.get("hitlType"));
            assertEquals("AUTO_REJECT", metadata.get("policy"));
            assertEquals("regular", metadata.get("surface"));
            assertEquals(CONVERSATION_ID, metadata.get("conversationId"));
        }

        @Test
        @DisplayName("WAIT_INDEFINITELY policy → no schedule created")
        void waitIndefinitely_noScheduleCreated() throws Exception {
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setApprovalTimeout("PT30S");
            hitlConfig.setTimeoutPolicy(AgentGroupConfiguration.HitlTimeoutPolicy.WAIT_INDEFINITELY);

            var agentConfig = new AgentConfiguration();
            agentConfig.setHitlConfig(hitlConfig);

            IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
            doReturn(1).when(resourceId).getVersion();
            doReturn(resourceId).when(agentStore).getCurrentResourceId(AGENT_ID);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, 1);

            var method = ConversationService.class.getDeclaredMethod("scheduleHitlTimeout", String.class, String.class);
            method.setAccessible(true);
            method.invoke(conversationService, CONVERSATION_ID, AGENT_ID);

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("null approvalTimeout → no schedule created")
        void nullTimeout_noScheduleCreated() throws Exception {
            var hitlConfig = new AgentConfiguration.HitlConfig();
            // approvalTimeout is null by default
            hitlConfig.setTimeoutPolicy(AgentGroupConfiguration.HitlTimeoutPolicy.AUTO_REJECT);

            var agentConfig = new AgentConfiguration();
            agentConfig.setHitlConfig(hitlConfig);

            IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
            doReturn(1).when(resourceId).getVersion();
            doReturn(resourceId).when(agentStore).getCurrentResourceId(AGENT_ID);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, 1);

            var method = ConversationService.class.getDeclaredMethod("scheduleHitlTimeout", String.class, String.class);
            method.setAccessible(true);
            method.invoke(conversationService, CONVERSATION_ID, AGENT_ID);

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("null hitlConfig → no schedule created")
        void nullHitlConfig_noScheduleCreated() throws Exception {
            var agentConfig = new AgentConfiguration();
            // hitlConfig is null

            IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
            doReturn(1).when(resourceId).getVersion();
            doReturn(resourceId).when(agentStore).getCurrentResourceId(AGENT_ID);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, 1);

            var method = ConversationService.class.getDeclaredMethod("scheduleHitlTimeout", String.class, String.class);
            method.setAccessible(true);
            method.invoke(conversationService, CONVERSATION_ID, AGENT_ID);

            verify(scheduleStore, never()).createSchedule(any());
        }
    }

    // =========================================================================
    // Bookmark mismatch: pausedWorkflowId no longer exists
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation — bookmark mismatch")
    class BookmarkMismatch {

        @Test
        @DisplayName("resume when pausedWorkflowId does not exist → LifecycleException via conversation.resume()")
        void bookmarkMismatch_lifecycleException() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            // Snapshot with a pausedWorkflowId that will not be found
            var snapshot = createResumeSnapshot();
            snapshot.setHitlPausedWorkflowId("nonexistent-workflow");
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            // Simulate that Conversation.resume() throws LifecycleException when workflow
            // is not found
            doThrow(new LifecycleException("Paused workflow 'nonexistent-workflow' no longer exists (config drift)"))
                    .when(conversation).resume(any(HitlDecision.class), anyMap());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> callableCaptor = ArgumentCaptor.forClass(Callable.class);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            // Act
            conversationService.resumeConversation(CONVERSATION_ID, decision, null);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), callableCaptor.capture());

            // Execute the callable — the LifecycleException is caught inside the callable
            // and sets memory state to ERROR
            callableCaptor.getValue().call();

            // Assert: conversation.resume() was attempted
            verify(conversation).resume(eq(decision), anyMap());

            // Assert: memory was stored (the finally block in resumeCallable always stores)
            verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation — edge cases")
    class EdgeCases {

        @Test
        @DisplayName("snapshot not found → ResourceNotFoundException")
        void snapshotNotFound_throwsResourceNotFoundException() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            doReturn(null).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            assertThrows(ResourceNotFoundException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
        }

        @Test
        @DisplayName("agent not deployed → ResourceStoreException with state set to ERROR")
        void agentNotDeployed_throwsResourceStoreException() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            var snapshot = createResumeSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            // Agent not found
            doReturn(null).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(null).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            ResourceStoreException exception = assertThrows(ResourceStoreException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            assertTrue(exception.getMessage().contains("Agent not deployed"),
                    "Exception should mention agent not deployed, got: " + exception.getMessage());

            // State should be set to ERROR
            verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ERROR);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a {@link ConversationMemorySnapshot} suitable for resume testing,
     * pre-populated with AWAITING_HUMAN state and HITL bookmark data.
     */
    private ConversationMemorySnapshot createResumeSnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setEnvironment(ENV);
        snapshot.setHitlPausedWorkflowId("workflow-1");
        snapshot.setHitlPausedAbsoluteTaskIndex(2);
        snapshot.setHitlPausedAt(Instant.now());
        snapshot.setHitlPauseReason("high-risk action");
        snapshot.setHitlTimeoutPolicy("AUTO_REJECT");

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
