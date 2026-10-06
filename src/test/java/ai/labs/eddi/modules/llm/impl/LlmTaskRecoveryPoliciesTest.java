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
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
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
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * R5 through the whole LLM task: what a task with a {@code retry}
 * {@code responseValidation} does with unusable replies, and that a task
 * without one is untouched.
 */
@DisplayName("LlmTask — recovery policies (R5)")
class LlmTaskRecoveryPoliciesTest {

    private static final String VALID = "{\"answer\":\"hi\"}";
    private static final String PROSE = "Sure, here you go.";

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
    private final Map<String, Object> templateData = new HashMap<>();
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
    }

    private static LlmConfiguration.Task task(Consumer<ResponseValidation> validation) {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You are helpful.");
        params.put("convertToObject", "true");
        t.setParameters(params);
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        t.setRetry(retry);
        var v = new ResponseValidation();
        v.setEnabled(true);
        validation.accept(v);
        t.setResponseValidation(v);
        return t;
    }

    private static Consumer<ResponseValidation> retryInvalidJson() {
        return v -> v.setOnInvalidJson("retry");
    }

    private void run(LlmConfiguration.Task task) throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task)));
    }

    @Test
    @DisplayName("invalid then valid: one re-ask, the parsed object is what the templates see, the recovery is noted on the step")
    void invalidThenValid() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text(PROSE), Step.text(VALID));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(task(retryInvalidJson()));

        assertEquals(2, model.callCount());
        assertEquals(Map.of("answer", "hi"), templateData.get("taskA"));
        var note = ArgumentCaptor.forClass(Object.class);
        verify(dataFactory).createData(eq("llm:retry:taskA"), note.capture());
        assertEquals(1, ((Map<?, ?>) note.getValue()).get("reasks"));
        assertNull(((Map<?, ?>) note.getValue()).get("unresolved"));
        verify(dataFactory).createData("llm:output:outcome:taskA", "valid");
        verify(dataFactory, never()).createData(eq("llm:fallback:taskA"), any());
    }

    @Test
    @DisplayName("invalid on every attempt: the default fallbackAction serves the configured fallback")
    void exhaustedFallsBack() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(task(retryInvalidJson().andThen(v -> v.setFallbackMessage("Please try again."))));

        assertEquals(2, model.callCount());
        assertEquals("Please try again.", templateData.get("taskA"));
        verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
        assertEquals(1.0, meterRegistry.find("eddi.llm.recovery").tag("action", "fallback").tag("trigger", "validation").counter().count());
    }

    @Test
    @DisplayName("fallbackAction error fails the turn, warn keeps the raw reply")
    void fallbackActionVariants() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        assertThrows(LifecycleException.class, () -> run(task(retryInvalidJson().andThen(v -> v.setFallbackAction("error")))));

        run(task(retryInvalidJson().andThen(v -> v.setFallbackAction("warn"))));
        assertEquals(PROSE, templateData.get("taskA"));
    }

    @Test
    @DisplayName("a cascade whose every step is invalid ends in the fallback")
    void everyCascadeStepInvalidFallsBack() throws Exception {
        var first = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        var second = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        when(chatModelRegistry.getOrCreate(eq("openai"), any())).thenReturn(first);
        when(chatModelRegistry.getOrCreate(eq("anthropic"), any())).thenReturn(second);
        var task = task(retryInvalidJson().andThen(v -> v.setFallbackMessage("Please try again.")));
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        var s1 = new CascadeStep();
        s1.setType("openai");
        var s2 = new CascadeStep();
        s2.setType("anthropic");
        cascade.setSteps(List.of(s1, s2));
        task.setModelCascade(cascade);

        run(task);

        assertEquals(2, first.callCount());
        assertEquals(2, second.callCount());
        assertEquals("Please try again.", templateData.get("taskA"));
        verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
    }

    @Test
    @DisplayName("without a retry action nothing changes: one call, the raw reply stored")
    void defaultsAreUntouched() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text(PROSE));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(task(v -> {
        }));

        assertEquals(1, model.callCount());
        assertEquals(PROSE, templateData.get("taskA"));
        verify(dataFactory, never()).createData(eq("llm:retry:taskA"), any());
    }

    @Test
    @DisplayName("onInvalidJson=warn records the validation note and does not re-ask")
    void warnDoesNotRetry() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text(PROSE));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(task(v -> v.setOnInvalidJson("warn")));

        assertEquals(1, model.callCount());
        assertEquals(PROSE, templateData.get("taskA"));
        verify(dataFactory).createData(eq("llm:validation:invalid_json:taskA"), anyString());
    }

    @Test
    @DisplayName("'retry' on a policy that is not re-asked (refusal) is a warning, never a crash")
    void retryOnRefusalIsAWarning() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text("I cannot help with that."));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(task(v -> v.setOnRefusal("retry")));

        assertEquals(1, model.callCount());
        verify(dataFactory).createData(eq("llm:validation:refusal_detected:taskA"), anyString());
    }

    @Test
    @DisplayName("streaming: an attempt that may be retried is buffered, never streamed; the final answer is emitted once")
    void streamingIsBuffered() throws Exception {
        var sink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(sink);
        var model = FaultInjectingChatModel.script(Step.text(PROSE), Step.text(VALID));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(retryInvalidJson());
        task.getParameters().put("addToOutput", "true");

        run(task);

        verify(chatModelRegistry, never()).getOrCreateStreaming(anyString(), any());
        verify(sink).onLlmRetry("invalid_json", 1);
        verify(sink).onToken(VALID);
        verify(sink, never()).onToken(PROSE);
    }

    @Test
    @DisplayName("context too long: the history window is halved for the one re-ask")
    void contextWindowIsHalved() throws Exception {
        var outputs = new ArrayList<ConversationOutput>();
        for (int i = 0; i < 6; i++) {
            var o = new ConversationOutput();
            o.put("input", "question " + i);
            o.put("output", List.of("answer " + i));
            outputs.add(o);
        }
        var last = new ConversationOutput();
        last.put("input", "question");
        outputs.add(last);
        when(memory.getConversationOutputs()).thenReturn(outputs);
        var model = FaultInjectingChatModel.script(Step.fail(new InvalidRequestException("This model's maximum context length is 8192 tokens")),
                Step.text(VALID));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(v -> v.setOnContextTooLong("retry"));
        task.setConversationHistoryLimit(6);

        run(task);

        assertEquals(2, model.callCount());
        int before = model.requests().get(0).messages().size();
        int after = model.requests().get(1).messages().size();
        assertTrue(after < before, "window not reduced: " + before + " -> " + after);
        assertEquals(Map.of("answer", "hi"), templateData.get("taskA"));
    }

    private static LlmConfiguration.Task shapeTask(Consumer<ResponseValidation> validation) {
        var t = task(v -> v.setOnSchemaMismatch("retry"));
        validation.accept(t.getResponseValidation());
        t.setNonBlankFields(List.of("answer"));
        return t;
    }

    @Test
    @DisplayName("onSchemaMismatch retry: a blank required field is re-asked with the violation as the reason, then valid")
    void schemaMismatchRetriesThenValid() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text("{\"answer\":\"\"}"), Step.text(VALID));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(shapeTask(v -> {
        }));

        assertEquals(2, model.callCount());
        var second = model.requests().get(1).messages();
        var corrective = ((UserMessage) second.getLast()).singleText();
        assertTrue(corrective.startsWith("Your previous reply could not be used: nonBlank: $.answer"), corrective);
        assertEquals(Map.of("answer", "hi"), templateData.get("taskA"));
        verify(dataFactory, never()).createData(eq("llm:fallback:taskA"), any());
    }

    @Test
    @DisplayName("onSchemaMismatch retry exhausted: the fallback is served")
    void schemaMismatchFallsBack() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text("{\"answer\":\"\"}")).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(shapeTask(v -> v.setFallbackMessage("Please try again.")));

        assertEquals(2, model.callCount());
        assertEquals("Please try again.", templateData.get("taskA"));
        verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
    }

    @Test
    @DisplayName("onSchemaMismatch ignore: the parsed object is kept, no re-ask")
    void schemaMismatchIgnoreKeepsTheObject() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text("{\"answer\":\"\"}"));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(shapeTask(v -> v.setOnSchemaMismatch("ignore")));

        assertEquals(1, model.callCount());
        assertEquals(Map.of("answer", ""), templateData.get("taskA"));
    }

    @Test
    @DisplayName("context too long with maxContextTokens: the token budget is halved for the one re-ask")
    void tokenAwareWindowIsHalved() throws Exception {
        String filler = "word ".repeat(100);
        var outputs = new ArrayList<ConversationOutput>();
        for (int i = 0; i < 6; i++) {
            var o = new ConversationOutput();
            o.put("input", "q" + i + " " + filler);
            o.put("output", List.of("a" + i + " " + filler));
            outputs.add(o);
        }
        var last = new ConversationOutput();
        last.put("input", "question");
        outputs.add(last);
        when(memory.getConversationOutputs()).thenReturn(outputs);
        var model = FaultInjectingChatModel.script(Step.fail(new InvalidRequestException("This model's maximum context length is 8192 tokens")),
                Step.text(VALID));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(v -> v.setOnContextTooLong("retry"));
        task.setMaxContextTokens(2000);
        task.getParameters().put("modelName", "gpt-4o"); // a model jtokkit knows

        run(task);

        assertEquals(2, model.callCount());
        int before = model.requests().get(0).messages().size();
        int after = model.requests().get(1).messages().size();
        assertTrue(after < before, "token budget not reduced: " + before + " -> " + after);
        assertEquals(Map.of("answer", "hi"), templateData.get("taskA"));
    }

    @Test
    @DisplayName("buffered streaming: exhausted re-asks emit only the fallback, once, never the raw reply")
    void bufferedTaskEmitsFallbackOnce() throws Exception {
        var sink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(sink);
        var model = FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(retryInvalidJson().andThen(v -> v.setFallbackMessage("Please try again.")));
        task.getParameters().put("addToOutput", "true");

        run(task);

        verify(sink, times(1)).onToken(anyString());
        verify(sink).onToken("Please try again.");
        verify(sink, never()).onToken(PROSE);
    }

    @Test
    @DisplayName("buffered streaming: onError fallback after a failed re-ask path emits the fallback once")
    void bufferedTaskOnErrorEmitsFallbackOnce() throws Exception {
        var sink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(sink);
        var model = FaultInjectingChatModel.script(Step.status(401)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task(retryInvalidJson().andThen(v -> v.setFallbackMessage("Please try again.")));
        var onError = new LlmConfiguration.OnError();
        onError.setAction("fallback");
        task.setOnError(onError);

        run(task);

        verify(sink, times(1)).onToken(anyString());
        verify(sink).onToken("Please try again.");
    }

    @Test
    @DisplayName("tool mode: only the final answer is re-asked, over a transcript that already holds the tool result; the tool runs once")
    void toolModeNeverReexecutesATool() throws Exception {
        var toolRuns = new AtomicInteger();
        var call = ToolExecutionRequest.builder().id("c1").name("placeOrder").arguments("{\"item\":\"pizza\"}").build();
        List<ChatMessage> exchange = List.of(AiMessage.from(call), ToolExecutionResultMessage.from(call, "order #42 placed"));
        var model = FaultInjectingChatModel.script(Step.text(VALID));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        // the loop: runs the tool (once) and ends in an unusable final answer
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            toolRuns.incrementAndGet();
            return new AgentOrchestrator.ExecutionResult(PROSE, new ArrayList<>(), Map.of("tokenUsage", Map.of("inputTokens", 10L)), exchange);
        });
        when(agentOrchestrator.reaskFinalAnswer(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ChatModel chatModel = inv.getArgument(0);
            List<ChatMessage> transcript = inv.getArgument(1);
            var reply = chatModel.chat(ChatRequest.builder().messages(transcript).build());
            return new AgentOrchestrator.ExecutionResult(reply.aiMessage().text(), List.of(), Map.of("tokenUsage", Map.of("inputTokens", 15L)));
        });

        run(task(retryInvalidJson()));

        assertEquals(1, toolRuns.get());
        verify(agentOrchestrator, times(1)).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        assertEquals(1, model.callCount());
        var transcript = model.requests().get(0).messages();
        assertTrue(transcript.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage r && r.text().equals("order #42 placed")));
        assertTrue(transcript.getLast() instanceof UserMessage corrective
                && corrective.singleText().startsWith("Your previous reply could not be used"));
        assertEquals(Map.of("answer", "hi"), templateData.get("taskA"));
    }

    @Test
    @DisplayName("context too long without the retry policy fails the turn as before")
    void contextTooLongWithoutPolicyFails() throws Exception {
        var model = FaultInjectingChatModel.script(Step.fail(new InvalidRequestException("This model's maximum context length is 8192 tokens")))
                .repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        assertThrows(LifecycleException.class, () -> run(task(v -> {
        })));
        assertEquals(1, model.callCount());
    }
}
