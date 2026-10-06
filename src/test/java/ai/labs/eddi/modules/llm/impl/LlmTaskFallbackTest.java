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
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
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
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * R6 / R7: the configurable, JSON-aware fallback and the task-level
 * {@code onError}. The model phase is driven by the scripted
 * {@link FaultInjectingChatModel}.
 */
@DisplayName("LlmTask — fallback and onError (R6/R7)")
class LlmTaskFallbackTest {

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

    private static LlmConfiguration.Task task(boolean convertToObject) {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You are helpful.");
        if (convertToObject) {
            params.put("convertToObject", "true");
        }
        t.setParameters(params);
        // one attempt: the retry layer is not under test and must not sleep
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        t.setRetry(retry);
        return t;
    }

    private static LlmConfiguration.Task withOnError(LlmConfiguration.Task task, String action) {
        var onError = new LlmConfiguration.OnError();
        onError.setAction(action);
        task.setOnError(onError);
        return task;
    }

    private static LlmConfiguration.ResponseValidation validation(String message, String field) {
        var v = new LlmConfiguration.ResponseValidation();
        v.setEnabled(true);
        v.setFallbackMessage(message);
        v.setFallbackField(field);
        return v;
    }

    private void liveModelReplies(Step... script) throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(FaultInjectingChatModel.script(script).repeatLast());
    }

    private void run(LlmConfiguration.Task task) throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task)));
    }

    private double recovery(String trigger) {
        var counter = meterRegistry.find("eddi.llm.recovery").tag("action", "fallback").tag("outcome", "served").tag("trigger", trigger).counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    @DisplayName("onError")
    class OnErrorTests {

        @Test
        @DisplayName("fallback: a provider 500 completes the turn with the default fallback, the failure recorded and counted")
        void providerFailureIsAbsorbed() throws Exception {
            liveModelReplies(Step.status(500));

            assertDoesNotThrow(() -> run(withOnError(task(false), "fallback")));

            assertEquals(LlmConfiguration.ResponseValidation.DEFAULT_FALLBACK_MESSAGE, templateData.get("taskA"));
            verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
            var error = ArgumentCaptor.forClass(Object.class);
            verify(dataFactory).createData(eq("llm:error:taskA"), error.capture());
            assertTrue(error.getValue() instanceof Map<?, ?> m && m.containsKey("class") && m.containsKey("message"));
            assertEquals(1, recovery("onError"));
            // the turn went on: postResponse still ran, and nothing was parsed as model
            // output
            verify(prePostUtils).runPostResponse(eq(memory), any(), eq(templateData), eq(200), eq(false));
            verify(dataFactory, never()).createData(eq("llm:output:outcome:taskA"), any());
            // the conversation is never flagged ERROR by the task
            verify(memory, never()).setConversationState(any());
        }

        @Test
        @DisplayName("absent: the failure propagates exactly as before, with no fallback recorded")
        void absentPropagates() throws Exception {
            liveModelReplies(Step.status(500));

            assertThrows(LifecycleException.class, () -> run(task(false)));

            verify(dataFactory, never()).createData(eq("llm:fallback:taskA"), any());
            assertEquals(0, recovery("onError"));
        }

        @Test
        @DisplayName("an explicit action of error propagates too")
        void explicitErrorPropagates() throws Exception {
            liveModelReplies(Step.status(500));

            assertThrows(LifecycleException.class, () -> run(withOnError(task(false), "error")));
        }

        @Test
        @DisplayName("a model that cannot even be built is absorbed")
        void modelConstructionFailureIsAbsorbed() throws Exception {
            when(chatModelRegistry.getOrCreate(anyString(), any())).thenThrow(new IllegalArgumentException("no api key"));

            assertDoesNotThrow(() -> run(withOnError(task(false), "fallback")));

            verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
        }

        @Test
        @DisplayName("a responseValidation of error is absorbed as well")
        void validationErrorIsAbsorbed() throws Exception {
            liveModelReplies(Step.empty());
            var task = withOnError(task(false), "fallback");
            var v = validation(null, null);
            v.setOnEmpty("error");
            task.setResponseValidation(v);

            assertDoesNotThrow(() -> run(task));

            assertEquals(LlmConfiguration.ResponseValidation.DEFAULT_FALLBACK_MESSAGE, templateData.get("taskA"));
            verify(dataFactory).createData(eq("llm:error:taskA"), any());
        }

        @Test
        @DisplayName("data the failed phase wrote is marked uncommitted; data that predates it is not")
        void partialDataIsUncommitted() throws Exception {
            IData<?> older = mock(IData.class);
            lenient().when(older.getKey()).thenReturn("older");
            IData<?> partial = mock(IData.class);
            lenient().when(partial.getKey()).thenReturn("partial");
            var elements = new ArrayList<IData<?>>(List.of(older));
            when(currentStep.getAllElements()).thenAnswer(inv -> new ArrayList<>(elements));
            when(chatModelRegistry.getOrCreate(anyString(), any())).thenAnswer(inv -> {
                elements.add(partial); // written during the failing phase
                throw new IllegalStateException("boom");
            });

            run(withOnError(task(false), "fallback"));

            verify(partial).setCommitted(false);
            verify(older, never()).setCommitted(false);
        }

        @Test
        @DisplayName("the recorded failure carries no URL or secret")
        void failureRecordIsSanitized() throws Exception {
            liveModelReplies(Step.fail(new IllegalStateException(
                    "401 from https://api.example.test/v1?key=AIzaSyA-1234567890abcdefghijklmnopqrstuv body={\"x\":1}")));

            run(withOnError(task(false), "fallback"));

            var error = ArgumentCaptor.forClass(Object.class);
            verify(dataFactory).createData(eq("llm:error:taskA"), error.capture());
            String message = String.valueOf(((Map<?, ?>) error.getValue()).get("message"));
            assertFalse(message.contains("https://"), message);
            assertFalse(message.contains("AIzaSy"), message);
        }
    }

    @Nested
    @DisplayName("control flow is never swallowed")
    class ControlFlow {

        @Test
        @DisplayName("a HITL tool approval signal propagates")
        void toolApprovalPropagates() throws Exception {
            when(chatModelRegistry.getOrCreate(anyString(), any())).thenThrow(new ToolApprovalRequiredException("approve", null));

            assertThrows(ToolApprovalRequiredException.class, () -> run(withOnError(task(false), "fallback")));
            verify(dataFactory, never()).createData(eq("llm:fallback:taskA"), any());
        }

        @Test
        @DisplayName("a graceful-shutdown interrupt propagates, even wrapped")
        void interruptPropagates() throws Exception {
            when(chatModelRegistry.getOrCreate(anyString(), any())).thenAnswer(inv -> {
                throw new IllegalStateException("wrapped", new LifecycleException.LifecycleInterruptedException("shutdown"));
            });

            assertThrows(IllegalStateException.class, () -> run(withOnError(task(false), "fallback")));
            verify(dataFactory, never()).createData(eq("llm:fallback:taskA"), any());
        }

        @Test
        @DisplayName("a cancellation propagates")
        void cancellationPropagates() throws Exception {
            when(chatModelRegistry.getOrCreate(anyString(), any())).thenThrow(new CancellationException());

            assertThrows(CancellationException.class, () -> run(withOnError(task(false), "fallback")));
        }

        @Test
        @DisplayName("a cancelled conversation propagates the failure")
        void cancelledConversationPropagates() throws Exception {
            when(memory.isCancelled()).thenReturn(true);
            liveModelReplies(Step.status(500));

            assertThrows(LifecycleException.class, () -> run(withOnError(task(false), "fallback")));
        }

        @Test
        @DisplayName("an interrupted thread propagates the failure")
        void interruptedThreadPropagates() throws Exception {
            when(chatModelRegistry.getOrCreate(anyString(), any())).thenThrow(new IllegalStateException("boom"));
            Thread.currentThread().interrupt();
            try {
                assertThrows(IllegalStateException.class, () -> run(withOnError(task(false), "fallback")));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Nested
    @DisplayName("the fallback itself")
    class TheFallback {

        @Test
        @DisplayName("fallbackField wraps it under convertToObject, so existing output templates render it unchanged")
        void jsonWrapped() throws Exception {
            liveModelReplies(Step.status(500));
            var task = withOnError(task(true), "fallback");
            task.setResponseValidation(validation("Please try again.", "htmlResponseText"));

            run(task);

            assertEquals(Map.of("htmlResponseText", "Please try again."), templateData.get("taskA"));
            verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
            // the raw model-output slot holds the JSON it was wrapped into
            verify(dataFactory).createData(eq("langchain:openai:taskA"), eq("{\"htmlResponseText\":\"Please try again.\"}"));
        }

        @Test
        @DisplayName("without fallbackField it stays a plain string, as before")
        void plainWithoutField() throws Exception {
            liveModelReplies(Step.status(500));
            var task = withOnError(task(true), "fallback");
            task.setResponseValidation(validation("Please try again.", null));

            run(task);

            assertEquals("Please try again.", templateData.get("taskA"));
        }

        @Test
        @DisplayName("the existing responseValidation fallback action is wrapped and flagged too")
        void validationFallbackActionIsWrapped() throws Exception {
            liveModelReplies(Step.empty());
            var task = task(true);
            var v = validation("Try later.", "htmlResponseText");
            v.setOnEmpty("fallback");
            task.setResponseValidation(v);

            run(task);

            assertEquals(Map.of("htmlResponseText", "Try later."), templateData.get("taskA"));
            verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
            assertEquals(1, recovery("validation"));
            verify(dataFactory, never()).createData(eq("llm:error:taskA"), any());
        }

        @Test
        @DisplayName("the default message is the sentence the fallback action always used")
        void defaultMessage() throws Exception {
            liveModelReplies(Step.empty());
            var task = task(false);
            var v = validation(null, null);
            v.setOnEmpty("fallback");
            task.setResponseValidation(v);

            run(task);

            assertEquals("I'm sorry, I wasn't able to generate a complete response. Please try again.", templateData.get("taskA"));
        }

        @Test
        @DisplayName("a template that fails to render degrades to the default sentence")
        void brokenTemplateDegrades() throws Exception {
            liveModelReplies(Step.status(500));
            when(templatingEngine.processTemplate(eq("{broken"), any())).thenThrow(new ITemplatingEngine.TemplateEngineException("bad", null));
            var task = withOnError(task(false), "fallback");
            task.setResponseValidation(validation("{broken", null));

            assertDoesNotThrow(() -> run(task));

            assertEquals(LlmConfiguration.ResponseValidation.DEFAULT_FALLBACK_MESSAGE, templateData.get("taskA"));
        }

        @Test
        @DisplayName("fallbackQuickReplies are added next to the text the way output sets add them")
        void quickReplies() throws Exception {
            liveModelReplies(Step.status(500));
            var task = withOnError(task(false), "fallback");
            var v = validation("Sorry", null);
            v.setFallbackQuickReplies(List.of(new QuickReply("Try again", "retry_last", true)));
            task.setResponseValidation(v);

            run(task);

            var expected = List.of(new QuickReply("Try again", "retry_last", true));
            verify(currentStep).addConversationOutputList(eq("quickReplies"), eq(expected));
            verify(dataFactory).createData(eq("quickReplies:llm:fallback:taskA"), eq(expected));
        }

        @Test
        @DisplayName("with addToOutput the output item carries the fallback flag and the message, not the JSON wrapper")
        void outputItemIsFlagged() throws Exception {
            liveModelReplies(Step.status(500));
            var task = withOnError(task(true), "fallback");
            task.getParameters().put("addToOutput", "true");
            task.setResponseValidation(validation("Please try again.", "htmlResponseText"));

            run(task);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Object>> items = ArgumentCaptor.forClass(List.class);
            verify(currentStep).addConversationOutputList(eq("output"), items.capture());
            var item = (TextOutputItem) items.getValue().getFirst();
            assertEquals("Please try again.", item.getText());
            assertEquals(Boolean.TRUE, item.getFallback());
        }

        @Test
        @DisplayName("a model answer is not flagged and a normal turn writes none of this")
        void normalTurnUntouched() throws Exception {
            liveModelReplies(Step.text("hello"));

            run(withOnError(task(false), "fallback"));

            assertEquals("hello", templateData.get("taskA"));
            verify(dataFactory, never()).createData(eq("llm:fallback:taskA"), any());
            assertEquals(0, recovery("onError"));
        }
    }

    @Nested
    @DisplayName("localized template (real engine)")
    class Localized {

        @Test
        @DisplayName("a {#if} on a property picks the language")
        void localized() throws Exception {
            var engine = Engine.builder().addDefaults().build();
            var handler = new LlmFallbackHandler(new TemplatingEngine(engine), new JsonSerialization(new ObjectMapper()), dataFactory,
                    meterRegistry);
            var task = task(false);
            task.setResponseValidation(validation("{#if properties.lang == 'de'}Entschuldigung, bitte erneut versuchen.{#else}Sorry.{/if}", null));

            var de = handler.serve(task, Map.of("properties", Map.of("lang", "de")), currentStep, "onError", null, false, "c1");
            var en = handler.serve(task, Map.of("properties", Map.of("lang", "en")), currentStep, "onError", null, false, "c1");

            assertEquals("Entschuldigung, bitte erneut versuchen.", de.text());
            assertEquals("Sorry.", en.text());
        }
    }
}
