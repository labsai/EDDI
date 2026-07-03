/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
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
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 10 — tool-pause timeout policy scoping + no-progress guards + per-guard
 * audit/metrics in {@link ConversationService}.
 * <p>
 * Effective-policy resolution is exercised through the real resume→re-pause
 * path (parity with {@code ConversationServiceResumeTest.TimeoutSchedule}) so
 * the bookmark and schedule are observed via
 * {@code scheduleStore.createSchedule}.
 */
class ConversationServiceToolTimeoutTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-tool-timeout";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-tool-timeout-1";
    private static final String USER_ID = "user-tt";
    private static final int AGENT_TIMEOUT = 30;
    private static final String FINGERPRINT = "fp-abc-123";

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

        doReturn(ConversationState.AWAITING_HUMAN)
                .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

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

        // onComplete persists via the state-guarded store; default: CAS won.
        lenient().when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), any()))
                .thenReturn(true);
    }

    // =========================================================================
    // Rule 1 — effective tool timeout policy in populateHitlTimeoutBookmark
    // =========================================================================

    @Nested
    @DisplayName("effective tool-pause timeout policy")
    class EffectivePolicy {

        @Test
        @DisplayName("inherited AUTO_APPROVE is demoted to WAIT_INDEFINITELY → no finite schedule armed")
        void inheritedAutoApproveDemoted() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("PT30S");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            // toolApprovals present (gate active) but WITHOUT its own timeout policy →
            // inherit the outer values, demoting AUTO_APPROVE.
            var toolApprovals = new ToolApprovalsConfig();
            hitl.setToolApprovals(toolApprovals);

            resumeWithToolRePause(hitl, FINGERPRINT, null);

            // Demotion to WAIT_INDEFINITELY means no finite timeout schedule.
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("explicit tool-level AUTO_APPROVE is honored → finite schedule armed with AUTO_APPROVE policy")
        void explicitToolAutoApproveHonored() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            // Outer inherits nothing useful; the tool-level override sets everything.
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setApprovalTimeout("PT30S");
            toolApprovals.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            hitl.setToolApprovals(toolApprovals);

            resumeWithToolRePause(hitl, FINGERPRINT, null);

            ArgumentCaptor<ScheduleConfiguration> cap = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(cap.capture());
            assertEquals("AUTO_APPROVE", cap.getValue().getMetadata().get("policy"),
                    "explicit tool-level AUTO_APPROVE must be honored, not demoted");
        }

        @Test
        @DisplayName("tool-level approvalTimeout + timeoutPolicy override the outer hitlConfig")
        void toolLevelOverridesOuter() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("PT10M");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.ABORT);
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setApprovalTimeout("PT30S");
            toolApprovals.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            hitl.setToolApprovals(toolApprovals);

            resumeWithToolRePause(hitl, FINGERPRINT, null);

            ArgumentCaptor<ScheduleConfiguration> cap = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(cap.capture());
            assertEquals("AUTO_REJECT", cap.getValue().getMetadata().get("policy"));
        }
    }

    // =========================================================================
    // Rule 2 — timeout decisions route through the normal resume path (journal)
    // =========================================================================

    @Nested
    @DisplayName("timeout decision routes through the normal resume path")
    class TimeoutRouting {

        @Test
        @DisplayName("system:timeout resume runs the standard resume machinery (CAS, agent, submitInOrder) — no shortcut path")
        void systemTimeoutUsesNormalResume() throws Exception {
            var snapshot = toolCallPauseSnapshot(FINGERPRINT, 0);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.REJECTED);
            decision.setDecidedBy("system:timeout");

            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            // The standard resume machinery ran — the CAS consumed the pause, the agent
            // continued the conversation, and the resume callable was submitted in order.
            // This is the journal-bearing path (Tasks 8/9); nothing bypasses it.
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            verify(agent).continueConversation(any(IConversationMemory.class), any(), any());
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }
    }

    // =========================================================================
    // Rule 3 — no-progress guard
    // =========================================================================

    @Nested
    @DisplayName("no-progress guard")
    class NoProgressGuard {

        @Test
        @DisplayName("identical-fingerprint re-pause after a system approval (default policy) → new pause demoted to WAIT_INDEFINITELY + hitl.tool.no_progress audit")
        void identicalFingerprintRePause_waitForHuman() throws Exception {
            doReturn(true).when(auditLedgerService).isEnabled();
            // Pre-resume batch carried autoApproveCount=1 so a second identical re-pause
            // crosses the >=2 threshold.
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("PT30S");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            var toolApprovals = new ToolApprovalsConfig();
            // onNoProgress defaults to WAIT_FOR_HUMAN
            toolApprovals.setApprovalTimeout("PT30S");
            toolApprovals.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            hitl.setToolApprovals(toolApprovals);

            resumeWithToolRePause(hitl, FINGERPRINT, "system:timeout", FINGERPRINT, 1);

            // WAIT_FOR_HUMAN: the re-pause must be demoted to WAIT_INDEFINITELY → no
            // finite schedule armed even though the effective policy is AUTO_REJECT.
            verify(scheduleStore, never()).createSchedule(any());

            // A per-guard audit entry is written.
            assertGuardAudit("hitl.tool.no_progress");
        }

        @Test
        @DisplayName("no-progress with onNoProgress=AUTO_REJECT → follow-up reject-all resume (system:no-progress) enqueued")
        void noProgress_autoReject() throws Exception {
            doReturn(true).when(auditLedgerService).isEnabled();
            var hitl = new AgentConfiguration.HitlConfig();
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setOnNoProgress("AUTO_REJECT");
            toolApprovals.setApprovalTimeout("PT30S");
            toolApprovals.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            hitl.setToolApprovals(toolApprovals);

            AtomicReference<HitlDecision> secondResume = new AtomicReference<>();
            resumeWithToolRePause(hitl, FINGERPRINT, "system:timeout", FINGERPRINT, 1, secondResume, null);

            // A follow-up resume was triggered with reject-all attributed to
            // system:no-progress.
            assertNotNull(secondResume.get(), "a reject-all follow-up resume must be issued");
            assertEquals(HitlVerdict.REJECTED, secondResume.get().getVerdict());
            assertEquals("system:no-progress", secondResume.get().getDecidedBy());
            assertGuardAudit("hitl.tool.no_progress");
        }

        @Test
        @DisplayName("no-progress with onNoProgress=ABORT → conversation cancelled via the existing cancel path")
        void noProgress_abort() throws Exception {
            doReturn(true).when(auditLedgerService).isEnabled();
            var hitl = new AgentConfiguration.HitlConfig();
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setOnNoProgress("ABORT");
            hitl.setToolApprovals(toolApprovals);

            // ABORT cancels via cancelConversation → CAS
            // AWAITING_HUMAN→EXECUTION_INTERRUPTED
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);

            resumeWithToolRePause(hitl, FINGERPRINT, "system:timeout", FINGERPRINT, 1);

            // The existing cancel path terminally resolves the wedged loop.
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
            assertGuardAudit("hitl.tool.no_progress");
        }

        @Test
        @DisplayName("a HUMAN decision resets autoApproveCount to 0 (fresh budget) on re-pause")
        void humanDecisionResetsCounter() throws Exception {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setApprovalTimeout("PT30S");
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setApprovalTimeout("PT30S");
            toolApprovals.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            hitl.setToolApprovals(toolApprovals);

            AtomicReference<PendingToolCallBatch> newBatch = new AtomicReference<>();
            // Human decision, identical fingerprint, pre-existing autoApproveCount=5.
            resumeWithToolRePause(hitl, FINGERPRINT, "reviewer-1", FINGERPRINT, 5, null, newBatch);

            // Human resets the loop counter — the fresh pause carries 0, and the guard
            // does NOT fire (a finite schedule is armed as normal).
            assertNotNull(newBatch.get());
            assertEquals(0, newBatch.get().getAutoApproveCount(),
                    "a human decision must reset autoApproveCount (group fresh-budget convention)");
            verify(scheduleStore).createSchedule(any());
        }
    }

    // =========================================================================
    // Rule 5 — auditHitlDecision detail extension (argsDigest, never raw args)
    // =========================================================================

    @Nested
    @DisplayName("auditHitlDecision detail for TOOL_CALL pauses")
    class AuditDetail {

        @Test
        @DisplayName("TOOL_CALL audit detail carries pauseType + toolDecisions with a SHA-256 argsDigest, never raw args")
        void toolCallAuditCarriesArgsDigestNotRawArgs() throws Exception {
            doReturn(true).when(auditLedgerService).isEnabled();
            var snapshot = toolCallPauseSnapshot(FINGERPRINT, 0);
            doReturn(true).when(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setDecidedBy("reviewer-1");

            conversationService.resumeConversation(CONVERSATION_ID, decision, null);

            ArgumentCaptor<AuditEntry> cap = ArgumentCaptor.forClass(AuditEntry.class);
            verify(auditLedgerService, atLeastOnce()).submit(cap.capture());
            AuditEntry approval = cap.getAllValues().stream()
                    .filter(e -> "hitl.approval".equals(e.taskId()))
                    .findFirst().orElseThrow(() -> new AssertionError("no hitl.approval entry"));

            assertEquals("TOOL_CALL", approval.output().get("pauseType"));

            Object toolDecisionsObj = approval.output().get("toolDecisions");
            assertInstanceOf(List.class, toolDecisionsObj, "toolDecisions summary must be present");
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> toolDecisions = (List<java.util.Map<String, Object>>) toolDecisionsObj;
            assertFalse(toolDecisions.isEmpty());

            var first = toolDecisions.get(0);
            assertNotNull(first.get("callId"));
            assertNotNull(first.get("verdict"));
            assertNotNull(first.get("toolName"));
            String argsDigest = (String) first.get("argsDigest");
            assertNotNull(argsDigest, "per-call argsDigest (SHA-256) required");
            assertEquals(64, argsDigest.length(), "SHA-256 hex digest is 64 chars");

            // The raw arguments must NEVER appear anywhere in the audit detail.
            String detailStr = approval.output().toString();
            assertFalse(detailStr.contains("\"amount\":50"),
                    "raw tool arguments must never be written to the ledger");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assertGuardAudit(String action) {
        ArgumentCaptor<AuditEntry> cap = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLedgerService, atLeastOnce()).submit(cap.capture());
        boolean found = cap.getAllValues().stream().anyMatch(e -> action.equals(e.taskId()));
        assertTrue(found, "expected a per-guard audit entry with action=" + action
                + " but saw: " + cap.getAllValues().stream().map(AuditEntry::taskId).toList());
    }

    /**
     * Full resume where the resumed turn RE-PAUSES with a fresh TOOL_CALL batch.
     */
    private void resumeWithToolRePause(AgentConfiguration.HitlConfig hitlConfig, String prePauseFingerprint,
                                       String decidedBy)
            throws Exception {
        resumeWithToolRePause(hitlConfig, prePauseFingerprint, decidedBy, prePauseFingerprint, 0, null, null);
    }

    private void resumeWithToolRePause(AgentConfiguration.HitlConfig hitlConfig, String prePauseFingerprint,
                                       String decidedBy, String rePauseFingerprint, int prePauseAutoApproveCount)
            throws Exception {
        resumeWithToolRePause(hitlConfig, prePauseFingerprint, decidedBy, rePauseFingerprint,
                prePauseAutoApproveCount, null, null);
    }

    private void resumeWithToolRePause(AgentConfiguration.HitlConfig hitlConfig, String prePauseFingerprint,
                                       String decidedBy, String rePauseFingerprint, int prePauseAutoApproveCount,
                                       AtomicReference<HitlDecision> secondResumeCapture)
            throws Exception {
        resumeWithToolRePause(hitlConfig, prePauseFingerprint, decidedBy, rePauseFingerprint,
                prePauseAutoApproveCount, secondResumeCapture, null);
    }

    /**
     * Drives a full resume whose resumed turn RE-PAUSES on a fresh TOOL_CALL batch
     * carrying {@code rePauseFingerprint}. The pre-resume snapshot carries
     * {@code prePauseFingerprint} and {@code prePauseAutoApproveCount}.
     */
    @SuppressWarnings("unchecked")
    private void resumeWithToolRePause(AgentConfiguration.HitlConfig hitlConfig, String prePauseFingerprint,
                                       String decidedBy, String rePauseFingerprint, int prePauseAutoApproveCount,
                                       AtomicReference<HitlDecision> secondResumeCapture,
                                       AtomicReference<PendingToolCallBatch> newBatchCapture)
            throws Exception {
        doReturn(true).when(conversationMemoryStore).compareAndSetState(
                CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);

        var snapshot = toolCallPauseSnapshot(prePauseFingerprint, prePauseAutoApproveCount);
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
        // The resumed turn re-pauses on a NEW tool-call batch — but ONLY for an
        // APPROVED verdict. A REJECTED resume (the no-progress AUTO_REJECT follow-up)
        // short-circuits in the real Conversation.resume() and settles terminally, so
        // it must NOT re-pause here (mirrors reality + avoids test recursion). The
        // reject-all decision is captured for the AUTO_REJECT assertion.
        doAnswer(inv -> {
            HitlDecision applied = inv.getArgument(0);
            if (applied.getVerdict() == HitlVerdict.REJECTED) {
                if (secondResumeCapture != null && "system:no-progress".equals(applied.getDecidedBy())) {
                    secondResumeCapture.set(applied);
                }
                var memory = memoryRef.get();
                memory.setConversationState(ConversationState.READY);
                return null;
            }
            var memory = memoryRef.get();
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("workflow-1");
            memory.setHitlPausedAbsoluteTaskIndex(4);
            memory.setHitlPausedAt(Instant.now());
            memory.setHitlPauseReason("gated tool call");
            memory.setHitlPauseType("TOOL_CALL");
            var newBatch = new PendingToolCallBatch();
            newBatch.setPauseEpoch("epoch-2");
            newBatch.setFingerprint(rePauseFingerprint);
            newBatch.setAutoApproveCount(0);
            newBatch.setCalls(List.of(pendingCall("call-xyz", false)));
            memory.setHitlPendingToolCalls(newBatch);
            if (newBatchCapture != null) {
                newBatchCapture.set(newBatch);
            }
            return null;
        }).when(conversation).resume(any(HitlDecision.class));

        HitlDecision decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setDecidedBy(decidedBy);

        conversationService.resumeConversation(CONVERSATION_ID, decision, null);

        // Drain every coordinator callable in order — the first is the outer resume
        // (whose onComplete runs the guard); a no-progress AUTO_REJECT enqueues a
        // second (follow-up reject-all) resume that must also run.
        drainCoordinatorCallables();
    }

    /**
     * Runs every callable submitted to the coordinator, including any enqueued
     * during draining.
     */
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
            // Run only the not-yet-run callables; running one may enqueue more.
            int start = ran;
            for (int i = start; i < all.size(); i++) {
                all.get(i).call();
                ran++;
            }
        }
    }

    private PendingToolCall pendingCall(String callId, boolean truncated) {
        var call = new PendingToolCall();
        call.setCallId(callId);
        call.setToolName("delete_record");
        call.setSource("mcp");
        call.setArgumentsRaw("{\"amount\":50}");
        call.setArgumentsRedacted("{\"amount\":50}");
        call.setArgsTruncated(truncated);
        call.setGateReason("mcp:*");
        return call;
    }

    private ConversationMemorySnapshot toolCallPauseSnapshot(String fingerprint, int autoApproveCount) {
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
        snapshot.setHitlPauseReason("gated tool call");
        snapshot.setHitlTimeoutPolicy("AUTO_REJECT");
        snapshot.setHitlPauseType("TOOL_CALL");

        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llm-task-1");
        batch.setFingerprint(fingerprint);
        batch.setAutoApproveCount(autoApproveCount);
        List<PendingToolCall> calls = new ArrayList<>();
        calls.add(pendingCall("call-abc", false));
        batch.setCalls(calls);
        snapshot.setHitlPendingToolCalls(batch);

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
