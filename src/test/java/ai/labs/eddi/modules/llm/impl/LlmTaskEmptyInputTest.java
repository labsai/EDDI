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
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * The model request of a task that runs at conversation start (the greeting
 * fires from {@code CONVERSATION_START} with empty input) must never leave EDDI
 * without a user or assistant message: Gemini answers a request holding only a
 * system instruction with 400 "contents is not specified". The synthetic turn
 * is request-only.
 */
@DisplayName("LlmTask — a request always carries conversational content")
class LlmTaskEmptyInputTest {

    private static final String DEFAULT_TURN = ConversationHistoryBuilder.DEFAULT_EMPTY_INPUT_PROMPT;

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

    private LlmTask llmTask;
    private ConversationOutput openingOutput;

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
        lenient().when(actionData.getResult()).thenReturn(List.of("CONVERSATION_START"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(memory.getConversationProperties()).thenReturn(new ConversationProperties(memory));
        // Conversation start: step 0 has no user input at all.
        openingOutput = new ConversationOutput();
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(openingOutput));

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine, new JsonSerialization(new ObjectMapper()),
                prePostUtils, chatModelRegistry, mock(IApiCallExecutor.class), mock(IAgentStore.class), mock(IWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), conversationSummarizer, promptSnippetService, globalVariableResolver,
                counterweightService, identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(), new SimpleMeterRegistry(),
                new CallerIdentityContext(null, null));
    }

    private static LlmConfiguration.Task greetingTask() {
        var t = new LlmConfiguration.Task();
        t.setId("greeting");
        t.setType("openai");
        t.setActions(List.of("CONVERSATION_START"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You greet the user.");
        t.setParameters(params);
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        t.setRetry(retry);
        return t;
    }

    private FaultInjectingChatModel liveModel() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text("Hello!")).repeatLast();
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        return model;
    }

    private void run(LlmConfiguration.Task task) throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task)));
    }

    private static boolean hasConversationalContent(List<ChatMessage> messages) {
        return messages.stream().anyMatch(m -> m instanceof UserMessage || m instanceof AiMessage);
    }

    private static String lastUserText(List<ChatMessage> messages) {
        return ((UserMessage) messages.stream().filter(UserMessage.class::isInstance).reduce((a, b) -> b).orElseThrow()).singleText();
    }

    @Test
    @DisplayName("legacy path: a greeting at conversation start sends a user turn next to the system message")
    void legacyGreetingHasContent() throws Exception {
        var model = liveModel();

        run(greetingTask());

        assertEquals(1, model.callCount());
        List<ChatMessage> sent = model.requests().getFirst().messages();
        assertInstanceOf(SystemMessage.class, sent.getFirst());
        assertTrue(hasConversationalContent(sent), "a system-only request is a 400 on Gemini");
        assertEquals(DEFAULT_TURN, lastUserText(sent));
    }

    @Test
    @DisplayName("the turn text is configurable through emptyInputPrompt")
    void configuredPromptIsUsed() throws Exception {
        var model = liveModel();
        var task = greetingTask();
        task.getParameters().put("emptyInputPrompt", "Say hello in German.");

        run(task);

        assertEquals("Say hello in German.", lastUserText(model.requests().getFirst().messages()));
    }

    @Test
    @DisplayName("a blank emptyInputPrompt falls back to the default, it is not a switch")
    void blankPromptFallsBackToDefault() throws Exception {
        var model = liveModel();
        var task = greetingTask();
        task.getParameters().put("emptyInputPrompt", "  ");

        run(task);

        assertEquals(DEFAULT_TURN, lastUserText(model.requests().getFirst().messages()));
    }

    @Test
    @DisplayName("token-aware windowing path: the same guarantee")
    void tokenAwareGreetingHasContent() throws Exception {
        var model = liveModel();
        var task = greetingTask();
        task.setMaxContextTokens(4000);

        run(task);

        assertEquals(DEFAULT_TURN, lastUserText(model.requests().getFirst().messages()));
    }

    @Test
    @DisplayName("a request that already has content is unchanged")
    void requestWithContentIsUnchanged() throws Exception {
        var model = liveModel();
        openingOutput.put("input", "question");

        run(greetingTask());

        List<ChatMessage> sent = model.requests().getFirst().messages();
        assertEquals(2, sent.size(), "system + the user's question, nothing added");
        assertEquals("question", lastUserText(sent));
    }

    @Test
    @DisplayName("the synthetic turn is request-only: nothing of it reaches memory, the step or the stored output")
    void syntheticTurnIsNotStored() throws Exception {
        liveModel();

        run(greetingTask());

        assertFalse(openingOutput.containsKey("input"), "the stored conversation output gained no input");
        for (Object mock : List.of(memory, currentStep, dataFactory, prePostUtils)) {
            for (Invocation call : mockingDetails(mock).getInvocations()) {
                for (Object arg : call.getArguments()) {
                    assertFalse(String.valueOf(arg).contains(DEFAULT_TURN), "synthetic turn leaked into " + call);
                }
            }
        }
    }

    @Test
    @DisplayName("tool loop (agent mode) receives the turn in the history it is handed")
    void agentModeGreetingHasContent() throws Exception {
        liveModel();
        List<List<ChatMessage>> handed = new ArrayList<>();
        lenient().when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> {
                    handed.add(new ArrayList<>(inv.<List<ChatMessage>>getArgument(2)));
                    return new AgentOrchestrator.ExecutionResult("Hi!", new ArrayList<>());
                });
        var task = greetingTask();
        task.setEnableBuiltInTools(true);

        run(task);

        assertEquals(1, handed.size());
        assertTrue(hasConversationalContent(handed.getFirst()), "the loop adds its own system message; the history must hold the turn");
        assertEquals(DEFAULT_TURN, lastUserText(handed.getFirst()));
    }

    @Test
    @DisplayName("cascade step: the request of the first step carries the turn")
    void cascadeGreetingHasContent() throws Exception {
        var model = liveModel();
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new LlmConfiguration.CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));
        var task = greetingTask();
        task.setModelCascade(cascade);

        run(task);

        assertEquals(DEFAULT_TURN, lastUserText(model.requests().getFirst().messages()));
    }
}
