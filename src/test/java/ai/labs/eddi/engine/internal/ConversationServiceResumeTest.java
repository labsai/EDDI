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
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
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
    private IJsonSerialization jsonSerialization;
    @Mock
    private ICache<String, ConversationState> conversationStateCache;

    private ConversationService conversationService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(), AGENT_TIMEOUT);

        // The resume path pre-checks existence via getConversationState (404 vs 409)
        doReturn(ConversationState.AWAITING_HUMAN)
                .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

        // The resume path wraps execution with the say-path watchdog: execute the
        // submitted callable inline and hand back a completed future.
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

        // Resume onComplete persists via an atomic compare-and-store guarded on
        // IN_PROGRESS (so a concurrent end/cancel cannot be clobbered). Default it to
        // "won the CAS" so the normal resume paths persist and schedule as before; the
        // terminal-writer-wins branch is exercised by stubbing this to false.
        lenient().when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), any()))
                .thenReturn(true);
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

            // B1 reality: the CAS has ALREADY flipped the stored document to
            // IN_PROGRESS by the time the snapshot is loaded — the service must
            // set the in-memory state back to AWAITING_HUMAN itself, or the real
            // Conversation.resume() guard rejects the resume.
            var snapshot = createResumeSnapshot();
            snapshot.setConversationState(ConversationState.IN_PROGRESS);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            // Agent is deployed; capture the memory handed to continueConversation
            // and record its state AT CALL TIME (post-B1-fix it must be AWAITING_HUMAN)
            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            var stateAtContinueTime = new java.util.concurrent.atomic.AtomicReference<ConversationState>();
            doAnswer(inv -> {
                IConversationMemory memoryArg = inv.getArgument(0);
                stateAtContinueTime.set(memoryArg.getConversationState());
                return conversation;
            }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

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

            // B1 fail-on-revert: the memory built from the post-CAS (IN_PROGRESS)
            // snapshot must have been reset to AWAITING_HUMAN before the
            // Conversation was built — deleting the fix makes this assertion fail.
            assertEquals(ConversationState.AWAITING_HUMAN, stateAtContinueTime.get(),
                    "memory must be AWAITING_HUMAN when handed to continueConversation, "
                            + "otherwise the real Conversation.resume() guard bricks the conversation");

            // Assert: cache was updated to IN_PROGRESS
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.IN_PROGRESS);

            // Assert: submitInOrder was called (the resume callable was submitted)
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), callableCaptor.capture());

            // Execute the captured callable synchronously to trigger conversation.resume()
            Callable<Void> resumeCallable = callableCaptor.getValue();
            resumeCallable.call();

            // Assert: conversation.resume() was invoked with APPROVED decision
            verify(conversation).resume(eq(decision));

            // Assert: memory was stored after resume — via the state-guarded store so
            // a concurrent terminal end/cancel can never be clobbered.
            verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
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
            verify(conversation).resume(eq(decision));
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

            // Second resume throws because CAS returns false — a wrong-state
            // conflict is IllegalStateException (409), not a store failure (500)
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            assertTrue(exception.getMessage().contains("not in AWAITING_HUMAN state"),
                    "Exception should mention state mismatch, got: " + exception.getMessage());
        }
    }

    // =========================================================================
    // M1: timeout schedule creation
    // =========================================================================

    @Nested
    @DisplayName("re-pause timeout scheduling (M1) — through the real resume path")
    class TimeoutSchedule {

        /**
         * Drives a full resume where the resumed turn RE-PAUSES, then asserts the
         * schedule behavior. Exercises populateHitlTimeoutBookmark (pinned-version
         * config read) + scheduleHitlTimeout (bookmark-driven) via the onComplete
         * persistence path — no reflection into privates.
         */
        private void resumeWithRePause(AgentConfiguration.HitlConfig hitlConfig) throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

            var snapshot = createResumeSnapshot();
            snapshot.setConversationState(ConversationState.IN_PROGRESS);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            var agentConfig = new AgentConfiguration();
            agentConfig.setHitlConfig(hitlConfig);
            // pinned-version read: the conversation is pinned to AGENT_VERSION
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, AGENT_VERSION);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            var memoryRef = new java.util.concurrent.atomic.AtomicReference<IConversationMemory>();
            doAnswer(inv -> {
                memoryRef.set(inv.getArgument(0));
                return conversation;
            }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
            // the resumed turn re-pauses: Conversation.resume leaves the memory
            // in AWAITING_HUMAN with a fresh bookmark
            doAnswer(inv -> {
                var memory = memoryRef.get();
                memory.setConversationState(ConversationState.AWAITING_HUMAN);
                memory.setHitlPausedWorkflowId("workflow-1");
                memory.setHitlPausedAbsoluteTaskIndex(4);
                memory.setHitlPausedAt(Instant.now());
                memory.setHitlPauseReason("PAUSE_CONVERSATION action");
                return null;
            }).when(conversation).resume(any(HitlDecision.class));

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> callableCaptor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), callableCaptor.capture());
            callableCaptor.getValue().call();
        }

        @Test
        @DisplayName("re-pause with finite policy → schedule created with bookmark-driven metadata")
        void rePauseCreatesScheduleWithMetadata() throws Exception {
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setApprovalTimeout("PT30S");
            hitlConfig.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);

            resumeWithRePause(hitlConfig);

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

            // and the re-paused memory was persisted with the policy bookmark, via the
            // state-guarded store
            verify(conversationMemoryStore, atLeastOnce()).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
        }

        @Test
        @DisplayName("re-pause with WAIT_INDEFINITELY policy → no schedule created")
        void waitIndefinitely_noScheduleCreated() throws Exception {
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setApprovalTimeout("PT30S");
            hitlConfig.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);

            resumeWithRePause(hitlConfig);

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("re-pause with finite policy but null approvalTimeout → no schedule created")
        void nullTimeout_noScheduleCreated() throws Exception {
            var hitlConfig = new AgentConfiguration.HitlConfig();
            // approvalTimeout is null by default
            hitlConfig.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);

            resumeWithRePause(hitlConfig);

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("re-pause with no hitlConfig at all → no schedule created")
        void nullHitlConfig_noScheduleCreated() throws Exception {
            resumeWithRePause(null);

            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("resume deletes the pending timeout schedule by name before executing (MAJOR-3)")
        void resumeDeletesTimeoutScheduleByName() throws Exception {
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

            // The stale one-shot fire must be disarmed so it cannot auto-decide a
            // conversation a human already resumed; deleted by the conversation-scoped
            // schedule name.
            verify(scheduleStore).deleteSchedulesByName("hitl-timeout-" + CONVERSATION_ID);
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
                    .when(conversation).resume(any(HitlDecision.class));

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
            verify(conversation).resume(eq(decision));

            // Assert: memory was stored (onComplete persists the ERROR outcome via the
            // state-guarded store)
            verify(conversationMemoryStore).storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));
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
        @DisplayName("agent not deployed → IllegalStateException (409) and the pause is RESTORED (not ERROR)")
        void agentNotDeployed_throwsResourceStoreException() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);

            var snapshot = createResumeSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            // Agent not found
            doReturn(null).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            assertTrue(exception.getMessage().contains("Agent not deployed"),
                    "Exception should mention agent not deployed, got: " + exception.getMessage());

            // #7: the pending approval must survive — pause restored via CAS,
            // never destroyed with ERROR
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            verify(conversationMemoryStore, never()).setConversationState(CONVERSATION_ID, ConversationState.ERROR);
            // undeployed agent must NOT re-arm the timeout schedule (would loop
            // timeout→restore→re-arm forever against a missing agent)
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("unexpected RuntimeException from continueConversation → pause RESTORED, wrapped as ResourceStoreException (not stuck IN_PROGRESS)")
        void continueConversationThrowsRuntimeException_restoresPause() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);

            var snapshot = createResumeSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            // An unexpected unchecked failure in the window between the CAS and
            // submitInOrder — must not escape raw or leave the conversation stuck.
            doThrow(new RuntimeException("boom from continueConversation"))
                    .when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            // Wrapped as a store failure (500), not leaked as a raw RuntimeException.
            assertThrows(ResourceStoreException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));

            // The pending approval survives: pause restored via CAS, never ERROR, and
            // the resume was never submitted (the failure happened before submit).
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            verify(conversationMemoryStore, never()).setConversationState(CONVERSATION_ID, ConversationState.ERROR);
            verify(conversationCoordinator, never()).submitInOrder(eq(CONVERSATION_ID), any());
        }

        @Test
        @DisplayName("concurrent end/cancel wins the CAS-store → resume outcome discarded (no re-arm, no clobber)")
        void concurrentTerminalWrite_resumeOutcomeDiscarded() throws Exception {
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            var snapshot = createResumeSnapshot();
            snapshot.setConversationState(ConversationState.IN_PROGRESS);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            var agentConfig = new AgentConfiguration();
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setApprovalTimeout("PT30S");
            hitlConfig.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            agentConfig.setHitlConfig(hitlConfig);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, AGENT_VERSION);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            var memoryRef = new java.util.concurrent.atomic.AtomicReference<IConversationMemory>();
            doAnswer(inv -> {
                memoryRef.set(inv.getArgument(0));
                return conversation;
            }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
            // the resumed turn re-pauses — would normally re-arm a timeout schedule
            doAnswer(inv -> {
                var memory = memoryRef.get();
                memory.setConversationState(ConversationState.AWAITING_HUMAN);
                memory.setHitlPausedWorkflowId("workflow-1");
                memory.setHitlPausedAbsoluteTaskIndex(4);
                memory.setHitlPausedAt(Instant.now());
                memory.setHitlPauseReason("PAUSE_CONVERSATION action");
                return null;
            }).when(conversation).resume(any(HitlDecision.class));

            // A concurrent end/cancel moved the conversation off IN_PROGRESS between
            // the up-front cancelled check and the persist: the atomic CAS-store is
            // rejected, so the resumed outcome must be discarded wholesale.
            doReturn(false).when(conversationMemoryStore)
                    .storeConversationMemorySnapshotIfState(any(), eq(ConversationState.IN_PROGRESS));

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> callableCaptor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), callableCaptor.capture());
            callableCaptor.getValue().call();

            // No re-arm (that would resurrect a conversation the terminal writer just
            // ended) and the unconditional full store is never used on the resume path.
            verify(scheduleStore, never()).createSchedule(any());
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
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
