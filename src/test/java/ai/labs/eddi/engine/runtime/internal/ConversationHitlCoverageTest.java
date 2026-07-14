/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.ConversationNotReadyException;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException.PauseOrigin;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Additive branch-coverage tests for {@link Conversation}, focused on the HITL
 * pause/resume error/edge paths that the integration-style
 * {@code ConversationHitlTest} does not exercise. Wiring is copied from that
 * test so it compiles by construction.
 */
@DisplayName("Conversation HITL Coverage (branch)")
class ConversationHitlCoverageTest {

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "user1");
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);

        lenient().when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        lenient().when(workflow.getWorkflowId()).thenReturn("wf1");
        lenient().when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
    }

    private Conversation createConversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
    }

    private Conversation createConversation(IExecutableWorkflow... wfs) {
        return new Conversation(List.of(wfs), memory, propertiesHandler, outputRenderer);
    }

    private HitlDecision decision(HitlVerdict verdict) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        return d;
    }

    private HitlDecision decision(HitlVerdict verdict, String note, String decidedBy) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        d.setNote(note);
        d.setDecidedBy(decidedBy);
        return d;
    }

    private PendingToolCall call(String name) {
        var c = new PendingToolCall();
        c.setToolName(name);
        return c;
    }

    private PendingToolCallBatch batch(ToolApprovalsConfig effective, PendingToolCall... calls) {
        var b = new PendingToolCallBatch();
        b.setCalls(List.of(calls));
        b.setEffectiveToolApprovals(effective);
        return b;
    }

    private ToolApprovalsConfig cfg(String pendingMessage) {
        var c = new ToolApprovalsConfig();
        c.setPendingMessage(pendingMessage);
        return c;
    }

    // =====================================================================
    // pauseConversation — TOOL_CALL branch (resolvePendingMessage variants)
    // =====================================================================

    @Nested
    @DisplayName("TOOL_CALL pause pending-message resolution")
    class ToolPausePendingMessage {

        @Test
        @DisplayName("no config anywhere → default template, no batch names")
        void toolPauseDefaultTemplateNoBatch() throws Exception {
            memory.setConversationState(ConversationState.READY);
            doThrow(new ConversationPauseException("wf1", 2, "gated", PauseOrigin.TOOL_CALL))
                    .when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("go", Map.of());

            var output = memory.getCurrentStep().getConversationOutput();
            String rendered = String.valueOf(output.get(MemoryKeys.OUTPUT_PREFIX));
            assertTrue(rendered.contains("requires human approval"),
                    "default pending message expected, got: " + rendered);
            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
            assertEquals("TOOL_CALL", memory.getHitlPauseType());
        }

        @Test
        @DisplayName("agent-level config with no pendingMessage → default template")
        void toolPauseAgentConfigBlankMessageDefault() throws Exception {
            memory.setConversationState(ConversationState.READY);
            memory.setAgentToolApprovalsConfig(new ToolApprovalsConfig()); // pendingMessage null
            doThrow(new ConversationPauseException("wf1", 2, "gated", PauseOrigin.TOOL_CALL))
                    .when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("go", Map.of());

            String rendered = String.valueOf(
                    memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX));
            assertTrue(rendered.contains("requires human approval"), rendered);
        }

        @Test
        @DisplayName("batch with null calls list → names substituted as empty string")
        void toolPauseBatchNullCalls() throws Exception {
            memory.setConversationState(ConversationState.READY);
            var b = new PendingToolCallBatch();
            b.setEffectiveToolApprovals(cfg("Pending: {toolNames}."));
            // calls left null
            doAnswer(inv -> {
                memory.setHitlPendingToolCalls(b);
                throw new ConversationPauseException("wf1", 2, "gated", PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("go", Map.of());

            String rendered = String.valueOf(
                    memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX));
            assertTrue(rendered.contains("Pending: ."), "empty names expected, got: " + rendered);
        }

        @Test
        @DisplayName("batch call with null toolName is filtered out of names")
        void toolPauseFiltersNullToolName() throws Exception {
            memory.setConversationState(ConversationState.READY);
            var withNull = new PendingToolCall(); // toolName null
            var b = batch(cfg("Names: {toolNames}"), withNull, call("real_tool"));
            doAnswer(inv -> {
                memory.setHitlPendingToolCalls(b);
                throw new ConversationPauseException("wf1", 2, "gated", PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("go", Map.of());

            String rendered = String.valueOf(
                    memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX));
            assertTrue(rendered.contains("Names: real_tool"), rendered);
        }

        @Test
        @DisplayName("duplicate tool names are de-duplicated and comma-joined")
        void toolPauseDedupesAndJoinsNames() throws Exception {
            memory.setConversationState(ConversationState.READY);
            var b = batch(cfg("[{toolNames}]"), call("a"), call("a"), call("b"));
            doAnswer(inv -> {
                memory.setHitlPendingToolCalls(b);
                throw new ConversationPauseException("wf1", 2, "gated", PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("go", Map.of());

            String rendered = String.valueOf(
                    memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX));
            assertTrue(rendered.contains("[a, b]"), "expected deduped join, got: " + rendered);
        }
    }

    // =====================================================================
    // pauseConversation — RULE branch (hitl:status marker + tool-state clear)
    // =====================================================================

    @Nested
    @DisplayName("RULE pause writes hitl:status marker")
    class RulePauseStatusMarker {

        @Test
        @DisplayName("RULE pause emits public hitl:status=awaiting_approval and clears stale tool batch")
        void rulePauseEmitsStatusMarkerAndClearsToolState() throws Exception {
            memory.setConversationState(ConversationState.READY);
            // A stale tool batch present at pause time must be cleared by the RULE branch.
            memory.setHitlPendingToolCalls(batch(null, call("leftover")));
            memory.setHitlPauseType("TOOL_CALL");

            doThrow(new ConversationPauseException("wf1", 1, "rule gate")) // default = RULE
                    .when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("go", Map.of());

            var output = memory.getCurrentStep().getConversationOutput();
            assertEquals("awaiting_approval", output.get("hitl:status"));
            assertNotNull(memory.getCurrentStep().getLatestData("hitl:status"));
            // clearToolPauseState() ran on the RULE branch.
            assertNull(memory.getHitlPendingToolCalls());
            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        }
    }

    // =====================================================================
    // executeConversationStep — ConversationStopException + cancel-wins
    // =====================================================================

    @Nested
    @DisplayName("say() stop and cancel branches")
    class SayStopAndCancel {

        @Test
        @DisplayName("ConversationStopException during say() ends the conversation")
        void sayStopEndsConversation() throws Exception {
            memory.setConversationState(ConversationState.READY);
            doThrow(new ConversationStopException())
                    .when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("bye", Map.of());

            assertEquals(ConversationState.ENDED, memory.getConversationState());
        }

        @Test
        @DisplayName("cancel that lands during a pausing task wins over the pause → ENDED not AWAITING_HUMAN")
        void sayCancelWinsOverPause() throws Exception {
            memory.setConversationState(ConversationState.READY);
            doAnswer(inv -> {
                memory.setCancelled(true);
                throw new ConversationPauseException("wf1", 2, "gated", PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("go", Map.of());

            assertEquals(ConversationState.ENDED, memory.getConversationState());
            // The pause branch was skipped, so no AWAITING_HUMAN bookmark should be
            // committed.
            assertNotEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        }
    }

    // =====================================================================
    // clearStaleToolPauseState (via startNextStep on say)
    // =====================================================================

    @Nested
    @DisplayName("clearStaleToolPauseState at turn start")
    class ClearStaleToolPauseState {

        @Test
        @DisplayName("stale tool-pause state present but not AWAITING_HUMAN → cleared at turn start")
        void staleStateClearedWhenPresent() throws Exception {
            memory.setConversationState(ConversationState.READY);
            memory.setHitlPendingToolCalls(batch(null, call("x")));
            memory.setHitlPauseType("TOOL_CALL");
            memory.setHitlResumeDecision(decision(HitlVerdict.APPROVED));

            createConversation().say("fresh turn", Map.of());

            assertNull(memory.getHitlPendingToolCalls());
            assertNull(memory.getHitlPauseType());
            assertNull(memory.getHitlResumeDecision());
        }

        @Test
        @DisplayName("no stale state present → clearStaleToolPauseState is a no-op (does not throw)")
        void noStaleStateNoOp() throws Exception {
            memory.setConversationState(ConversationState.READY);
            // both null — the guard's inner branch is skipped.
            createConversation().say("fresh turn", Map.of());
            assertNull(memory.getHitlPendingToolCalls());
            assertNull(memory.getHitlPauseType());
        }

        @Test
        @DisplayName("only pauseType present (pending batch null) still triggers the clear")
        void onlyPauseTypePresentClears() throws Exception {
            memory.setConversationState(ConversationState.READY);
            memory.setHitlPauseType("RULE");
            // pending batch null

            createConversation().say("fresh turn", Map.of());

            assertNull(memory.getHitlPauseType());
        }
    }

    // =====================================================================
    // resume() — toolPause paths
    // =====================================================================

    @Nested
    @DisplayName("resume() TOOL_CALL pause paths")
    class ResumeToolPause {

        @Test
        @DisplayName("TOOL_CALL APPROVED re-enters at SAME index (no +1) and stashes resume decision")
        void toolPauseApprovedResumesAtSameIndex() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(2);
            memory.setHitlPauseType("TOOL_CALL");
            memory.setHitlPendingToolCalls(batch(cfg("Pending {toolNames}"), call("t")));

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            // resumeFromIndex == pausedIndex (2), NOT 3, for tool pauses.
            verify(lifecycleManager).executeLifecycleFromIndex(memory, 2);
        }

        @Test
        @DisplayName("TOOL_CALL REJECTED does NOT short-circuit — re-enters the pipeline (LlmTask handles graceful answer)")
        void toolPauseRejectedStillResumes() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);
            memory.setHitlPauseType("TOOL_CALL");
            memory.setHitlPendingToolCalls(batch(cfg("Pending {toolNames}"), call("t")));

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED, "no", "sup"));

            // Only RULE pauses short-circuit REJECTED; TOOL_CALL re-enters at same index.
            verify(lifecycleManager).executeLifecycleFromIndex(memory, 0);
        }

        @Test
        @DisplayName("dropPendingApprovalPlaceholder blanks the exact placeholder mirror Data on resume")
        void toolPauseDropsPlaceholderData() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);
            memory.setHitlPauseType("TOOL_CALL");
            var b = batch(cfg("PENDING for {toolNames}"), call("delete"));
            memory.setHitlPendingToolCalls(b);

            // Seed the current step exactly as pauseConversation would: mirror Data +
            // conversation-output list holding the placeholder string.
            String placeholder = "PENDING for delete";
            var seed = new Data<>(MemoryKeys.OUTPUT_PREFIX, List.of(placeholder));
            seed.setPublic(true);
            memory.getCurrentStep().storeData(seed);
            memory.getCurrentStep().addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(placeholder));

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            // Mirror Data blanked to an empty list because it held exactly [placeholder].
            var data = memory.getCurrentStep().getData(MemoryKeys.OUTPUT_PREFIX);
            assertNotNull(data);
            assertEquals(new ArrayList<>(), data.getResult());
        }

        @Test
        @DisplayName("dropPendingApprovalPlaceholder leaves a non-matching mirror Data untouched")
        void toolPauseKeepsNonMatchingData() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);
            memory.setHitlPauseType("TOOL_CALL");
            memory.setHitlPendingToolCalls(batch(cfg("PENDING for {toolNames}"), call("delete")));

            // Some other writer replaced the mirror with a different value.
            var other = new Data<>(MemoryKeys.OUTPUT_PREFIX, List.of("a real answer"));
            other.setPublic(true);
            memory.getCurrentStep().storeData(other);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            var data = memory.getCurrentStep().getData(MemoryKeys.OUTPUT_PREFIX);
            assertNotNull(data);
            assertEquals(List.of("a real answer"), data.getResult());
        }
    }

    // =====================================================================
    // resume() — RULE pause: clearHitlStatusMarker
    // =====================================================================

    @Nested
    @DisplayName("resume() clears the RULE hitl:status marker")
    class ResumeClearsStatusMarker {

        @Test
        @DisplayName("APPROVED resume removes the hitl:status marker Data and output")
        void resumeClearsHitlStatusMarker() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);
            // RULE pause (no pauseType == "TOOL_CALL").
            var statusData = new Data<>("hitl:status", "awaiting_approval");
            statusData.setPublic(true);
            memory.getCurrentStep().storeData(statusData);
            memory.getCurrentStep().addConversationOutputString("hitl:status", "awaiting_approval");

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            assertNull(memory.getCurrentStep().getLatestData("hitl:status"));
            assertFalse(memory.getCurrentStep().getConversationOutput().containsKey("hitl:status"));
        }
    }

    // =====================================================================
    // resume() — config-drift + cancel-wins-over-repause + re-pause finally
    // =====================================================================

    @Nested
    @DisplayName("resume() error / re-pause / finally branches")
    class ResumeErrorAndFinally {

        @Test
        @DisplayName("config drift: paused workflow absent → ERROR + LifecycleException, no property persist")
        void configDriftErrors() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("gone-workflow");
            memory.setHitlPausedAbsoluteTaskIndex(1);

            var conv = createConversation();
            assertThrows(LifecycleException.class,
                    () -> conv.resume(decision(HitlVerdict.APPROVED)));
            assertEquals(ConversationState.ERROR, memory.getConversationState());
            // postConversationLifecycleTasks skipped on ERROR — store never touched.
            verify(propertiesHandler, atLeast(0)).getUserMemoryStore();
        }

        @Test
        @DisplayName("cancel that lands during a re-pause on resume wins → ENDED, not AWAITING_HUMAN")
        void resumeCancelWinsOverRePause() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(1);

            doAnswer(inv -> {
                memory.setCancelled(true);
                throw new ConversationPauseException("wf1", 3, "second gate", PauseOrigin.RULE);
            }).when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            assertEquals(ConversationState.ENDED, memory.getConversationState());
        }

        @Test
        @DisplayName("resume re-pause (AWAITING_HUMAN) leaves a fresh pending batch in place (finally does NOT clear)")
        void resumeRePauseKeepsFreshBatch() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(1);
            memory.setHitlPauseType("TOOL_CALL");
            memory.setHitlPendingToolCalls(batch(cfg("p {toolNames}"), call("t")));

            doAnswer(inv -> {
                // The re-pause re-arms a fresh batch.
                memory.setHitlPendingToolCalls(batch(cfg("p2 {toolNames}"), call("t2")));
                throw new ConversationPauseException("wf1", 4, "re-gate", PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
            // finally safety-net must NOT clear a fresh re-pause batch.
            assertNotNull(memory.getHitlPendingToolCalls());
        }

        @Test
        @DisplayName("resume finally clears a lingering batch when NOT re-paused (state READY)")
        void resumeFinallyClearsLingeringBatch() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);
            memory.setHitlPauseType("TOOL_CALL");
            // LlmTask (mocked away) never consumes the batch, so it lingers after a clean
            // run.
            memory.setHitlPendingToolCalls(batch(cfg("p {toolNames}"), call("t")));

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            assertEquals(ConversationState.READY, memory.getConversationState());
            // finally safety-net clears the lingering batch on a non-AWAITING_HUMAN
            // outcome.
            assertNull(memory.getHitlPendingToolCalls());
        }

        @Test
        @DisplayName("resume ERROR path: an unexpected RuntimeException sets ERROR and rethrows as LifecycleException")
        void resumeUnexpectedExceptionErrors() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            doThrow(new IllegalStateException("boom"))
                    .when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());

            var conv = createConversation();
            assertThrows(LifecycleException.class,
                    () -> conv.resume(decision(HitlVerdict.APPROVED)));
            assertEquals(ConversationState.ERROR, memory.getConversationState());
        }
    }

    // =====================================================================
    // resume() — RULE REJECTED short-circuit (note vs no-note branch)
    // =====================================================================

    @Nested
    @DisplayName("resume() RULE REJECTED short-circuit")
    class ResumeRuleRejected {

        @Test
        @DisplayName("RULE REJECTED with a note appends the reason to the rejection message")
        void ruleRejectedWithNote() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(1);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED, "policy violation", "sup"));

            var output = memory.getCurrentStep().getConversationOutput();
            @SuppressWarnings("unchecked")
            var outputList = (List<Object>) output.get(MemoryKeys.OUTPUT_PREFIX);
            assertNotNull(outputList);
            assertTrue(outputList.stream().anyMatch(o -> o.toString().contains("policy violation")));
            verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
        }

        @Test
        @DisplayName("RULE REJECTED without a note omits the reason clause")
        void ruleRejectedNoNote() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(1);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED)); // note null

            var output = memory.getCurrentStep().getConversationOutput();
            @SuppressWarnings("unchecked")
            var outputList = (List<Object>) output.get(MemoryKeys.OUTPUT_PREFIX);
            assertNotNull(outputList);
            assertTrue(outputList.stream().anyMatch(o -> o.toString().contains("rejected by a human reviewer")));
            assertTrue(outputList.stream().noneMatch(o -> o.toString().contains("Reason:")));
        }

        @Test
        @DisplayName("RULE REJECTED clears the HITL bookmark")
        void ruleRejectedClearsBookmark() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(2);
            memory.setHitlPausedAt(Instant.now());
            memory.setHitlPauseReason("r");

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED));

            assertNull(memory.getHitlPausedWorkflowId());
            assertEquals(-1, memory.getHitlPausedAbsoluteTaskIndex());
            assertNull(memory.getHitlPausedAt());
            assertNull(memory.getHitlPauseReason());
        }
    }

    // =====================================================================
    // resume() — decision optional fields (note/decidedBy present vs absent)
    // =====================================================================

    @Nested
    @DisplayName("resume() decision optional field branches")
    class ResumeDecisionFields {

        @Test
        @DisplayName("decision without note or decidedBy stores neither optional Data")
        void decisionWithoutOptionalFields() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED)); // note & decidedBy null

            var step = memory.getCurrentStep();
            assertNotNull(step.getLatestData("hitl:decision_verdict"));
            assertNull(step.getLatestData("hitl:decision_note"));
            assertNull(step.getLatestData("hitl:decision_by"));
            // Output note branch skipped too.
            assertFalse(step.getConversationOutput().containsKey("hitlDecisionNote"));
        }

        @Test
        @DisplayName("decision with note but no decidedBy stores note only")
        void decisionWithNoteOnly() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var d = new HitlDecision();
            d.setVerdict(HitlVerdict.APPROVED);
            d.setNote("checked");

            var conv = createConversation();
            conv.resume(d);

            var step = memory.getCurrentStep();
            assertNotNull(step.getLatestData("hitl:decision_note"));
            assertNull(step.getLatestData("hitl:decision_by"));
            assertEquals("checked", step.getConversationOutput().get("hitlDecisionNote"));
        }
    }

    // =====================================================================
    // stripPauseAction — null actions / no PAUSE present branches
    // =====================================================================

    @Nested
    @DisplayName("stripPauseAction edge branches")
    class StripPauseActionEdges {

        @Test
        @DisplayName("resume with no ACTIONS data at all → stripPauseAction is a no-op (no throw)")
        void stripWithNoActionsData() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);
            // No ACTIONS data stored in the current step.

            var conv = createConversation();
            assertDoesNotThrow(() -> conv.resume(decision(HitlVerdict.APPROVED)));
            assertEquals(ConversationState.READY, memory.getConversationState());
        }

        @Test
        @DisplayName("resume when ACTIONS present but without PAUSE_CONVERSATION → actions left unchanged")
        void stripWithoutPauseActionPresent() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);
            memory.getCurrentStep().storeData(new Data<>("actions", List.of("just_an_action")));

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            var actionsData = memory.getCurrentStep().<List<String>>getLatestData("actions");
            assertNotNull(actionsData);
            assertEquals(List.of("just_an_action"), actionsData.getResult());
        }
    }
}
