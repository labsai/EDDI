/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.FinishReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R5 in the cascade: a step re-asks its own model for a reply it cannot use
 * before the cascade pays for the next one, and escalates with the reason
 * {@code invalid_output} when the re-asks do not fix it.
 */
@DisplayName("CascadingModelExecutor — same-model recovery before escalation (R5)")
class CascadingModelExecutorFormatRetryTest {

    private static final String VALID = "{\"answer\":\"hi\"}";
    private static final String PROSE = "Sure! Here is what you wanted.";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ChatModelRegistry registry;
    private IConversationMemory memory;
    private ConversationEventSink sink;
    private CascadingModelExecutor executor;

    @BeforeEach
    void setUp() {
        registry = mock(ChatModelRegistry.class);
        memory = mock(IConversationMemory.class);
        sink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(sink);
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), meters,
                new CallerIdentityContext(null, null),
                new FormatRetryRunner(new ModelOutputParser(new JsonSerialization(new ObjectMapper())), meters));
    }

    private void models(FaultInjectingChatModel first, FaultInjectingChatModel second) throws Exception {
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(first);
        when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(second);
    }

    private static CascadeStep step(String type, Consumer<CascadeStep> tweak) {
        var s = new CascadeStep();
        s.setType(type);
        tweak.accept(s);
        return s;
    }

    private static ModelCascadeConfig cascade(CascadeStep... steps) {
        var c = new ModelCascadeConfig();
        c.setEnabled(true);
        c.setEvaluationStrategy("heuristic");
        c.setSteps(List.of(steps));
        return c;
    }

    private static LlmConfiguration.Task task(Consumer<ResponseValidation> tweak) {
        var t = new LlmConfiguration.Task();
        t.setId("t");
        t.setType("openai");
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        t.setRetry(retry);
        var v = new ResponseValidation();
        v.setEnabled(true);
        tweak.accept(v);
        t.setResponseValidation(v);
        return t;
    }

    private static List<ChatMessage> messages() {
        return new ArrayList<>(List.of(SystemMessage.from("sys"), UserMessage.from("question")));
    }

    private CascadingModelExecutor.CascadeResult run(ModelCascadeConfig cascade, LlmConfiguration.Task task, Map<String, String> params,
                                                     FormatRetryRunner.ContextShrinker shrinker)
            throws LifecycleException {
        return executor.execute(cascade, messages(), "sys", params, task, memory, mock(IAgentOrchestrator.class), Map.of(), false, true, false, null,
                -1, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT, shrinker, FormatRetryRunner.RemainingBudget.UNBOUNDED);
    }

    private CascadingModelExecutor.CascadeResult run(ModelCascadeConfig cascade, LlmConfiguration.Task task) throws LifecycleException {
        return run(cascade, task, Map.of(), null);
    }

    private static Consumer<ResponseValidation> invalidJsonRetry() {
        return v -> v.setOnInvalidJson("retry");
    }

    private static CascadeStep plain(String type) {
        return step(type, s -> {
        });
    }

    private double recovery(String action, String outcome, String trigger) {
        var c = meters.find("eddi.llm.recovery").tag("action", action).tag("outcome", outcome).tag("trigger", trigger).counter();
        return c == null ? 0 : c.count();
    }

    private static Object stepTrace(CascadingModelExecutor.CascadeResult result, int step, String key) {
        return result.trace().stream().filter(e -> Integer.valueOf(step).equals(e.get("step"))).findFirst().orElseThrow().get(key);
    }

    @Test
    @DisplayName("invalid then valid on the same model: one corrective message, a valid answer, no escalation")
    void sameModelFixesItself() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE), Step.text(VALID));
        var second = FaultInjectingChatModel.script(Step.text("must not be asked"));
        models(first, second);

        var result = run(cascade(plain("openai"), plain("anthropic")), task(invalidJsonRetry()));

        assertEquals(0, result.stepUsed());
        assertEquals(VALID, result.response());
        assertEquals(2, first.callCount());
        assertEquals(0, second.callCount());
        assertEquals("accepted", stepTrace(result, 0, "status"));
        verify(sink).onLlmRetry("invalid_json", 1);
        verify(sink, never()).onCascadeEscalation(anyInt(), anyInt(),
                anyDouble(), anyDouble(), eq("invalid_output"),
                anyLong());
    }

    @Test
    @DisplayName("step 1 invalid twice: the cascade escalates to step 2, trace reason invalid_output")
    void twoInvalidRepliesEscalate() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE), Step.text(PROSE));
        var second = FaultInjectingChatModel.script(Step.text(VALID));
        models(first, second);

        var result = run(cascade(plain("openai"), plain("anthropic")), task(invalidJsonRetry()));

        assertEquals(1, result.stepUsed());
        assertEquals(VALID, result.response());
        assertEquals(2, first.callCount());
        assertEquals("escalated", stepTrace(result, 0, "status"));
        assertEquals("invalid_output", stepTrace(result, 0, "reason"));
        assertEquals("invalid_json", stepTrace(result, 0, "invalidOutput"));
        assertEquals(1, stepTrace(result, 0, "formatRetries"));
        verify(sink).onCascadeEscalation(eq(0), eq(1), anyDouble(), anyDouble(),
                eq("invalid_output"), anyLong());
        assertEquals(1.0, recovery("escalate", "invalid_output", "invalid_json"));
        assertEquals(1.0, meters.find("eddi.llm.cascade.escalations").tag("reason", "invalid_output").counter().count());
    }

    @Test
    @DisplayName("step 2 invalid then valid: answered by step 2 after its own re-ask")
    void secondStepHasItsOwnReask() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE), Step.text(PROSE));
        var second = FaultInjectingChatModel.script(Step.text(PROSE), Step.text(VALID));
        models(first, second);

        var result = run(cascade(plain("openai"), plain("anthropic")), task(invalidJsonRetry()));

        assertEquals(1, result.stepUsed());
        assertEquals(VALID, result.response());
        assertEquals(2, first.callCount());
        assertEquals(2, second.callCount());
        // the corrective message went to step 2's model too
        assertEquals(4, second.requests().get(1).messages().size());
    }

    @Test
    @DisplayName("every step invalid: the last reply comes back unusable (LlmTask then applies fallbackAction)")
    void everyStepInvalid() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        models(first, second);

        var result = run(cascade(plain("openai"), plain("anthropic")), task(invalidJsonRetry()));

        assertEquals(1, result.stepUsed());
        assertEquals(PROSE, result.response());
        assertEquals(2, first.callCount());
        assertEquals(2, second.callCount());
        assertEquals("invalid_json", stepTrace(result, 1, "invalidOutput"));
    }

    @Test
    @DisplayName("no time left in the step: escalate at once without a re-ask")
    void noTimeLeftEscalatesImmediately() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE)); // a second call would fail the script
        var second = FaultInjectingChatModel.script(Step.text(VALID));
        models(first, second);

        var result = run(cascade(step("openai", s -> s.setTimeoutMs(2_000L)), plain("anthropic")), task(invalidJsonRetry()));

        assertEquals(1, result.stepUsed());
        assertEquals(1, first.callCount());
        assertEquals("invalid_output", stepTrace(result, 0, "reason"));
        assertEquals(1.0, recovery("retry", "skipped_no_time", "invalid_json"));
    }

    @Test
    @DisplayName("an open breaker for invalid output: escalate without a same-model re-ask")
    void breakerEscalatesWithoutReask() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE));
        var second = FaultInjectingChatModel.script(Step.text(VALID));
        models(first, second);
        executor.setReaskGate(trigger -> trigger != FormatRetryRunner.Trigger.INVALID_JSON);

        var result = run(cascade(plain("openai"), plain("anthropic")), task(invalidJsonRetry()));

        assertEquals(1, result.stepUsed());
        assertEquals(1, first.callCount());
        assertEquals(1.0, recovery("retry", "skipped_breaker", "invalid_json"));
    }

    @Test
    @DisplayName("a per-step maxFormatRetries of 0 is respected: the step is not re-asked, the cascade still escalates")
    void perStepZeroRetries() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE));
        var second = FaultInjectingChatModel.script(Step.text(PROSE), Step.text(VALID));
        models(first, second);
        // the task allows 3, step 1 says 0, step 2 inherits 3
        var result = run(cascade(step("openai", s -> s.setMaxFormatRetries(0)), plain("anthropic")), task(v -> {
            v.setOnInvalidJson("retry");
            v.setMaxRetries(3);
        }));

        assertEquals(1, first.callCount());
        assertEquals(2, second.callCount());
        assertEquals(1, result.stepUsed());
        assertEquals("invalid_output", stepTrace(result, 0, "reason"));
    }

    @Test
    @DisplayName("a step override above the clamp is clamped, and null inherits the task's maxRetries")
    void stepOverrideIsClamped() {
        var s = new CascadeStep();
        s.setMaxFormatRetries(40);
        assertEquals(3, s.getMaxFormatRetries());
        s.setMaxFormatRetries(null);
        assertNull(s.getMaxFormatRetries());
    }

    @Test
    @DisplayName("truncation retry doubles maxOutputTokens on the same model")
    void truncationDoublesTokens() throws Exception {
        var first = FaultInjectingChatModel.script(Step.finishReason("{\"answer\":\"hi", FinishReason.LENGTH), Step.text(VALID));
        var second = FaultInjectingChatModel.script(Step.text("not asked"));
        models(first, second);

        var result = run(cascade(plain("openai"), plain("anthropic")), task(v -> v.setOnTruncation("retry")), Map.of("maxTokens", "1000"), null);

        assertEquals(0, result.stepUsed());
        assertEquals(VALID, result.response());
        assertNull(first.requests().get(0).parameters().maxOutputTokens());
        assertEquals(2000, first.requests().get(1).parameters().maxOutputTokens());
    }

    @Test
    @DisplayName("context too long: the step is re-sent once with the halved window and answers")
    void contextRetryHalvesTheWindow() throws Exception {
        var first = FaultInjectingChatModel.script(Step.fail(new InvalidRequestException("This model's maximum context length is 8192 tokens")),
                Step.text(VALID));
        var second = FaultInjectingChatModel.script(Step.text("not asked"));
        models(first, second);
        var halved = List.<ChatMessage>of(SystemMessage.from("sys"));

        var result = run(cascade(plain("openai"), plain("anthropic")), task(v -> v.setOnContextTooLong("retry")), Map.of(), () -> halved);

        assertEquals(0, result.stepUsed());
        assertEquals(2, first.callCount());
        assertEquals(2, first.requests().get(0).messages().size());
        assertEquals(1, first.requests().get(1).messages().size());
        verify(sink).onLlmRetry("context_too_long", 1);
    }

    @Test
    @DisplayName("an earlier usable answer beats an unusable last step")
    void earlierUsableAnswerWinsOverUnusableLastStep() throws Exception {
        // step 0 escalates on confidence (threshold 0.99 is never met by the
        // heuristic),
        // step 1 is unusable
        var first = FaultInjectingChatModel.script(Step.text(VALID));
        var second = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        models(first, second);

        var result = run(cascade(step("openai", s -> s.setConfidenceThreshold(0.99)), plain("anthropic")), task(invalidJsonRetry()));

        assertEquals(0, result.stepUsed());
        assertEquals(VALID, result.response());
        assertTrue(second.callCount() >= 1);
    }

    @Test
    @DisplayName("no retry policy configured: today's behaviour — the reply is accepted as it is, one call")
    void defaultsChangeNothing() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE));
        var second = FaultInjectingChatModel.script(Step.text(VALID));
        models(first, second);

        var result = run(cascade(plain("openai"), plain("anthropic")), task(v -> {
        }));

        assertEquals(0, result.stepUsed());
        assertEquals(PROSE, result.response());
        assertEquals(1, first.callCount());
        assertEquals(0, second.callCount());
    }

    @Test
    @DisplayName("tool mode: the step re-asks only its final answer; the loop (and so the tool) runs exactly once")
    void toolModeReasksOnlyTheFinalAnswer() throws Exception {
        var toolRuns = new AtomicInteger();
        var call = ToolExecutionRequest.builder().id("c1").name("placeOrder").arguments("{}").build();
        List<ChatMessage> exchange = List.of(AiMessage.from(call), ToolExecutionResultMessage.from(call, "order #42 placed"));
        var first = FaultInjectingChatModel.script(Step.text(VALID));
        var second = FaultInjectingChatModel.script(Step.text("not asked"));
        models(first, second);
        var orchestrator = mock(IAgentOrchestrator.class);
        when(orchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any())).thenAnswer(inv -> {
            toolRuns.incrementAndGet();
            return new AgentOrchestrator.ExecutionResult(PROSE, new ArrayList<>(), Map.of("toolCostUsd", 0.25), exchange);
        });
        when(orchestrator.reaskFinalAnswer(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ChatModel chatModel = inv.getArgument(0);
            List<ChatMessage> transcript = inv.getArgument(1);
            var reply = chatModel.chat(ChatRequest.builder().messages(transcript).build());
            return new AgentOrchestrator.ExecutionResult(reply.aiMessage().text(), List.of(), Map.of());
        });
        var task = task(invalidJsonRetry());
        task.setEnableBuiltInTools(true);

        var result = executor.execute(cascade(plain("openai"), plain("anthropic")), messages(), "sys", Map.of(), task, memory, orchestrator,
                Map.of(), false, true, false, null, -1, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT, null,
                FormatRetryRunner.RemainingBudget.UNBOUNDED);

        assertEquals(0, result.stepUsed());
        assertEquals(VALID, result.response());
        assertEquals(1, toolRuns.get());
        assertEquals(1, first.callCount());
        var transcript = first.requests().get(0).messages();
        assertTrue(transcript.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage));
        assertTrue(transcript.getLast() instanceof UserMessage);
        // the loop's own metadata (tool spend) survives the re-ask
        assertEquals(0.25, result.runToolCostUsd());
    }
}
