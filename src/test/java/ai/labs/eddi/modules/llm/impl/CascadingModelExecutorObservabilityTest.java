/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CircuitBreakerConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R11 in the cascade: the trace and the result name the model the variable
 * resolves to, and each escalation, re-ask and circuit skip leaves exactly one
 * structured line and one {@code eddi.llm.recovery} count.
 */
@DisplayName("CascadingModelExecutor — observability (R11)")
class CascadingModelExecutorObservabilityTest {

    private static final String VALID = "{\"answer\":\"hi\"}";
    private static final String PROSE = "Sure! Here is what you wanted.";
    private static final String CONVERSATION = "conv-cascade-observability-1";
    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> lines = new ArrayList<>();
    private ChatModelRegistry registry;
    private IConversationMemory memory;
    private CascadingModelExecutor executor;

    @BeforeEach
    void setUp() {
        LlmRecoveryLog.observer = line -> {
            if (line.contains("conversationId=" + CONVERSATION)) {
                lines.add(line);
            }
        };
        registry = mock(ChatModelRegistry.class);
        memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn(CONVERSATION);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getAgentVersion()).thenReturn(1);
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(resolver.resolveValue("${vars:fast-model}")).thenReturn("resolved-model-1");
        executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), meters,
                new CallerIdentityContext(null, null),
                new FormatRetryRunner(new ModelOutputParser(new JsonSerialization(new ObjectMapper())), meters));
        executor.setCircuitBreakers(new LlmCircuitBreakers(meters, System::currentTimeMillis));
    }

    @AfterEach
    void tearDown() {
        LlmRecoveryLog.observer = null;
    }

    private void models(FaultInjectingChatModel first, FaultInjectingChatModel second) throws Exception {
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(first);
        when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(second);
    }

    private static CascadeStep step(String type) {
        var s = new CascadeStep();
        s.setType(type);
        return s;
    }

    private static ModelCascadeConfig cascade(CascadeStep... steps) {
        var c = new ModelCascadeConfig();
        c.setEnabled(true);
        c.setEvaluationStrategy("heuristic");
        c.setSteps(List.of(steps));
        return c;
    }

    private static LlmConfiguration.Task task(Consumer<ResponseValidation> validation, boolean circuit) {
        var t = new LlmConfiguration.Task();
        t.setId("t");
        t.setType("openai");
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        t.setRetry(retry);
        if (validation != null) {
            var v = new ResponseValidation();
            v.setEnabled(true);
            validation.accept(v);
            t.setResponseValidation(v);
        }
        if (circuit) {
            var cb = new CircuitBreakerConfig();
            cb.setEnabled(true);
            cb.setWindow(10);
            cb.setThreshold(8);
            cb.setCoolDownMs(60_000);
            t.setCircuitBreaker(cb);
        }
        return t;
    }

    private CascadingModelExecutor.CascadeResult run(ModelCascadeConfig cascade, LlmConfiguration.Task task, String modelName)
            throws LifecycleException {
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("sys"), UserMessage.from("question")));
        return executor.execute(cascade, messages, "sys", Map.of("modelName", modelName), task, memory, mock(IAgentOrchestrator.class), Map.of(),
                false, true, false, null, -1, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT, null, FormatRetryRunner.RemainingBudget.UNBOUNDED);
    }

    private List<String> linesFor(String action) {
        return lines.stream().filter(l -> l.contains(" action=" + action + " ")).toList();
    }

    private double recovery(String action) {
        return meters.find("eddi.llm.recovery").tag("action", action).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    @DisplayName("the trace and the result carry the model a ${vars:...} reference resolves to")
    void traceNamesTheResolvedModel() throws Exception {
        models(FaultInjectingChatModel.script(Step.text(VALID)), FaultInjectingChatModel.script(Step.text(VALID)));

        var result = run(cascade(step("openai"), step("anthropic")), task(null, false), "${vars:fast-model}");

        assertEquals("resolved-model-1", result.modelName());
        assertEquals("resolved-model-1", result.trace().get(0).get("model"));
    }

    @Test
    @DisplayName("a turn whose time left is already inside the reserve still gives step 0 a real timeout, not 1 ms")
    void stepZeroGetsTheRemainingTurnTimeWhenTheReserveIsSpent() throws Exception {
        when(memory.getTurnDeadline()).thenReturn(TurnDeadline.of(Clock.systemUTC(), System.currentTimeMillis(), 1_200, 1_500L));
        models(FaultInjectingChatModel.script(Step.text(VALID)), FaultInjectingChatModel.script(Step.text(VALID)));

        var result = run(cascade(step("openai"), step("anthropic")), task(null, false), "m");

        assertEquals(0, result.stepUsed());
        assertEquals(VALID, result.response());
    }

    @Test
    @DisplayName("step 1 unusable twice: one re-ask line and one escalate line, matched by the two counters")
    void reaskThenEscalateIsTwoLines() throws Exception {
        models(FaultInjectingChatModel.script(Step.text(PROSE), Step.text(PROSE)), FaultInjectingChatModel.script(Step.text(VALID)));

        run(cascade(step("openai"), step("anthropic")), task(v -> v.setOnInvalidJson("retry"), false), "m");

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(linesFor("retry").get(0).contains(" class=invalid_json action=retry outcome=still_invalid attempt=1 "), lines.toString());
        String escalate = linesFor("escalate").get(0);
        assertTrue(escalate.contains(" class=invalid_json action=escalate outcome=invalid_output attempt=1 "), escalate);
        assertEquals(1.0, recovery("escalate"));
    }

    @Test
    @DisplayName("a failing step 1 escalates with its failure class on the line")
    void errorEscalationNamesTheClass() throws Exception {
        models(FaultInjectingChatModel.script(Step.status(400)), FaultInjectingChatModel.script(Step.text(VALID)));

        run(cascade(step("openai"), step("anthropic")), task(null, false), "m");

        assertEquals(1, linesFor("escalate").size(), lines.toString());
        assertTrue(linesFor("escalate").get(0).contains(" class=BAD_REQUEST action=escalate outcome=error attempt=1 "), lines.toString());
    }

    @Test
    @DisplayName("a skipped open circuit is one circuit_skip line, not an escalate line, and the model is not called")
    void circuitSkipIsOneLine() throws Exception {
        var first = FaultInjectingChatModel.script(Step.status(401)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(VALID)).repeatLast();
        models(first, second);
        var task = task(null, true);
        run(cascade(step("openai"), step("anthropic")), task, "m");
        lines.clear();

        run(cascade(step("openai"), step("anthropic")), task, "m");

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(linesFor("circuit_skip").get(0).contains(" action=circuit_skip outcome=skipped attempt=1 "), lines.toString());
        assertEquals(1, first.callCount());
    }
}
