/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
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
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pre-CAS validation of {@link HitlDecision#getToolDecisions()} in
 * {@link ConversationService#resumeConversation}. Every table row from the Task
 * 7 brief maps to one {@code @Test}: a bad request must throw
 * {@link IllegalArgumentException} (the group-conversation precedent for 400)
 * BEFORE the AWAITING_HUMAN-&gt;IN_PROGRESS CAS ever runs, so a rejected resume
 * never consumes the pause.
 */
class ConversationServiceToolDecisionValidationTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-tooldecision";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-tooldecision-1";
    private static final String USER_ID = "user-tooldecision";
    private static final int AGENT_TIMEOUT = 30;
    private static final String CALL_ABC = "call_abc";
    private static final String CALL_DEF = "call_def";

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
    private final IJsonSerialization jsonSerialization = new JsonSerialization(new ObjectMapper());

    @BeforeEach
    void setUp() {
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
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PendingToolCall pendingCall(String callId, boolean truncated) {
        var call = new PendingToolCall();
        call.setCallId(callId);
        call.setToolName("some_tool");
        call.setSource("mcp");
        call.setArgumentsRaw("{\"amount\":50}");
        call.setArgumentsRedacted("{\"amount\":50}");
        call.setArgsTruncated(truncated);
        call.setGateReason("mcp:*");
        return call;
    }

    private ConversationMemorySnapshot toolCallPauseSnapshot() {
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
        snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        snapshot.setHitlPauseType("TOOL_CALL");

        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llm-task-1");
        batch.setCalls(List.of(pendingCall(CALL_ABC, false), pendingCall(CALL_DEF, false)));
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

    private ConversationMemorySnapshot toolCallPauseSnapshotWithTruncatedCall() {
        var snapshot = toolCallPauseSnapshot();
        snapshot.getHitlPendingToolCalls().setCalls(
                List.of(pendingCall(CALL_ABC, true), pendingCall(CALL_DEF, false)));
        return snapshot;
    }

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
        snapshot.setHitlPausedAt(Instant.now());
        snapshot.setHitlPauseReason("high-risk action");
        snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
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

    private void stubSuccessfulResumeMachinery(ConversationMemorySnapshot snapshot) throws Exception {
        doReturn(true).when(conversationMemoryStore).compareAndSetState(
                CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        IAgent agent = mock(IAgent.class);
        IConversation conversation = mock(IConversation.class);
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
    }

    private ToolCallDecision toolDecision(HitlVerdict verdict, String note, String amendedArguments) {
        var d = new ToolCallDecision();
        d.setVerdict(verdict);
        d.setNote(note);
        d.setAmendedArguments(amendedArguments);
        return d;
    }

    // =========================================================================
    // Validation table
    // =========================================================================

    @Nested
    @DisplayName("toolDecisions present but pause is not TOOL_CALL")
    class NonToolCallPause {

        @Test
        @DisplayName("toolDecisions on a RULE pause -> 400 IllegalArgumentException, CAS never touched")
        void toolDecisionsOnRulePause_rejected() throws Exception {
            var snapshot = rulePauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            assertTrue(ex.getMessage().contains("toolDecisions is only valid for tool-call pauses"),
                    "unexpected message: " + ex.getMessage());

            verify(conversationMemoryStore, never()).compareAndSetState(
                    eq(CONVERSATION_ID), any(), any());
        }

        @Test
        @DisplayName("toolDecisions on a legacy null-pauseType pause -> 400 IllegalArgumentException")
        void toolDecisionsOnNullPauseType_rejected() throws Exception {
            var snapshot = rulePauseSnapshot();
            snapshot.setHitlPauseType(null);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, null)));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }
    }

    @Nested
    @DisplayName("unknown callId")
    class UnknownCallId {

        @Test
        @DisplayName("unknown callId -> 400 IllegalArgumentException naming the pending calls, CAS never touched")
        void unknownCallId_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of("call_xyz", toolDecision(HitlVerdict.APPROVED, null, null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            assertTrue(ex.getMessage().contains("no pending tool call 'call_xyz'"),
                    "unexpected message: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(CALL_ABC) && ex.getMessage().contains(CALL_DEF),
                    "expected pending callIds listed, got: " + ex.getMessage());

            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }
    }

    @Nested
    @DisplayName("per-call verdict missing")
    class PerCallVerdictMissing {

        @Test
        @DisplayName("per-call verdict missing -> 400 IllegalArgumentException, CAS never touched")
        void missingVerdict_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(CALL_ABC, toolDecision(null, null, null)));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }
    }

    @Nested
    @DisplayName("per-call note too long")
    class PerCallNoteTooLong {

        @Test
        @DisplayName("per-call note > 1024 chars -> 400 IllegalArgumentException, CAS never touched")
        void noteTooLong_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            String longNote = "x".repeat(1025);
            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(CALL_ABC, toolDecision(HitlVerdict.APPROVED, longNote, null)));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }
    }

    @Nested
    @DisplayName("amendedArguments on a REJECTED call")
    class AmendedArgumentsOnRejected {

        @Test
        @DisplayName("amendedArguments on a REJECTED call -> 400 IllegalArgumentException, CAS never touched")
        void amendedArgumentsOnRejected_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.REJECTED, "wrong account", "{\"amount\":1}")));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }
    }

    @Nested
    @DisplayName("amendedArguments malformed or oversized")
    class AmendedArgumentsMalformed {

        @Test
        @DisplayName("amendedArguments not parseable JSON -> 400 IllegalArgumentException, CAS never touched")
        void notParseableJson_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, "{not valid json")));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }

        @Test
        @DisplayName("amendedArguments parses to a JSON array (not object) -> 400 IllegalArgumentException")
        void jsonArrayNotObject_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, "[1,2,3]")));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }

        @Test
        @DisplayName("amendedArguments parses to a JSON primitive (not object) -> 400 IllegalArgumentException")
        void jsonPrimitiveNotObject_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, "\"just a string\"")));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }

        @Test
        @DisplayName("amendedArguments exceeds AMENDED_ARGS_MAX_BYTES -> 400 IllegalArgumentException")
        void oversizedAmendedArguments_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            // A JSON object whose serialized size exceeds the 32 KB cap.
            String bigValue = "y".repeat(PendingToolCallBatch.AMENDED_ARGS_MAX_BYTES + 100);
            String oversized = "{\"padding\":\"" + bigValue + "\"}";

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, oversized)));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }
    }

    @Nested
    @DisplayName("amendedArguments on a truncated call")
    class AmendedArgumentsOnTruncated {

        @Test
        @DisplayName("amendedArguments for argsTruncated=true call -> 400 IllegalArgumentException with exact message")
        void truncatedCallAmended_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshotWithTruncatedCall();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, "{\"amount\":1}")));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            assertTrue(ex.getMessage().contains(
                    "call 'call_abc' was truncated at pause time and cannot be amended; approve or reject it as-is"),
                    "unexpected message: " + ex.getMessage());

            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }

        @Test
        @DisplayName("truncated call can still be approved as-is (no amendedArguments) -> validation passes")
        void truncatedCallApprovedWithoutAmend_passesValidation() throws Exception {
            var snapshot = toolCallPauseSnapshotWithTruncatedCall();
            stubSuccessfulResumeMachinery(snapshot);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, null)));

            assertDoesNotThrow(() -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("top-level REJECTED with per-call APPROVED mixing")
    class TopLevelRejectedMixing {

        @Test
        @DisplayName("top-level REJECTED + any per-call APPROVED -> 400 IllegalArgumentException, CAS never touched")
        void topLevelRejectedWithPerCallApproved_rejected() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.REJECTED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, null),
                    CALL_DEF, toolDecision(HitlVerdict.REJECTED, null, null)));

            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore, never()).compareAndSetState(eq(CONVERSATION_ID), any(), any());
        }

        @Test
        @DisplayName("top-level REJECTED + all per-call REJECTED -> validation passes")
        void topLevelRejectedWithAllPerCallRejected_passesValidation() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            stubSuccessfulResumeMachinery(snapshot);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.REJECTED);
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.REJECTED, "no", null),
                    CALL_DEF, toolDecision(HitlVerdict.REJECTED, "no", null)));

            assertDoesNotThrow(() -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("valid bodies pass validation")
    class ValidBodiesPass {

        @Test
        @DisplayName("valid mixed body (approve+amend one call, reject the other) passes validation and reaches the CAS")
        void validMixedBody_passesValidation() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            stubSuccessfulResumeMachinery(snapshot);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setNote("capped the amount");
            decision.setToolDecisions(Map.of(
                    CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, "{\"amount\":100}"),
                    CALL_DEF, toolDecision(HitlVerdict.REJECTED, "wrong account", null)));

            assertDoesNotThrow(() -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));

            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        }

        @Test
        @DisplayName("calls not listed in toolDecisions inherit the top-level verdict (no validation error)")
        void unlistedCallsInheritTopLevelVerdict_passesValidation() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            stubSuccessfulResumeMachinery(snapshot);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            // Only call_abc listed; call_def inherits top-level APPROVED.
            decision.setToolDecisions(Map.of(CALL_ABC, toolDecision(HitlVerdict.APPROVED, null, null)));

            assertDoesNotThrow(() -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        }

        @Test
        @DisplayName("plain {verdict:APPROVED} (no toolDecisions) on a TOOL_CALL pause passes validation unaffected")
        void plainVerdictOnToolCallPause_passesValidation() throws Exception {
            var snapshot = toolCallPauseSnapshot();
            stubSuccessfulResumeMachinery(snapshot);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            assertDoesNotThrow(() -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        }

        @Test
        @DisplayName("plain {verdict:APPROVED} (no toolDecisions) on a RULE pause is unaffected (backward compat)")
        void plainVerdictOnRulePause_passesValidation() throws Exception {
            var snapshot = rulePauseSnapshot();
            stubSuccessfulResumeMachinery(snapshot);

            HitlDecision decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);

            assertDoesNotThrow(() -> conversationService.resumeConversation(CONVERSATION_ID, decision, null));
            verify(conversationMemoryStore).compareAndSetState(
                    CONVERSATION_ID, ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS);
        }
    }
}
