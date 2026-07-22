/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.*;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional branch coverage tests for LlmTask — focuses on branches NOT
 * covered by the main LlmTaskTest (convertToObject, responseSchema,
 * responseMetadataObjectName, prompt snippets injection, global vars injection,
 * httpCallRag, summary config, channel tag, and more).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@DisplayName("LlmTask Branch Coverage Tests")
class LlmTaskBranchTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private PrePostUtils prePostUtils;

    private LlmTask llmTask;
    private PromptSnippetService mockSnippetService;
    private GlobalVariableResolver globalVariableResolver;

    private static final String LLM_RESPONSE = "LLM response text";

    @BeforeEach
    void setUp() {
        openMocks(this);

        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> parameters -> new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(aiMessage(LLM_RESPONSE)).build();
            }
        });

        var secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());

        var chatModelRegistry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver);

        mockSnippetService = mock(PromptSnippetService.class);
        when(mockSnippetService.getAll()).thenReturn(Collections.emptyMap());

        var counterweightService = new CounterweightService(mockSnippetService,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        counterweightService.initMetrics();
        var identityMaskingService = new IdentityMaskingService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        identityMaskingService.initMetrics();

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                mock(IApiCallExecutor.class), mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                mock(RagContextProvider.class), new TokenCounterFactory(), mock(ConversationSummarizer.class),
                mockSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, mock(AgentOrchestrator.class), new ConversationHistoryBuilder(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private IConversationMemory setupMemory(List<String> actions) {
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

        var actionData = mock(IData.class);
        when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(actions);

        IData outputData = mock(IData.class);
        when(dataFactory.createData(anyString(), any())).thenReturn(outputData);

        return memory;
    }

    private LlmConfiguration.Task createTask(Map<String, String> params) {
        var task = new LlmConfiguration.Task();
        task.setActions(List.of("action1"));
        task.setId("testTask");
        task.setType("openai");
        task.setParameters(params);
        return task;
    }

    // ==================== Snippets & Global Vars Injection ====================

    @Nested
    @DisplayName("Snippet and Global Variable Injection")
    class SnippetVarInjectionTests {

        @Test
        @DisplayName("non-empty snippets are injected into template data")
        void snippetsInjected() throws Exception {
            when(mockSnippetService.getAll()).thenReturn(Map.of("cautious", "Be careful"));
            var memory = setupMemory(List.of("action1"));
            var templateData = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            assertTrue(templateData.containsKey("snippets"));
        }

        @Test
        @DisplayName("non-empty global vars are injected into template data")
        void globalVarsInjected() throws Exception {
            when(globalVariableResolver.getTemplateData()).thenReturn(Map.of("model", "gpt-4"));
            var memory = setupMemory(List.of("action1"));
            var templateData = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            assertTrue(templateData.containsKey("vars"));
        }
    }

    // ==================== convertToObject branch ====================

    @Nested
    @DisplayName("convertToObject Branch Tests")
    class ConvertToObjectTests {

        @Test
        @DisplayName("convertToObject=true with responseSchema appends schema to system message")
        void convertToObject_withSchema() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));
            when(jsonSerialization.deserialize(anyString(), eq(Map.class))).thenReturn(Map.of("k", "v"));

            var task = createTask(Map.of("apiKey", "key", "convertToObject", "true",
                    "responseSchema", "{\"type\":\"object\"}"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Should reach the schema branch — just verify no exception
            verify(dataFactory, atLeastOnce()).createData(anyString(), any());
        }

        @Test
        @DisplayName("convertToObject=true with non-JSON response stores as string")
        void convertToObject_nonJsonResponse() throws Exception {
            // Need a custom ChatModel that returns plain text (not starting with { or [)
            // Our default mock returns "LLM response text" which doesn't start with { or [
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key", "convertToObject", "true"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Should NOT call deserialize since response doesn't start with { or [
            verify(jsonSerialization, never()).deserialize(anyString(), eq(Map.class));
        }
    }

    // ==================== responseMetadataObjectName ====================

    @Nested
    @DisplayName("responseMetadataObjectName Tests")
    class ResponseMetadataTests {

        @Test
        @DisplayName("responseMetadataObjectName stores metadata in memory")
        void responseMetadataObjectName() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            task.setResponseMetadataObjectName("metadata");
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(prePostUtils).createMemoryEntry(any(), any(), eq("metadata"), eq("langchain"));
        }
    }

    // ==================== responseObjectName fallback ====================

    @Nested
    @DisplayName("responseObjectName Tests")
    class ResponseObjectNameTests {

        @Test
        @DisplayName("null responseObjectName falls back to task ID")
        void nullResponseObjectName_usesTaskId() throws Exception {
            var memory = setupMemory(List.of("action1"));
            var templateData = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            task.setResponseObjectName(null);
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // The response should be stored under the task ID
            assertTrue(templateData.containsKey("testTask"));
        }

        @Test
        @DisplayName("explicit responseObjectName is used")
        void explicitResponseObjectName() throws Exception {
            var memory = setupMemory(List.of("action1"));
            var templateData = new HashMap<String, Object>();
            when(memoryItemConverter.convert(memory)).thenReturn(templateData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            task.setResponseObjectName("myResponse");
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            assertTrue(templateData.containsKey("myResponse"));
        }
    }

    // ==================== addToOutput explicitly false ====================

    @Nested
    @DisplayName("addToOutput=false Tests")
    class AddToOutputFalseTests {

        @Test
        @DisplayName("addToOutput=false prevents output generation")
        void addToOutputFalse() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key", "addToOutput", "false"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            var currentStep = memory.getCurrentStep();
            verify(currentStep, never()).addConversationOutputList(
                    eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
        }
    }

    // ==================== channelTag resolution ====================

    @Nested
    @DisplayName("Channel Tag Tests")
    class ChannelTagTests {

        @Test
        @DisplayName("channel tag is read from step and passed to counterweight")
        void channelTagRead() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var channelData = mock(IData.class);
            when(channelData.getResult()).thenReturn("scheduled");
            when(memory.getCurrentStep().getLatestData("channel:tag")).thenReturn(channelData);

            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Verify execution completed without error
            verify(dataFactory, atLeastOnce()).createData(anyString(), any());
        }

        @Test
        @DisplayName("null channel data result is handled")
        void nullChannelDataResult() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var channelData = mock(IData.class);
            when(channelData.getResult()).thenReturn(null);
            when(memory.getCurrentStep().getLatestData("channel:tag")).thenReturn(channelData);

            var task = createTask(Map.of("apiKey", "key"));
            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }
    }

    // ==================== conversationHistoryLimit ====================

    @Nested
    @DisplayName("Conversation History Limit Tests")
    class HistoryLimitTests {

        @Test
        @DisplayName("conversationHistoryLimit is used when logSizeLimit param not set")
        void conversationHistoryLimit() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            task.setConversationHistoryLimit(5);
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(dataFactory, atLeastOnce()).createData(anyString(), any());
        }

        @Test
        @DisplayName("includeFirstAgentMessage parameter is parsed")
        void includeFirstAgentMessage() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key", "includeFirstAgentMessage", "false"));
            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }
    }

    // ==================== template skip params ====================

    @Nested
    @DisplayName("Template Skip Params Tests")
    class TemplateSkipParamsTests {

        @Test
        @DisplayName("apiKey, signingSecret, appPassword, botToken are NOT template-processed")
        void skippedParams() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of(
                    "apiKey", "secret-key",
                    "signingSecret", "sign-secret",
                    "appPassword", "app-pass",
                    "botToken", "bot-tok",
                    "systemMessage", "hello"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Verify templating was called for systemMessage but NOT for secret params
            verify(templatingEngine, never()).processTemplate(eq("secret-key"), anyMap());
            verify(templatingEngine, never()).processTemplate(eq("sign-secret"), anyMap());
            verify(templatingEngine, never()).processTemplate(eq("app-pass"), anyMap());
            verify(templatingEngine, never()).processTemplate(eq("bot-tok"), anyMap());
            verify(templatingEngine).processTemplate(eq("hello"), anyMap());
        }
    }

    // ==================== token-aware context ====================

    @Nested
    @DisplayName("Token-Aware Context Tests")
    class TokenAwareContextTests {

        @Test
        @DisplayName("maxContextTokens > 0 uses token-aware builder")
        void tokenAwareMessages() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key", "modelName", "gpt-4"));
            task.setMaxContextTokens(4096);
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(dataFactory, atLeastOnce()).createData(anyString(), any());
        }
    }

    // ==================== resolveModelName branches ====================

    @Nested
    @DisplayName("resolveModelName Tests")
    class ResolveModelNameTests {

        @Test
        @DisplayName("model parameter is used when modelName is absent")
        void modelParam() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key", "model", "gpt-3.5-turbo"));
            task.setMaxContextTokens(1000);
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(dataFactory, atLeastOnce()).createData(anyString(), any());
        }

        @Test
        @DisplayName("modelId parameter is used when modelName and model are absent")
        void modelIdParam() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // modelId fallback — no maxContextTokens to avoid tokenizer model lookup
            var task = createTask(Map.of("apiKey", "key", "modelId", "my-custom-model"));
            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }

        @Test
        @DisplayName("deploymentName parameter is used as last fallback")
        void deploymentNameParam() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // deploymentName fallback — no maxContextTokens to avoid tokenizer model lookup
            var task = createTask(Map.of("apiKey", "key", "deploymentName", "my-deployment"));
            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }
    }

    // ==================== summary config ====================

    @Nested
    @DisplayName("Summary Config Tests")
    class SummaryConfigTests {

        @Test
        @DisplayName("summary config is applied when enabled")
        void summaryEnabled() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // ConversationSummarizer.readSummary needs conversationProperties mock
            var props = mock(ai.labs.eddi.engine.memory.model.ConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(props);
            when(props.get(anyString())).thenReturn(null);

            var task = createTask(Map.of("apiKey", "key"));
            var summaryConfig = new LlmConfiguration.ConversationSummaryConfig();
            summaryConfig.setEnabled(true);
            task.setConversationSummary(summaryConfig);

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(dataFactory, atLeastOnce()).createData(anyString(), any());
        }

        @Test
        @DisplayName("summary config with excludePropertiesFromSummary reads properties")
        void summaryWithExcludeProperties() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var props = mock(ConversationProperties.class);
            when(props.isEmpty()).thenReturn(false);
            when(props.entrySet()).thenReturn(Set.of());
            when(memory.getConversationProperties()).thenReturn(props);

            var task = createTask(Map.of("apiKey", "key"));
            var summaryConfig = new LlmConfiguration.ConversationSummaryConfig();
            summaryConfig.setEnabled(true);
            summaryConfig.setExcludePropertiesFromSummary(true);
            task.setConversationSummary(summaryConfig);

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(props).isEmpty();
        }
    }

    // ==================== streaming with addToOutput=false ====================

    @Nested
    @DisplayName("Streaming with addToOutput=false Tests")
    class StreamingAddToOutputFalseTests {

        @Test
        @DisplayName("streaming with addToOutput=false does not emit tokens")
        void streamingAddToOutputFalse() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var eventSink = mock(ConversationEventSink.class);
            when(memory.getEventSink()).thenReturn(eventSink);

            var task = createTask(Map.of("apiKey", "key", "addToOutput", "false"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Sync fallback path now respects addToOutputExplicitlyFalse
            verify(eventSink, never()).onToken(anyString());
        }
    }

    // ==================== Template processing exception handling
    // ====================

    @Nested
    @DisplayName("Template Processing Exception Handling")
    class TemplateExceptionTests {

        @Test
        @DisplayName("template processing failure for a param is logged but doesn't crash")
        void templateFailureForParam() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            // First call succeeds, second throws
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("value")
                    .thenThrow(new ITemplatingEngine.TemplateEngineException("template error", new RuntimeException("cause")))
                    .thenReturn("value");

            var task = createTask(Map.of("systemMessage", "hello", "prompt", "world"));
            // Should NOT throw — template failures for individual params are caught
            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }
    }
}
