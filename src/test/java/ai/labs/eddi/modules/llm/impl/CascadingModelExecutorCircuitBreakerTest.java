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
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.Failure;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.Key;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.State;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8 in the cascade: a model whose circuit is open is skipped (the next step
 * answers), per model, and the breaker feeds R5's re-ask gate.
 */
@DisplayName("CascadingModelExecutor — circuit breaker (R8)")
class CascadingModelExecutorCircuitBreakerTest {

    private static final String VALID = "{\"answer\":\"hi\"}";
    private static final String PROSE = "Sure! Here is what you wanted.";
    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong now = new AtomicLong(5_000_000L);
    private ChatModelRegistry registry;
    private IConversationMemory memory;
    private ConversationEventSink sink;
    private CascadingModelExecutor executor;
    private LlmCircuitBreakers breakers;

    @BeforeEach
    void setUp() {
        registry = mock(ChatModelRegistry.class);
        memory = mock(IConversationMemory.class);
        sink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(sink);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getAgentVersion()).thenReturn(1);
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), meters,
                new CallerIdentityContext(null, null),
                new FormatRetryRunner(new ModelOutputParser(new JsonSerialization(new ObjectMapper())), meters));
        breakers = new LlmCircuitBreakers(meters, now::get);
        executor.setCircuitBreakers(breakers);
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

    private static LlmConfiguration.Task task(int window, int threshold, Consumer<ResponseValidation> validation) {
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
        var cb = new CircuitBreakerConfig();
        cb.setEnabled(true);
        cb.setWindow(window);
        cb.setThreshold(threshold);
        cb.setCoolDownMs(60_000);
        t.setCircuitBreaker(cb);
        return t;
    }

    private CascadingModelExecutor.CascadeResult run(ModelCascadeConfig cascade, LlmConfiguration.Task task) throws LifecycleException {
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("sys"), UserMessage.from("question")));
        return executor.execute(cascade, messages, "sys", Map.of("modelName", "m"), task, memory, mock(IAgentOrchestrator.class), Map.of(), false,
                true, false, null, -1, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT, null, FormatRetryRunner.RemainingBudget.UNBOUNDED);
    }

    private static Object stepTrace(CascadingModelExecutor.CascadeResult result, int step, String key) {
        return result.trace().stream().filter(e -> Integer.valueOf(step).equals(e.get("step"))).findFirst().orElseThrow().get(key);
    }

    private Key key(String provider) {
        return new Key(AGENT, 1, provider, "m");
    }

    @Test
    @DisplayName("invalid JSON from step 1 on 2 of 3 turns opens ITS circuit: the next turn skips step 1, step 2 answers, step 2's circuit stays closed")
    void openStepIsSkippedOthersStillUsed() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(VALID)).repeatLast();
        models(first, second);
        var task = task(3, 2, null);

        run(cascade(step("openai"), step("anthropic")), task);
        run(cascade(step("openai"), step("anthropic")), task);
        assertEquals(State.OPEN, breakers.stateOf(key("openai")));
        int firstCalls = first.callCount();
        int secondCalls = second.callCount();

        var result = run(cascade(step("openai"), step("anthropic")), task);

        assertEquals(firstCalls, first.callCount(), "an open circuit must not call the model at all");
        assertEquals(secondCalls + 1, second.callCount());
        assertEquals(1, result.stepUsed());
        assertEquals(VALID, result.response());
        assertEquals("circuit_open", stepTrace(result, 0, "status"));
        assertEquals("INVALID_OUTPUT", stepTrace(result, 0, "failureClass"));
        assertEquals(State.CLOSED, breakers.stateOf(key("anthropic")));
        assertEquals(1.0, meters.find("eddi.llm.cascade.escalations").tag("reason", "circuit_open").counter().count());
        verify(sink).onCascadeEscalation(eq(0), eq(1), anyDouble(), anyDouble(), eq("circuit_open"), anyLong());
    }

    @Test
    @DisplayName("a 401 from step 1 opens its circuit at once")
    void authTripsImmediately() throws Exception {
        var first = FaultInjectingChatModel.script(Step.status(401), Step.text("must not be asked"));
        var second = FaultInjectingChatModel.script(Step.text(VALID)).repeatLast();
        models(first, second);
        var task = task(10, 8, null);

        var firstRun = run(cascade(step("openai"), step("anthropic")), task);
        assertEquals(1, firstRun.stepUsed());
        assertEquals(State.OPEN, breakers.stateOf(key("openai")));

        run(cascade(step("openai"), step("anthropic")), task);
        assertEquals(1, first.callCount(), "the second turn never reached the revoked key");
    }

    @Test
    @DisplayName("transient failures (503) never open the circuit, however many")
    void transientFailuresDoNotCount() throws Exception {
        var first = FaultInjectingChatModel.script(Step.status(503)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(VALID)).repeatLast();
        models(first, second);
        var task = task(3, 2, null);

        for (int i = 0; i < 12; i++) {
            run(cascade(step("openai"), step("anthropic")), task);
        }

        assertEquals(State.CLOSED, breakers.stateOf(key("openai")));
        assertEquals(12, first.callCount(), "step 1 is still tried on every turn");
    }

    @Test
    @DisplayName("every step open, nothing to return: a LlmCircuitOpenException that carries the class")
    void everyStepOpenFailsFast() throws Exception {
        var first = FaultInjectingChatModel.script(Step.status(401)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.status(401)).repeatLast();
        models(first, second);
        var task = task(10, 8, null);

        assertThrows(LifecycleException.class, () -> run(cascade(step("openai"), step("anthropic")), task));
        assertEquals(State.OPEN, breakers.stateOf(key("openai")));
        assertEquals(State.OPEN, breakers.stateOf(key("anthropic")));

        var thrown = assertThrows(LlmCircuitOpenException.class, () -> run(cascade(step("openai"), step("anthropic")), task));

        assertEquals(Failure.AUTH, thrown.getFailureClass());
        assertTrue(thrown.getMessage().contains("circuit"));
        assertEquals(1, first.callCount());
        assertEquals(1, second.callCount());
    }

    @Test
    @DisplayName("after the cool-down one probe goes through; a valid reply closes the circuit again")
    void probeClosesTheCircuit() throws Exception {
        var first = FaultInjectingChatModel.script(Step.status(401), Step.text(VALID)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(VALID)).repeatLast();
        models(first, second);
        var task = task(10, 8, null);
        run(cascade(step("openai"), step("anthropic")), task);
        assertEquals(State.OPEN, breakers.stateOf(key("openai")));

        now.addAndGet(60_000);
        var result = run(cascade(step("openai"), step("anthropic")), task);

        assertEquals(0, result.stepUsed(), "the probe was answered by step 1");
        assertEquals(State.CLOSED, breakers.stateOf(key("openai")));
    }

    @Test
    @DisplayName("an open invalid-output circuit skips the same-model re-ask: the half-open probe asks once and the cascade moves on")
    void openCircuitSkipsReask() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(VALID)).repeatLast();
        models(first, second);
        var task = task(2, 2, v -> v.setOnInvalidJson("retry"));

        // Closed: the retry policy re-asks once (2 calls per turn), the reply stays
        // invalid.
        run(cascade(step("openai"), step("anthropic")), task);
        run(cascade(step("openai"), step("anthropic")), task);
        assertEquals(4, first.callCount(), "two turns x (ask + one re-ask)");
        assertEquals(State.OPEN, breakers.stateOf(key("openai")));

        now.addAndGet(60_000);
        var result = run(cascade(step("openai"), step("anthropic")), task);

        assertEquals(5, first.callCount(), "the probe asked once, with no same-model re-ask");
        assertEquals(1, result.stepUsed());
        assertEquals(State.OPEN, breakers.stateOf(key("openai")), "the probe failed: re-opened");
        assertEquals(1.0, meters.find("eddi.llm.recovery").tag("outcome", "skipped_breaker").tag("trigger", "invalid_json").counter().count());
    }

    @Test
    @DisplayName("disabled by default: a task without circuitBreaker.enabled never opens, however often step 1 fails")
    void disabledByDefaultChangesNothing() throws Exception {
        var first = FaultInjectingChatModel.script(Step.status(401)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(VALID)).repeatLast();
        models(first, second);
        var task = task(2, 1, null);
        task.setCircuitBreaker(null);

        for (int i = 0; i < 5; i++) {
            run(cascade(step("openai"), step("anthropic")), task);
        }

        assertEquals(5, first.callCount());
        assertEquals(State.CLOSED, breakers.stateOf(key("openai")));
        assertTrue(meters.find("eddi.llm.circuit").counters().isEmpty());
    }

    @Test
    @DisplayName("an executor without a breaker registry runs exactly as before")
    void noRegistryNoGuard() throws Exception {
        var first = FaultInjectingChatModel.script(Step.status(401)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(VALID)).repeatLast();
        models(first, second);
        executor.setCircuitBreakers(null);
        var task = task(2, 1, null);

        for (int i = 0; i < 3; i++) {
            run(cascade(step("openai"), step("anthropic")), task);
        }

        assertEquals(3, first.callCount());
    }
}
