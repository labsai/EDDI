/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.hitl.HitlPlanBinding;
import ai.labs.eddi.engine.hitl.HitlPlanHash;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.Deployment.Environment;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Plan binding on the regular resume ({@link ConversationHitlService}): a
 * decision is checked against the plan hash its pause was committed with, and
 * the stored plan is re-derived and checked before anything runs.
 */
@DisplayName("Resume — plan-hash binding")
class ConversationServicePlanHashTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-plan";
    private static final int AGENT_VERSION = 4;
    private static final String CONVERSATION_ID = "conv-plan-1";
    private static final String USER_ID = "user-plan";
    private static final Instant PAUSED_AT = Instant.ofEpochMilli(1_700_000_000_123L);

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
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        conversationService = new ConversationService(agentFactory, conversationMemoryStore, conversationDescriptorStore, userMemoryStore,
                conversationCoordinator, conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore, jsonSerialization, new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
        doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        lenient().when(runtime.submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(null));
        lenient().when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), any())).thenReturn(true);
        lenient().doReturn(true).when(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.AWAITING_HUMAN,
                ConversationState.IN_PROGRESS);
        lenient().doReturn(true).when(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.IN_PROGRESS,
                ConversationState.AWAITING_HUMAN);
    }

    // ─── fixtures ─────────────────────────────────────────────

    private static ConversationMemorySnapshot ruleSnapshot(List<String> actions) {
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
        snapshot.setHitlPauseType("RULE");
        var step = new ConversationStepSnapshot();
        var workflow = new WorkflowRunSnapshot();
        workflow.getLifecycleTasks().add(new ResultSnapshot("input:initial", "delete my account", null, new Date(), null, true));
        workflow.getLifecycleTasks().add(new ResultSnapshot("actions", new ArrayList<>(actions), null, new Date(), null, true));
        step.getWorkflows().add(workflow);
        snapshot.getConversationSteps().add(step);
        var output = new ConversationOutput();
        output.put("input", "delete my account");
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }

    private static ConversationMemorySnapshot toolSnapshot(String arguments) {
        var snapshot = ruleSnapshot(List.of());
        snapshot.setHitlPauseType("TOOL_CALL");
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId("call-1");
        call.setToolName("transferFunds");
        call.setSource("http");
        call.setArgumentsRaw(arguments);
        call.setArgumentsRedacted(arguments);
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setPlanNonce("nonce-1");
        batch.setCalls(new ArrayList<>(List.of(call)));
        snapshot.setHitlPendingToolCalls(batch);
        return snapshot;
    }

    /**
     * Commits the plan hash exactly as a pause commit would, from the snapshot's
     * memory.
     */
    private static String commit(ConversationMemorySnapshot snapshot) {
        IConversationMemory memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
        String hash = HitlPlanHash.commit(memory);
        snapshot.setHitlPlanHash(hash);
        if (snapshot.getHitlPendingToolCalls() != null) {
            snapshot.getHitlPendingToolCalls().setPlanHash(hash);
        }
        return hash;
    }

    private void deployAgent() throws Exception {
        IAgent agent = mock(IAgent.class);
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(mock(IConversation.class)).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
    }

    private static HitlDecision approve(String planHash) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer-1");
        decision.setPlanHash(planHash);
        return decision;
    }

    private void assertRefusedAndRestored() throws Exception {
        verify(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
        verify(conversationCoordinator, never()).submitInOrder(any(), any());
    }

    // ─── tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("a decision carrying a plan hash")
    class Presented {

        @Test
        @DisplayName("the pause's own hash: resumed, and the binding recorded as MATCHED")
        void matching() throws Exception {
            var snapshot = ruleSnapshot(List.of("delete_account", "PAUSE_CONVERSATION"));
            String hash = commit(snapshot);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            deployAgent();
            var decision = approve(hash);

            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
            assertEquals(hash, decision.getCommittedPlanHash());
            assertEquals(HitlPlanBinding.Outcome.MATCHED.name(), decision.getPlanBinding());
        }

        @Test
        @DisplayName("another plan's hash: refused before the CAS, nothing consumed")
        void mismatchBeforeCas() throws Exception {
            var snapshot = toolSnapshot("{\"amount\":250}");
            commit(snapshot);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            String otherPlan = HitlPlanHash.forToolBatch(CONVERSATION_ID, AGENT_ID, AGENT_VERSION, toolSnapshot("{\"amount\":9999}")
                    .getHitlPendingToolCalls());

            assertThrows(IConversationService.PlanMismatchException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve(otherPlan), null));

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
            verify(conversationCoordinator, never()).submitInOrder(any(), any());
        }

        @Test
        @DisplayName("the plan changes between the pre-check and the CAS: refused on the snapshot that runs, pause restored")
        void mismatchAfterCas() throws Exception {
            var reviewed = toolSnapshot("{\"amount\":250}");
            String reviewedHash = commit(reviewed);
            var changed = toolSnapshot("{\"amount\":9999}");
            commit(changed);
            doReturn(reviewed, changed).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertThrows(IConversationService.PlanMismatchException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve(reviewedHash), null));

            assertRefusedAndRestored();
        }

        @Test
        @DisplayName("a hash for a pause that has none (committed before plan hashing): refused")
        void hashForUnhashedPause() throws Exception {
            var snapshot = ruleSnapshot(List.of("delete_account"));
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertThrows(IConversationService.PlanMismatchException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve("v1:" + "0".repeat(64)), null));
            verify(conversationCoordinator, never()).submitInOrder(any(), any());
        }

        @Test
        @DisplayName("an over-long hash is a malformed request, refused before anything is read")
        void overLong() throws Exception {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve("v1:" + "a".repeat(200)), null));
            verify(conversationMemoryStore, never()).loadConversationMemorySnapshot(any());
            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("the stored plan no longer hashes to its committed value")
    class Tampered {

        @Test
        @DisplayName("tool arguments changed after the pause: refused, pause restored, nothing run")
        void toolArgumentsChanged() throws Exception {
            var snapshot = toolSnapshot("{\"amount\":250}");
            String hash = commit(snapshot);
            snapshot.getHitlPendingToolCalls().getCalls().get(0).setArgumentsRaw("{\"amount\":9999}");
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertThrows(IConversationService.PlanMismatchException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve(hash), null));
            assertRefusedAndRestored();
        }

        @Test
        @DisplayName("rule actions changed after the pause: refused even without a presented hash")
        void ruleActionsChanged() throws Exception {
            var snapshot = ruleSnapshot(List.of("delete_account", "PAUSE_CONVERSATION"));
            commit(snapshot);
            var tampered = ruleSnapshot(List.of("delete_all_accounts", "PAUSE_CONVERSATION"));
            tampered.setHitlPlanHash(snapshot.getHitlPlanHash());
            doReturn(tampered).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertThrows(IConversationService.PlanMismatchException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve(null), null));
            assertRefusedAndRestored();
        }
    }

    @Nested
    @DisplayName("a decision without a plan hash")
    class NotPresented {

        @Test
        @DisplayName("default (non-strict): accepted and recorded as NOT_PRESENTED")
        void nonStrict() throws Exception {
            var snapshot = toolSnapshot("{\"amount\":250}");
            String hash = commit(snapshot);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            deployAgent();
            var decision = approve(null);

            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
            assertEquals(HitlPlanBinding.Outcome.NOT_PRESENTED.name(), decision.getPlanBinding());
            assertEquals(hash, decision.getCommittedPlanHash(), "the audit still records which plan ran");
        }

        @Test
        @DisplayName("strict deployment (eddi.hitl.plan-hash.strict): refused with a 400, pause restored")
        void strictDeployment() throws Exception {
            conversationService.planHashStrict = true;
            var snapshot = toolSnapshot("{\"amount\":250}");
            commit(snapshot);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertThrows(HitlPlanBinding.PlanHashRequired.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve(null), null));
            assertRefusedAndRestored();
        }

        @Test
        @DisplayName("strict agent (hitlConfig.requirePlanHash): refused")
        void strictAgent() throws Exception {
            var agentConfig = new AgentConfiguration();
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setRequirePlanHash(true);
            agentConfig.setHitlConfig(hitl);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, AGENT_VERSION);
            var snapshot = ruleSnapshot(List.of("delete_account"));
            commit(snapshot);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertThrows(HitlPlanBinding.PlanHashRequired.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve(null), null));
            assertRefusedAndRestored();
        }

        @Test
        @DisplayName("strict agent, but the hash is presented: resumed")
        void strictAgentWithHash() throws Exception {
            var agentConfig = new AgentConfiguration();
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setRequirePlanHash(true);
            agentConfig.setHitlConfig(hitl);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, AGENT_VERSION);
            var snapshot = ruleSnapshot(List.of("delete_account"));
            String hash = commit(snapshot);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            deployAgent();

            conversationService.resumeConversation(CONVERSATION_ID, approve(hash), null);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }

        @Test
        @DisplayName("the agent policy cannot be read: treated as strict (fail closed)")
        void unreadablePolicyIsStrict() throws Exception {
            doThrow(new RuntimeException("store down")).when(agentStore).read(anyString(), anyInt());
            var snapshot = ruleSnapshot(List.of("delete_account"));
            commit(snapshot);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertThrows(HitlPlanBinding.PlanHashRequired.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, approve(null), null));
            assertRefusedAndRestored();
        }

        @Test
        @DisplayName("strict, but an automated decision (system:timeout): resumed as SYSTEM")
        void systemDecisionUnderStrict() throws Exception {
            conversationService.planHashStrict = true;
            var snapshot = toolSnapshot("{\"amount\":250}");
            commit(snapshot);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            deployAgent();
            var decision = approve(null);
            decision.setDecidedBy("system:timeout");

            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
            assertEquals(HitlPlanBinding.Outcome.SYSTEM.name(), decision.getPlanBinding());
        }
    }

    @Nested
    @DisplayName("a pause committed before plan hashing (no stored hash)")
    class Legacy {

        @Test
        @DisplayName("accepted as before, also under strict binding, and recorded as LEGACY_UNHASHED")
        void acceptedEvenStrict() throws Exception {
            conversationService.planHashStrict = true;
            var snapshot = toolSnapshot("{\"amount\":250}");
            snapshot.getHitlPendingToolCalls().setPlanNonce(null);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            deployAgent();
            var decision = approve(null);

            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
            assertEquals(HitlPlanBinding.Outcome.LEGACY_UNHASHED.name(), decision.getPlanBinding());
            assertEquals(null, decision.getCommittedPlanHash());
        }
    }

    @Test
    @DisplayName("the pause commit stores the plan hash on the memory before the store")
    void pauseCommitStoresTheHash() {
        var snapshot = toolSnapshot("{\"amount\":250}");
        snapshot.getHitlPendingToolCalls().setPlanNonce(null);
        IConversationMemory memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);

        conversationService.conversationHitlService.populateHitlTimeoutBookmark(memory);

        assertNotNull(memory.getHitlPlanHash());
        assertNotNull(memory.getHitlPendingToolCalls().getPlanNonce());
        assertEquals(memory.getHitlPlanHash(), memory.getHitlPendingToolCalls().getPlanHash());
        assertEquals(memory.getHitlPlanHash(), HitlPlanHash.forPause(memory));
    }
}
