/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.calc;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.callArgumentsSeen;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.entry;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.gateCalculate;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.model;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.task;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.text;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.toolBatch;
import static ai.labs.eddi.modules.llm.impl.ToolCallGuardrailFixture.toolResultsSeen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code toolCallGuardrails} on the live tool loop: through the real
 * orchestrator, execution pipeline and approval gate.
 */
@DisplayName("ToolLoopRunner — toolCallGuardrails")
class ToolLoopRunnerToolCallGuardrailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolCallGuardrailFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new ToolCallGuardrailFixture();
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    @DisplayName("without toolCallGuardrails the call reaches the tool byte for byte as the model wrote it")
    void inertWithoutField() throws Exception {
        var raw = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{ \"expression\" : \"6*7\" }")
                .build();
        var chatModel = model(toolBatch(raw), text("42"));

        f.run(chatModel, task(), null);

        verify(f.calculatorTool).calculate("6*7");
        assertEquals(List.of("{ \"expression\" : \"6*7\" }"), callArgumentsSeen(chatModel));
        assertEquals(0.0, f.counted("live", "allowed"));
    }

    @Test
    @DisplayName("a clean call runs unchanged and is counted as allowed")
    void cleanCallAllowed() throws Exception {
        var chatModel = model(toolBatch(calc("c1", "6*7")), text("42"));

        f.run(chatModel, task(entry("regex-filter", "block", "patterns", "acme")), null);

        verify(f.calculatorTool).calculate("6*7");
        assertEquals(1.0, f.counted("live", "allowed"));
    }

    @Nested
    @DisplayName("block")
    class Block {

        @Test
        @DisplayName("a blocked ungated call never reaches the tool; the model gets a NOT_EXECUTED envelope")
        void blockedUngatedCall() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "acme 6*7")), text("ok"));
            var block = entry("regex-filter", "block", "patterns", "acme");
            block.setBlockMessage("Do not name customers.");

            f.run(chatModel, task(block), null);

            verify(f.calculatorTool, never()).calculate(anyString());
            var seen = toolResultsSeen(chatModel);
            assertEquals(1, seen.size());
            JsonNode envelope = json(seen.get(0));
            assertEquals("NOT_EXECUTED", envelope.get("status").asText());
            assertEquals("tool_call_guardrail", envelope.get("reason").asText());
            assertEquals("regex-filter", envelope.get("guardrail").asText());
            assertEquals("/expression", envelope.get("field").asText());
            assertEquals("Do not name customers.", envelope.get("message").asText());
            assertFalse(envelope.get("retryAllowed").asBoolean());
            assertEquals(1.0, f.counted("live", "blocked"));
        }

        @Test
        @DisplayName("a blocked gated call never pauses — no human is asked to approve what will be refused")
        void blockedGatedCallNeverPauses() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "acme")), text("I could not."));

            var result = f.run(chatModel, task(entry("regex-filter", "block", "patterns", "acme")), gateCalculate());

            assertEquals("I could not.", result.response());
            verify(f.memory, never()).setHitlPendingToolCalls(any());
            verify(f.calculatorTool, never()).calculate(anyString());
        }

        @Test
        @DisplayName("the source-qualified pattern matches (not only the bare tool name)")
        void sourceQualifiedPatternMatches() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "acme")), text("ok"));
            var block = entry("regex-filter", "block", "patterns", "acme");
            block.setTools(List.of("builtin:calculate"));

            f.run(chatModel, task(block), null);

            verify(f.calculatorTool, never()).calculate(anyString());
        }

        @Test
        @DisplayName("an entry for another tool does not apply")
        void otherToolUnaffected() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "acme")), text("ok"));
            var block = entry("regex-filter", "block", "patterns", "acme");
            block.setTools(List.of("mcp:calculate", "builtin:other"));

            f.run(chatModel, task(block), null);

            verify(f.calculatorTool).calculate("acme");
        }

        @Test
        @DisplayName("fields absent: every string of the arguments is screened")
        void fieldsAbsentScreensEveryString() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "acme")), text("ok"));
            var block = entry("regex-filter", "block", "patterns", "acme");
            block.setFields(null);

            f.run(chatModel, task(block), null);

            verify(f.calculatorTool, never()).calculate(anyString());
            assertEquals("*", json(toolResultsSeen(chatModel).get(0)).get("field").asText());
        }

        @Test
        @DisplayName("arguments that are not JSON are undecided: a block entry refuses the call")
        void unparseableFailsClosed() throws Exception {
            var bad = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{not json").build();
            var chatModel = model(toolBatch(bad), text("ok"));

            f.run(chatModel, task(entry("regex-filter", "block", "patterns", "acme")), null);

            verify(f.calculatorTool, never()).calculate(anyString());
            assertEquals(1.0, f.counted("live", "undecided"));
        }
    }

    @Nested
    @DisplayName("canary: a blocked argument is never echoed")
    class Canary {

        private static final String CANARY = "zqcanaryzq";

        @Test
        @DisplayName("not in the tool result, the transcript, the trace, the audit records or the SSE events")
        void canaryNowhere() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "6*7 " + CANARY)), text("ok"));

            var result = f.run(chatModel, task(entry("regex-filter", "block", "patterns", "zqcanary\\w*")), null);

            String seen = toolResultsSeen(chatModel).toString();
            assertFalse(seen.contains(CANARY), seen);
            assertFalse(callArgumentsSeen(chatModel).toString().contains(CANARY), "the rewrite replaced it in the transcript");
            assertFalse(result.trace().toString().contains(CANARY), result.trace().toString());
            String stored = f.storedText();
            assertTrue(stored.contains("audit:guardrail:tool_call:regex-filter"), stored);
            assertFalse(stored.contains(CANARY), stored);
            ArgumentCaptor<String> sse = ArgumentCaptor.forClass(String.class);
            verify(f.sink, atLeastOnce()).onGuardrailTriggered(sse.capture(), sse.capture(), sse.capture(), sse.capture());
            assertFalse(sse.getAllValues().toString().contains(CANARY));
            assertTrue(sse.getAllValues().contains("tool_call"));
        }
    }

    @Nested
    @DisplayName("redact")
    class Redact {

        @Test
        @DisplayName("an ungated call runs with the redacted arguments, and the transcript carries them")
        void redactedUngatedCall() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "6*7 acme")), text("42"));

            var result = f.run(chatModel, task(entry("regex-filter", "redact", "patterns", "acme")), null);

            verify(f.calculatorTool).calculate("6*7 [REDACTED]");
            assertEquals(List.of("{\"expression\":\"6*7 [REDACTED]\"}"), callArgumentsSeen(chatModel));
            assertFalse(result.trace().toString().contains("acme"), result.trace().toString());
            assertEquals(1.0, f.counted("live", "rewritten"));
        }

        @Test
        @DisplayName("a gated call pauses with the redacted arguments — the approver sees what will run")
        void redactedGatedCallPauses() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "6*7 acme")));

            assertThrows(ToolApprovalRequiredException.class,
                    () -> f.run(chatModel, task(entry("regex-filter", "redact", "patterns", "acme")), gateCalculate()));

            ArgumentCaptor<PendingToolCallBatch> batch = ArgumentCaptor.forClass(PendingToolCallBatch.class);
            verify(f.memory).setHitlPendingToolCalls(batch.capture());
            assertEquals("{\"expression\":\"6*7 [REDACTED]\"}", batch.getValue().getCalls().get(0).getArgumentsRaw());
            assertFalse(batch.getValue().getChatTranscriptJson().contains("acme"));
        }

        @Test
        @DisplayName("disclosure appends the label once")
        void disclosure() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "6*7")), text("42"));

            f.run(chatModel, task(entry("disclosure", "redact", "text", "[AI]", "separator", " ")), null);

            verify(f.calculatorTool).calculate("6*7 [AI]");
        }
    }

    @Nested
    @DisplayName("reprompt")
    class Reprompt {

        @Test
        @DisplayName("refused with retryAllowed and the fix text; once maxRetries are spent the same finding blocks")
        void repromptThenBlock() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "acme")), toolBatch(calc("c2", "acme again")), text("gave up"));
            var reprompt = entry("regex-filter", "reprompt", "patterns", "acme", "repromptMessage", "Leave the customer out.");
            reprompt.setMaxRetries(1);

            var result = f.run(chatModel, task(reprompt), null);

            verify(f.calculatorTool, never()).calculate(anyString());
            var seen = toolResultsSeen(chatModel);
            JsonNode first = json(seen.get(0));
            assertTrue(first.get("retryAllowed").asBoolean());
            assertEquals("Leave the customer out.", first.get("message").asText());
            JsonNode second = json(seen.get(1));
            assertFalse(second.get("retryAllowed").asBoolean(), "the reprompt budget is spent");
            assertEquals(1, ToolCallGuardrails.repromptsSoFar(result.trace(), 0));
            assertEquals(1.0, f.counted("live", "reprompted"));
            assertEquals(1.0, f.counted("live", "blocked"));
        }
    }

    @Nested
    @DisplayName("spend")
    class Spend {

        @Test
        @DisplayName("a model-backed check is charged to the run's tool cost")
        void judgeCharged() throws Exception {
            var chatModel = model(toolBatch(calc("c1", "6*7")), text("42"));
            var task = task(entry("fake-judge", "block"));

            var result = f.run(chatModel, task, null);

            assertEquals(1, f.modelBacked.calls.get());
            verify(f.calculatorTool).calculate("6*7");
            assertEquals(ToolCallGuardrailFixture.ModelBacked.COST, (Double) result.responseMetadata().get("toolCostUsd"), 1e-9);
        }

        @Test
        @DisplayName("refused spend is undecided: a block entry refuses the call")
        void spendRefusedBlocks() throws Exception {
            when(f.tenantQuotaService.checkCostBudget(any(), anyDouble())).thenReturn(QuotaCheckResult.denied("budget"));
            var chatModel = model(toolBatch(calc("c1", "6*7")), text("no"));

            f.run(chatModel, task(entry("fake-judge", "block")), null);

            verify(f.calculatorTool, never()).calculate(anyString());
            assertEquals(1.0, f.counted("live", "undecided"));
            var trace = toolResultsSeen(chatModel).get(0);
            assertTrue(trace.contains("NOT_EXECUTED"), trace);
        }

        @Test
        @DisplayName("the run's model-check budget: MAX passes, the next one is refused")
        void modelCheckBudget() {
            var budget = ToolCallGuardrails.newRunBudget();
            for (int i = 0; i < ToolCallGuardrails.MAX_MODEL_CHECKS_PER_RUN; i++) {
                assertTrue(budget.tryAcquire());
            }
            assertFalse(budget.tryAcquire());
        }
    }

    @Test
    @DisplayName("a refused call's result binds to its call id")
    void refusalBindsToCallId() throws Exception {
        var chatModel = model(toolBatch(calc("c1", "6*7"), calc("c2", "acme")), text("ok"));

        f.run(chatModel, task(entry("regex-filter", "block", "patterns", "acme")), null);

        verify(f.calculatorTool, times(1)).calculate("6*7");
        var last = ToolCallGuardrailFixture.requests(chatModel);
        var ids = last.get(last.size() - 1).messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(m -> ((ToolExecutionResultMessage) m).id()).toList();
        assertEquals(2, ids.size());
        assertTrue(ids.containsAll(List.of("c1", "c2")), ids.toString());
    }
}
