/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.Key;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.State;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CircuitBreakerConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.OnError;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * R8 through the whole LLM task, on the non-cascade path: the circuit of the
 * task's single model, the {@code onError} path it ends in, and that a task
 * without {@code circuitBreaker.enabled} is untouched.
 */
@DisplayName("LlmTask — circuit breaker (R8)")
class LlmTaskCircuitBreakerTest {

    private static final String VALID = "{\"answer\":\"hi\"}";
    private static final String PROSE = "Sure, here you go.";
    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private PrePostUtils prePostUtils;
    @Mock
    private ChatModelRegistry chatModelRegistry;
    @Mock
    private RagContextProvider ragContextProvider;
    @Mock
    private PromptSnippetService promptSnippetService;
    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private ConversationSummarizer conversationSummarizer;
    @Mock
    private CounterweightService counterweightService;
    @Mock
    private IdentityMaskingService identityMaskingService;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AtomicLong now = new AtomicLong(9_000_000L);
    private final Map<String, Object> templateData = new HashMap<>();
    private LlmCircuitBreakers breakers;
    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        lenient().when(promptSnippetService.getAll()).thenReturn(Map.of());
        lenient().when(promptSnippetService.getForAgent(any())).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(counterweightService.apply(anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData<?> d = mock(IData.class);
            lenient().when(d.getResult()).thenAnswer(x -> inv.getArgument(1));
            return d;
        });
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(memory.getAgentId()).thenReturn(AGENT);
        lenient().when(memory.getAgentVersion()).thenReturn(3);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        var inputData = mock(IData.class);
        lenient().when(currentStep.getLatestData("input")).thenReturn(inputData);
        lenient().when(inputData.getResult()).thenReturn("question");
        lenient().when(memory.getConversationProperties()).thenReturn(new ConversationProperties(memory));
        var output = new ConversationOutput();
        output.put("input", "question");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(output));

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine, new JsonSerialization(new ObjectMapper()),
                prePostUtils, chatModelRegistry, mock(IApiCallExecutor.class), mock(IAgentStore.class), mock(IWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), conversationSummarizer, promptSnippetService, globalVariableResolver,
                counterweightService, identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(), meterRegistry,
                new CallerIdentityContext(null, null));
        breakers = new LlmCircuitBreakers(meterRegistry, now::get);
        llmTask.setCircuitBreakers(breakers);
    }

    private static LlmConfiguration.Task task(boolean convertToObject) {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You are helpful.");
        params.put("convertToObject", String.valueOf(convertToObject));
        t.setParameters(params);
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        t.setRetry(retry);
        return t;
    }

    private void run(LlmConfiguration.Task task) throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task)));
    }

    private Key key() {
        return new Key(AGENT, 3, "openai", "base-model");
    }

    @Test
    @DisplayName("two 400s open the circuit; the third turn fails fast with the class, without calling the model")
    void badRequestsOpenThenFailFast() throws Exception {
        var model = FaultInjectingChatModel.script(Step.status(400)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(false);
        task.setCircuitBreaker(enabled());

        assertThrows(Exception.class, () -> run(task));
        assertThrows(Exception.class, () -> run(task));
        assertEquals(State.OPEN, breakers.stateOf(key()));
        int calls = model.callCount();

        var thrown = circuitOpen(assertThrows(LifecycleException.class, () -> run(task)));

        assertEquals(calls, model.callCount());
        assertEquals(LlmCircuitBreakers.Failure.BAD_REQUEST, thrown.getFailureClass());
        assertTrue(thrown.getMessage().contains("base-model"));
        assertTrue(thrown.getMessage().contains("BAD_REQUEST"));
    }

    /**
     * LlmTask wraps a failed task in a LifecycleException; the circuit's own is its
     * cause.
     */
    private static LlmCircuitOpenException circuitOpen(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof LlmCircuitOpenException open) {
                return open;
            }
        }
        throw new AssertionError("no LlmCircuitOpenException in the cause chain of " + failure);
    }

    private static CircuitBreakerConfig enabled() {
        var cb = new CircuitBreakerConfig();
        cb.setEnabled(true);
        cb.setWindow(3);
        cb.setThreshold(2);
        cb.setCoolDownMs(60_000);
        return cb;
    }

    @Test
    @DisplayName("onError=fallback serves the fallback when the circuit is open, and the model is not called")
    void openCircuitTakesTheFallbackPath() throws Exception {
        var model = FaultInjectingChatModel.script(Step.status(404)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(false);
        task.setCircuitBreaker(enabled());
        var v = new ResponseValidation();
        v.setEnabled(true);
        v.setFallbackMessage("Please try again later.");
        task.setResponseValidation(v);
        var onError = new OnError();
        onError.setAction(OnError.ACTION_FALLBACK);
        task.setOnError(onError);

        run(task);
        run(task);
        assertEquals(State.OPEN, breakers.stateOf(key()));
        int calls = model.callCount();

        run(task);

        assertEquals(calls, model.callCount(), "the open circuit served the fallback without a model call");
        assertEquals("Please try again later.", templateData.get("taskA"));
    }

    @Test
    @DisplayName("a model that keeps answering non-JSON opens the circuit although no responseValidation is configured")
    void invalidJsonCountsWithoutValidationConfig() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(true);
        task.setCircuitBreaker(enabled());

        run(task);
        run(task);

        assertEquals(State.OPEN, breakers.stateOf(key()));
        circuitOpen(assertThrows(LifecycleException.class, () -> run(task)));
        assertEquals(2, model.callCount());
    }

    @Test
    @DisplayName("valid replies keep it closed and a probe after the cool-down closes it again")
    void probeAfterCoolDownCloses() throws Exception {
        var model = FaultInjectingChatModel.script(Step.status(401), Step.text(VALID)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(true);
        task.setCircuitBreaker(enabled());

        assertThrows(Exception.class, () -> run(task));
        assertEquals(State.OPEN, breakers.stateOf(key()), "401 trips at once");
        circuitOpen(assertThrows(LifecycleException.class, () -> run(task)));

        now.addAndGet(60_000);
        run(task);

        assertEquals(State.CLOSED, breakers.stateOf(key()));
        assertEquals(Map.of("answer", "hi"), templateData.get("taskA"));
    }

    @Test
    @DisplayName("disabled by default: failures never open anything and every turn still reaches the model")
    void disabledByDefault() throws Exception {
        var model = FaultInjectingChatModel.script(Step.status(401)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(false);

        for (int i = 0; i < 4; i++) {
            assertThrows(Exception.class, () -> run(task));
        }

        assertEquals(4, model.callCount());
        assertEquals(State.CLOSED, breakers.stateOf(key()));
    }

    @Test
    @DisplayName("transient failures (503) do not count, so the circuit stays closed")
    void transientFailuresDoNotCount() throws Exception {
        var model = FaultInjectingChatModel.script(Step.status(503)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(false);
        task.setCircuitBreaker(enabled());

        for (int i = 0; i < 6; i++) {
            assertThrows(Exception.class, () -> run(task));
        }

        assertEquals(6, model.callCount());
        assertEquals(State.CLOSED, breakers.stateOf(key()));
    }
}
