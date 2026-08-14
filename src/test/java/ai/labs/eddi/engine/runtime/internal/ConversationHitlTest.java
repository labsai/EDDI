/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.model.Property;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.ConversationNotReadyException;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Conversation HITL Tests")
class ConversationHitlTest {

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

        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
    }

    private Conversation createConversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
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

    @Nested
    @DisplayName("AWAITING_HUMAN blocks say()")
    class AwaitingHumanBlocksSayTests {

        @Test
        @DisplayName("say() throws ConversationNotReadyException when AWAITING_HUMAN")
        void sayBlockedWhenAwaitingHuman() {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            var conv = createConversation();

            var ex = assertThrows(ConversationNotReadyException.class,
                    () -> conv.say("hello", Map.of()));
            assertTrue(ex.getMessage().contains("AWAITING_HUMAN"));
        }

        @Test
        @DisplayName("rerun() also blocked when AWAITING_HUMAN")
        void rerunBlockedWhenAwaitingHuman() {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            var conv = createConversation();

            assertThrows(ConversationNotReadyException.class,
                    () -> conv.rerun(Map.of()));
        }
    }

    @Nested
    @DisplayName("Pause during say()")
    class PauseDuringSayTests {

        @Test
        @DisplayName("pause during say() sets state to AWAITING_HUMAN")
        void pauseSetsAwaitingHuman() throws Exception {
            memory.setConversationState(ConversationState.READY);

            // Lifecycle throws ConversationPauseException
            doThrow(new ConversationPauseException("wf1", 2, "needs approval"))
                    .when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("approve this", Map.of());

            // After pause, state should be AWAITING_HUMAN (set in finally block won't
            // override because the state is no longer IN_PROGRESS)
            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());

            // Verify HITL bookmark was recorded
            assertEquals("wf1", memory.getHitlPausedWorkflowId());
            assertEquals(2, memory.getHitlPausedAbsoluteTaskIndex());
            assertEquals("needs approval", memory.getHitlPauseReason());
            assertNotNull(memory.getHitlPausedAt());
        }

        @Test
        @DisplayName("pause during say() skips postConversationLifecycleTasks — step-scoped properties survive (Invariant 9)")
        void pauseSkipsPostTasks() throws Exception {
            memory.setConversationState(ConversationState.READY);
            // A step-scoped property that postConversationLifecycleTasks would purge
            memory.getConversationProperties().put("stepProp",
                    new Property("stepProp", "value",
                            Property.Scope.step));

            doThrow(new ConversationPauseException("wf1", 1, "human review"))
                    .when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("check this", Map.of());

            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
            // Invariant 9, asserted behaviorally: removing the "if (!paused)" guard
            // around postConversationLifecycleTasks purges this property mid-turn.
            assertTrue(memory.getConversationProperties().containsKey("stepProp"),
                    "step-scoped properties must survive a pause — post tasks must not run");
        }

        @Test
        @DisplayName("TOOL_CALL pause uses the batch's task-scoped pendingMessage over the agent-level default")
        void toolPauseUsesBatchEffectivePendingMessage() throws Exception {
            memory.setConversationState(ConversationState.READY);

            // Agent-level default present on memory (what the pre-fix path read only).
            var agentLevel = new ToolApprovalsConfig();
            agentLevel.setPendingMessage("AGENT default for {toolNames}");
            memory.setAgentToolApprovalsConfig(agentLevel);

            // The batch carries the task-scoped override that actually gated the call.
            var taskOverride = new ToolApprovalsConfig();
            taskOverride.setPendingMessage("TASK review pending for {toolNames}");

            doAnswer(inv -> {
                var call = new PendingToolCallBatch.PendingToolCall();
                call.setToolName("delete_record");
                var batch = new PendingToolCallBatch();
                batch.setCalls(List.of(call));
                batch.setEffectiveToolApprovals(taskOverride);
                memory.setHitlPendingToolCalls(batch);
                throw new ConversationPauseException("wf1", 2, "gated tool call",
                        ConversationPauseException.PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("delete it", Map.of());

            var output = memory.getCurrentStep().getConversationOutput();
            String rendered = output.get(MemoryKeys.OUTPUT_PREFIX).toString();
            assertTrue(rendered.contains("TASK review pending for delete_record"),
                    "the batch's task-scoped pendingMessage must win over the agent-level default; got: " + rendered);
            assertFalse(rendered.contains("AGENT default"),
                    "the agent-level default must NOT be used when the batch carries an effective config");
        }

        @Test
        @DisplayName("TOOL_CALL pause with legacy batch (null effective config) falls back to the agent-level pendingMessage")
        void toolPauseLegacyBatchFallsBackToAgentLevel() throws Exception {
            memory.setConversationState(ConversationState.READY);

            var agentLevel = new ToolApprovalsConfig();
            agentLevel.setPendingMessage("AGENT default for {toolNames}");
            memory.setAgentToolApprovalsConfig(agentLevel);

            doAnswer(inv -> {
                var call = new PendingToolCallBatch.PendingToolCall();
                call.setToolName("delete_record");
                var batch = new PendingToolCallBatch();
                batch.setCalls(List.of(call));
                // No effectiveToolApprovals — legacy batch.
                memory.setHitlPendingToolCalls(batch);
                throw new ConversationPauseException("wf1", 2, "gated tool call",
                        ConversationPauseException.PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("delete it", Map.of());

            var output = memory.getCurrentStep().getConversationOutput();
            String rendered = output.get(MemoryKeys.OUTPUT_PREFIX).toString();
            assertTrue(rendered.contains("AGENT default for delete_record"),
                    "a legacy batch must fall back to the agent-level pendingMessage; got: " + rendered);
        }

        @Test
        @DisplayName("TOOL_CALL pause uses the governing rule's pendingMessage over both scalar levels")
        void toolPauseUsesGoverningRulePendingMessage() throws Exception {
            // "You are about to create an agent" reads very differently from a blanket
            // "an action needs approval" — per-endpoint friction is only worth having if
            // the end user actually sees the endpoint-specific wording.
            memory.setConversationState(ConversationState.READY);

            var agentLevel = new ToolApprovalsConfig();
            agentLevel.setPendingMessage("AGENT default for {toolNames}");
            memory.setAgentToolApprovalsConfig(agentLevel);

            var taskOverride = new ToolApprovalsConfig();
            taskOverride.setPendingMessage("TASK review pending for {toolNames}");

            var rule = new ToolApprovalsConfig.ApprovalRule();
            rule.setMatch("http.post:/agentstore/agents");
            rule.setPendingMessage("RULE creating an agent via {toolNames}");

            doAnswer(inv -> {
                var call = new PendingToolCallBatch.PendingToolCall();
                call.setToolName("createAgent");
                var batch = new PendingToolCallBatch();
                batch.setCalls(List.of(call));
                batch.setEffectiveToolApprovals(taskOverride);
                batch.setEffectiveRule(rule);
                memory.setHitlPendingToolCalls(batch);
                throw new ConversationPauseException("wf1", 2, "gated tool call",
                        ConversationPauseException.PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("create an agent", Map.of());

            var output = memory.getCurrentStep().getConversationOutput();
            String rendered = output.get(MemoryKeys.OUTPUT_PREFIX).toString();
            assertTrue(rendered.contains("RULE creating an agent via createAgent"),
                    "the governing rule's pendingMessage must win over both scalar levels; got: " + rendered);
            assertFalse(rendered.contains("TASK review pending"),
                    "the toolApprovals scalar must NOT be used when the governing rule states a message");
        }

        @Test
        @DisplayName("a governing rule stating no pendingMessage still falls back to the toolApprovals scalar")
        void toolPauseRuleWithoutMessageFallsBackToScalar() throws Exception {
            // Fields fall back individually: a rule that only tightens the timeout must
            // not blank out the message the designer wrote at the level above.
            memory.setConversationState(ConversationState.READY);

            var taskOverride = new ToolApprovalsConfig();
            taskOverride.setPendingMessage("TASK review pending for {toolNames}");

            var rule = new ToolApprovalsConfig.ApprovalRule();
            rule.setMatch("http.delete:*");
            rule.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);

            doAnswer(inv -> {
                var call = new PendingToolCallBatch.PendingToolCall();
                call.setToolName("deleteAgent");
                var batch = new PendingToolCallBatch();
                batch.setCalls(List.of(call));
                batch.setEffectiveToolApprovals(taskOverride);
                batch.setEffectiveRule(rule);
                memory.setHitlPendingToolCalls(batch);
                throw new ConversationPauseException("wf1", 2, "gated tool call",
                        ConversationPauseException.PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("delete it", Map.of());

            var output = memory.getCurrentStep().getConversationOutput();
            String rendered = output.get(MemoryKeys.OUTPUT_PREFIX).toString();
            assertTrue(rendered.contains("TASK review pending for deleteAgent"),
                    "a rule silent on pendingMessage must inherit the scalar; got: " + rendered);
        }

        /**
         * Arms a gated batch for {@code toolName} (no configured pendingMessage
         * anywhere, so the DEFAULT applies) and runs one turn to the pause.
         *
         * @return the rendered {@code "output"} conversation-output of the paused step
         */
        private String pauseWithUnconfiguredMessage(String toolName) throws Exception {
            memory.setConversationState(ConversationState.READY);
            doAnswer(inv -> {
                var call = new PendingToolCallBatch.PendingToolCall();
                call.setToolName(toolName);
                var batch = new PendingToolCallBatch();
                batch.setCalls(List.of(call));
                memory.setHitlPendingToolCalls(batch);
                throw new ConversationPauseException("wf1", 2, "gated tool call",
                        ConversationPauseException.PauseOrigin.TOOL_CALL);
            }).when(lifecycleManager).executeLifecycle(any(), any());

            createConversation().say("do it", Map.of());
            return memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX).toString();
        }

        @Test
        @DisplayName("with no pendingMessage configured, the default NAMES the gated tool")
        void unconfiguredPendingMessageNamesTheTool() throws Exception {
            String rendered = pauseWithUnconfiguredMessage("createAgent");

            assertTrue(rendered.contains("createAgent"),
                    "the default pending message must name the gated tool; got: " + rendered);
            assertFalse(rendered.contains("{toolNames}"),
                    "the placeholder token must be substituted, not rendered; got: " + rendered);
        }

        /**
         * The regression this default exists for. A turn may pause up to
         * maxPausesPerTurn times; each pause rewrites the placeholder into the same
         * step. When the text was a constant, deciding one batch and landing on the
         * next produced a byte-identical bubble — which reads as "I approved it and
         * nothing happened". Two pauses on different tools must not render the same
         * sentence.
         */
        @Test
        @DisplayName("two pauses on different tools produce DIFFERENT pending messages")
        void consecutivePausesOnDifferentToolsDiffer() throws Exception {
            String first = pauseWithUnconfiguredMessage("createAgent");
            // A fresh conversation stands in for the next pause of a multi-pause turn:
            // what is under test is resolvePendingMessage's dependence on the batch,
            // and the batch is the only thing that differs between the two.
            String second = pauseWithUnconfiguredMessage("deployAgent");

            assertNotEquals(first, second,
                    "consecutive pauses on different tools must not render identical text; got: " + first);
            assertTrue(second.contains("deployAgent") && !second.contains("createAgent"),
                    "the second pause must name only its own gated tool; got: " + second);
        }

        @Test
        @DisplayName("a batch with no usable tool name falls back to the generic default")
        void unnamedCallsFallBackToGenericDefault() throws Exception {
            // Legacy/degenerate batch: "run ." would be worse than saying nothing
            // specific, so the name-free sentence is kept for exactly this case.
            String rendered = pauseWithUnconfiguredMessage(null);

            assertTrue(rendered.contains("This action requires human approval"),
                    "a nameless batch must fall back to the generic default; got: " + rendered);
        }

        @Test
        @DisplayName("normal (non-pause) turn DOES purge step-scoped properties — companion to Invariant 9")
        void normalTurnPurgesStepProperties() throws Exception {
            memory.setConversationState(ConversationState.READY);
            memory.getConversationProperties().put("stepProp",
                    new Property("stepProp", "value",
                            Property.Scope.step));

            var conv = createConversation();
            conv.say("normal turn", Map.of());

            assertFalse(memory.getConversationProperties().containsKey("stepProp"),
                    "step-scoped properties are purged when the turn completes normally");
        }
    }

    @Nested
    @DisplayName("resume()")
    class ResumeTests {

        @Test
        @DisplayName("resume with APPROVED calls executeLifecycleFromIndex")
        void resumeApprovedCallsFromIndex() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(2);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            // Should call executeLifecycleFromIndex with resumeFromIndex = 2 + 1 = 3
            verify(lifecycleManager).executeLifecycleFromIndex(memory, 3);
        }

        @Test
        @DisplayName("resume with APPROVED stores decision verdict in memory")
        void resumeApprovedStoresDecision() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED, "looks good", "admin"));

            // Verify decision data was stored in current step
            var step = memory.getCurrentStep();
            assertNotNull(step.getLatestData("hitl:decision_verdict"));
            assertEquals("APPROVED", step.getLatestData("hitl:decision_verdict").getResult());
            assertNotNull(step.getLatestData("hitl:decision_note"));
            assertEquals("looks good", step.getLatestData("hitl:decision_note").getResult());
            assertNotNull(step.getLatestData("hitl:decision_by"));
            assertEquals("admin", step.getLatestData("hitl:decision_by").getResult());
        }

        @Test
        @DisplayName("resume strips the stale PAUSE_CONVERSATION action from step ACTIONS (Blocker #1 belt-and-braces)")
        void resumeStripsStalePauseAction() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(1);
            // The paused turn's actions (incl. PAUSE_CONVERSATION) are restored
            // into the current step on resume — they must not survive re-entry.
            memory.getCurrentStep().storeData(new Data<>(
                    "actions", List.of("delete_account", IConversation.PAUSE_CONVERSATION)));

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            var actionsData = memory.getCurrentStep().<List<String>>getLatestData("actions");
            assertNotNull(actionsData);
            assertFalse(actionsData.getResult().contains(IConversation.PAUSE_CONVERSATION),
                    "stale PAUSE_CONVERSATION must be stripped before re-entering the pipeline");
            assertTrue(actionsData.getResult().contains("delete_account"),
                    "other actions must be preserved");
        }

        @Test
        @DisplayName("resume exposes the decision to templates and next-turn behavior rules")
        void resumeExposesDecisionToTemplates() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED, "verified by phone", "supervisor"));

            // {{memory.current.hitlDecision}} — conversationOutput, not raw step data
            var output = memory.getCurrentStep().getConversationOutput();
            assertEquals("APPROVED", output.get("hitlDecision"));
            assertEquals("verified by phone", output.get("hitlDecisionNote"));

            // {properties.hitlVerdict} — conversation-scoped property for next-turn rules
            assertTrue(memory.getConversationProperties().containsKey("hitlVerdict"));
        }

        @Test
        @DisplayName("resume with REJECTED skips executeLifecycleFromIndex")
        void resumeRejectedSkipsExecution() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED));

            // REJECTED should NOT call executeLifecycleFromIndex or executeLifecycle
            verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
            verify(lifecycleManager, never()).executeLifecycle(any(), any());
        }

        @Test
        @DisplayName("resume with REJECTED clears HITL bookmark")
        void resumeRejectedClearsBookmark() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(2);
            memory.setHitlPausedAt(Instant.now());
            memory.setHitlPauseReason("some reason");

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED));

            assertNull(memory.getHitlPausedWorkflowId());
            assertEquals(-1, memory.getHitlPausedAbsoluteTaskIndex());
            assertNull(memory.getHitlPausedAt());
            assertNull(memory.getHitlPauseReason());
        }

        @Test
        @DisplayName("resume when not AWAITING_HUMAN throws ConversationNotReadyException")
        void resumeNotAwaitingThrows() {
            memory.setConversationState(ConversationState.READY);

            var conv = createConversation();

            var ex = assertThrows(ConversationNotReadyException.class,
                    () -> conv.resume(decision(HitlVerdict.APPROVED)));
            assertTrue(ex.getMessage().contains("AWAITING_HUMAN"));
        }

        @Test
        @DisplayName("resume when IN_PROGRESS throws ConversationNotReadyException")
        void resumeInProgressThrows() {
            memory.setConversationState(ConversationState.IN_PROGRESS);

            var conv = createConversation();

            assertThrows(ConversationNotReadyException.class,
                    () -> conv.resume(decision(HitlVerdict.APPROVED)));
        }

        @Test
        @DisplayName("resume re-pause keeps AWAITING_HUMAN state")
        void resumeRePauseKeepsAwaitingHuman() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(1);

            // The resumed pipeline hits another PAUSE
            doThrow(new ConversationPauseException("wf1", 3, "second approval needed"))
                    .when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            // Should be AWAITING_HUMAN again (from the second pause)
            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());

            // New bookmark should be set for the second pause point
            assertEquals("wf1", memory.getHitlPausedWorkflowId());
            assertEquals(3, memory.getHitlPausedAbsoluteTaskIndex());
            assertEquals("second approval needed", memory.getHitlPauseReason());
        }

        @Test
        @DisplayName("resume with APPROVED sets state to READY after completion")
        void resumeApprovedSetsReadyAfterCompletion() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            // After successful resume without another pause/stop, state should be READY
            assertEquals(ConversationState.READY, memory.getConversationState());
        }

        @Test
        @DisplayName("resume with APPROVED renders output")
        void resumeRendersOutput() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            verify(outputRenderer).renderOutput(memory);
        }

        @Test
        @DisplayName("resume catches ConversationStopException and ends conversation")
        void resumeStopEndsConversation() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            doThrow(new ConversationStopException())
                    .when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED));

            assertEquals(ConversationState.ENDED, memory.getConversationState());
        }

        @Test
        @DisplayName("REJECTED path writes the rejection to ConversationOutput[output] AND strips PAUSE_CONVERSATION (finding 24/H)")
        void resumeRejectedWritesOutputAndStripsPauseAction() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(1);
            // The paused turn's ACTIONS (incl. PAUSE_CONVERSATION) are restored on
            // resume — the REJECTED branch must strip the gate action just like APPROVED.
            memory.getCurrentStep().storeData(new Data<>(
                    "actions", List.of("delete_account", IConversation.PAUSE_CONVERSATION)));

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED, "not authorized", "supervisor"));

            // The rejection feedback must reach conversationOutputs["output"] so log
            // generation and UIs (which read that key) render it.
            var output = memory.getCurrentStep().getConversationOutput();
            @SuppressWarnings("unchecked")
            var outputList = (List<Object>) output.get(
                    MemoryKeys.OUTPUT_PREFIX);
            assertNotNull(outputList, "REJECTED must publish an output entry");
            assertTrue(outputList.stream().anyMatch(o -> o.toString().contains("rejected by a human reviewer")),
                    "output must carry the rejection message, got: " + outputList);
            assertTrue(outputList.stream().anyMatch(o -> o.toString().contains("not authorized")),
                    "output must include the reviewer's note");

            // ACTIONS strip: PAUSE_CONVERSATION must be gone so a later rerun/undo of
            // this step cannot re-trigger the gate from stale action data.
            var actionsData = memory.getCurrentStep().<List<String>>getLatestData("actions");
            assertNotNull(actionsData);
            assertFalse(actionsData.getResult().contains(IConversation.PAUSE_CONVERSATION),
                    "REJECTED must strip stale PAUSE_CONVERSATION");
            assertTrue(actionsData.getResult().contains("delete_account"),
                    "other actions must be preserved");

            // No pipeline re-entry on REJECTED.
            verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
        }
    }

    // =========================================================================
    // Multi-workflow resume (finding 24) — the paused workflow resumes FROM the
    // bookmark index; every workflow AFTER it runs a full executeLifecycle. The
    // single-workflow ResumeTests never exercise the found-then-continue loop.
    // =========================================================================

    @Nested
    @DisplayName("resume() across multiple workflows")
    class MultiWorkflowResume {

        private IExecutableWorkflow wf(String id, ILifecycleManager lm) {
            var w = mock(IExecutableWorkflow.class);
            when(w.getWorkflowId()).thenReturn(id);
            when(w.getLifecycleManager()).thenReturn(lm);
            return w;
        }

        @Test
        @DisplayName("pause in workflow 1 of 2 → wf1 resumes from index, wf2 runs a full lifecycle, in order")
        void pausedInFirstOfTwo_secondRunsFull() throws Exception {
            var lm1 = mock(ILifecycleManager.class);
            var lm2 = mock(ILifecycleManager.class);
            var wf1 = wf("wf1", lm1);
            var wf2 = wf("wf2", lm2);

            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(2);

            var conv = new Conversation(List.of(wf1, wf2), memory, propertiesHandler, outputRenderer);
            conv.resume(decision(HitlVerdict.APPROVED));

            var inOrder = inOrder(lm1, lm2);
            // wf1: resume from bookmark index + 1
            inOrder.verify(lm1).executeLifecycleFromIndex(memory, 3);
            // wf2: full lifecycle (the found-then-continue branch) — regression guard:
            // skipping subsequent workflows would drop their output/templating.
            inOrder.verify(lm2).executeLifecycle(memory, null);
            verify(lm2, never()).executeLifecycleFromIndex(any(), anyInt());
            assertEquals(ConversationState.READY, memory.getConversationState());
        }

        @Test
        @DisplayName("pause in workflow 2 of 2 → wf1 is skipped entirely, wf2 resumes from index")
        void pausedInSecondOfTwo_firstSkipped() throws Exception {
            var lm1 = mock(ILifecycleManager.class);
            var lm2 = mock(ILifecycleManager.class);
            var wf1 = wf("wf1", lm1);
            var wf2 = wf("wf2", lm2);

            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf2");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = new Conversation(List.of(wf1, wf2), memory, propertiesHandler, outputRenderer);
            conv.resume(decision(HitlVerdict.APPROVED));

            // wf1 is before the paused workflow — it already ran in the original turn,
            // so it must NOT re-run on resume.
            verify(lm1, never()).executeLifecycle(any(), any());
            verify(lm1, never()).executeLifecycleFromIndex(any(), anyInt());
            verify(lm2).executeLifecycleFromIndex(memory, 1);
        }
    }

    // =========================================================================
    // Config-drift resume (finding 24) — the pausedWorkflowId is not among the
    // deployed workflows (agent redeployed / workflow renamed). The REAL loop
    // must set ERROR and throw a config-drift LifecycleException. Previously this
    // branch was only "tested" by a mock that pre-scripted the exception.
    // =========================================================================

    @Nested
    @DisplayName("resume() config drift (paused workflow no longer exists)")
    class ConfigDriftResume {

        @Test
        @DisplayName("bookmarked workflowId absent from deployed workflows → LifecycleException + state ERROR")
        void missingPausedWorkflow_errorsWithActionableMessage() throws Exception {
            // The only deployed workflow is "wf1"; the bookmark names one that is gone.
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("renamed-workflow");
            memory.setHitlPausedAbsoluteTaskIndex(1);

            var conv = createConversation();

            var ex = assertThrows(LifecycleException.class,
                    () -> conv.resume(decision(HitlVerdict.APPROVED)));
            assertTrue(ex.getMessage().contains("renamed-workflow")
                    && ex.getMessage().contains("config drift"),
                    "message must be actionable, got: " + ex.getMessage());

            // The conversation is parked in ERROR (not left dangling IN_PROGRESS).
            assertEquals(ConversationState.ERROR, memory.getConversationState());
            // The paused workflow was never found, so nothing was executed.
            verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
            verify(lifecycleManager, never()).executeLifecycle(any(), any());
        }
    }
}
