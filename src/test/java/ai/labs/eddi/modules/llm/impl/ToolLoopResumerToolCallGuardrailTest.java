/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolCallGuardrailConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.calc;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.entry;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.model;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.task;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.text;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.toolBatch;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.toolResultsSeen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code toolCallGuardrails} after approval: block-only, before the journal
 * claim, every type on an amended call and only the deterministic ones on an
 * unamended call.
 */
@DisplayName("ToolLoopResumer — toolCallGuardrails")
class ToolLoopResumerToolCallGuardrailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolCallGuardrailFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new ToolCallGuardrailFixture();
    }

    private static HitlDecision approve(String callId, String amendedArguments) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer-1");
        if (amendedArguments != null) {
            var call = new ToolCallDecision();
            call.setVerdict(HitlDecision.HitlVerdict.APPROVED);
            call.setAmendedArguments(amendedArguments);
            Map<String, ToolCallDecision> perCall = new HashMap<>();
            perCall.put(callId, call);
            decision.setToolDecisions(perCall);
        }
        return decision;
    }

    private AgentOrchestrator.ExecutionResult resume(ChatModel chatModel, LlmConfiguration.Task task, String original, String amended)
            throws Exception {
        return resume(chatModel, task, original, amended, List.of());
    }

    private AgentOrchestrator.ExecutionResult resume(ChatModel chatModel, LlmConfiguration.Task task, String original, String amended,
                                                     List<Map<String, Object>> traceSoFar)
            throws Exception {
        return f.orchestrator.resumeToolLoop(chatModel, task, f.memory, f.pending(List.of(calc("c1", original)), traceSoFar),
                approve("c1", amended), true);
    }

    private static String amended(String expression) {
        return "{\"expression\":\"" + expression + "\"}";
    }

    @Test
    @DisplayName("an amendment that breaks a rule is refused before the journal claim — nothing consumed, nothing run")
    void amendedViolationRefusedBeforeClaim() throws Exception {
        var chatModel = model(text("I could not run it."));

        resume(chatModel, task(entry("regex-filter", "block", "patterns", "acme")), "6*7", amended("acme"));

        verify(f.journalStore, never()).tryClaim(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(f.calculatorTool, never()).calculate(anyString());
        var seen = toolResultsSeen(chatModel);
        assertEquals(1, seen.size());
        assertEquals("tool_call_guardrail", MAPPER.readTree(seen.get(0)).get("reason").asText());
        assertEquals(1.0, f.counted("resume", "blocked"));
    }

    @Test
    @DisplayName("a redact finding on an amendment refuses the call instead of rewriting what the human approved")
    void redactOnAmendmentBlocks() throws Exception {
        var chatModel = model(text("not run"));

        resume(chatModel, task(entry("regex-filter", "redact", "patterns", "acme")), "6*7", amended("6*7 acme"));

        verify(f.calculatorTool, never()).calculate(anyString());
        verify(f.journalStore, never()).tryClaim(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a clean amendment runs as approved")
    void cleanAmendmentRuns() throws Exception {
        var chatModel = model(text("42"));

        resume(chatModel, task(entry("regex-filter", "block", "patterns", "acme")), "6*7", amended("6*8"));

        verify(f.calculatorTool).calculate("6*8");
        assertEquals(1.0, f.counted("resume", "allowed"));
    }

    @Test
    @DisplayName("disclosure on an amendment: label kept runs, label dropped is refused")
    void disclosureOnAmendment() throws Exception {
        ToolCallGuardrailConfig disclosure = entry("disclosure", "redact", "text", "[AI]", "separator", " ");

        resume(model(text("ok")), task(disclosure), "6*7 [AI]", amended("6*8 [AI]"));
        verify(f.calculatorTool).calculate("6*8 [AI]");

        var dropped = model(text("not run"));
        resume(dropped, task(disclosure), "6*7 [AI]", amended("6*9"));
        verify(f.calculatorTool, never()).calculate("6*9");
        assertTrue(toolResultsSeen(dropped).get(0).contains("NOT_EXECUTED"));
    }

    @Test
    @DisplayName("an unamended approved call skips model-backed types; an amended one gets them")
    void modelBackedOnlyOnAmendment() throws Exception {
        var task = task(entry("fake-judge", "block"));

        resume(model(text("ok")), task, "a claim", null);
        assertEquals(0, f.modelBacked.calls.get(), "the unamended call already passed the live screen");
        verify(f.calculatorTool).calculate("a claim");

        resume(model(text("no")), task, "6*7", amended("another claim"));
        assertEquals(1, f.modelBacked.calls.get());
        verify(f.calculatorTool, never()).calculate("another claim");
    }

    @Test
    @DisplayName("an unamended approved call is still re-screened with the deterministic types")
    void unamendedDeterministicRescreen() throws Exception {
        var chatModel = model(text("no"));

        resume(chatModel, task(entry("regex-filter", "block", "patterns", "acme")), "acme", null);

        verify(f.calculatorTool, never()).calculate(anyString());
    }

    @Test
    @DisplayName("a call the journal already executed is replayed, not re-screened")
    void replayNotRescreened() throws Exception {
        when(f.journalStore.tryClaim(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(f.journalStore.find("conv-1", "epoch-1", "c1")).thenReturn(Optional.of(new IHitlToolJournalStore.JournalEntry("conv-1", "epoch-1",
                "c1", "calculate", IHitlToolJournalStore.Status.EXECUTED, "stored-42", Instant.now(), "reviewer-1")));
        var chatModel = model(text("42"));

        resume(chatModel, task(entry("regex-filter", "block", "patterns", "acme")), "acme", amended("acme"));

        var seen = toolResultsSeen(chatModel);
        assertEquals(List.of("stored-42"), seen);
        assertEquals(0.0, f.counted("resume", "blocked"));
    }

    @Test
    @DisplayName("the reprompt budget continues across a resume: the turn's trace already holds the spent reprompt")
    void repromptCountSeededFromTrace() throws Exception {
        var reprompt = entry("regex-filter", "reprompt", "patterns", "acme");
        reprompt.setMaxRetries(1);
        Map<String, Object> earlier = new HashMap<>();
        earlier.put("type", ToolCallGuardrails.TRACE_TYPE);
        earlier.put("action", "reprompt");
        earlier.put("entry", 0);
        var chatModel = model(toolBatch(calc("c2", "acme")), text("gave up"));

        resume(chatModel, task(reprompt), "6*7", null, List.of(earlier));

        var seen = toolResultsSeen(chatModel);
        var refusal = MAPPER.readTree(seen.get(seen.size() - 1));
        assertFalse(refusal.get("retryAllowed").asBoolean(), "the one reprompt was spent before the pause");
    }
}
