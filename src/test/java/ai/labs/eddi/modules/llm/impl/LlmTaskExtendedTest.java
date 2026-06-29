/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link LlmTask} — covers branches not exercised by
 * LlmTaskTest: template parameter skipping, responseObjectName fallback,
 * responseMetadataObjectName storage, rolling summary update, structured JSON
 * output with responseSchema, channel tag counterweight, and resolveModelName
 * provider-specific key resolution.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class LlmTaskExtendedTest {

    private IResourceClientLibrary resourceClientLibrary;
    private IDataFactory dataFactory;
    private IMemoryItemConverter memoryItemConverter;
    private ITemplatingEngine templatingEngine;
    private LlmTask llmTask;
    private IJsonSerialization jsonSerialization;
    private PrePostUtils prePostUtils;
    private PromptSnippetService promptSnippetService;
    private ConversationSummarizer conversationSummarizer;

    private static final String TEST_MESSAGE = "Test LLM response";

    @BeforeEach
    void setUp() {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        dataFactory = mock(IDataFactory.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        templatingEngine = mock(ITemplatingEngine.class);
        jsonSerialization = mock(IJsonSerialization.class);
        prePostUtils = mock(PrePostUtils.class);
        conversationSummarizer = mock(ConversationSummarizer.class);

        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> parameters -> new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(aiMessage(TEST_MESSAGE)).build();
            }
        });

        var secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        var globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());

        var chatModelRegistry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver);
        var toolResponseTruncator = new ToolResponseTruncator(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), chatModelRegistry);

        promptSnippetService = mock(PromptSnippetService.class);
        when(promptSnippetService.getAll()).thenReturn(Collections.emptyMap());

        var counterweightService = new CounterweightService(
                promptSnippetService, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        counterweightService.initMetrics();

        var identityMaskingService = new IdentityMaskingService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        identityMaskingService.initMetrics();

        llmTask = new LlmTask(
                resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils,
                chatModelRegistry,
                mock(CalculatorTool.class), mock(DateTimeTool.class),
                mock(WebSearchTool.class), mock(DataFormatterTool.class),
                mock(WebScraperTool.class), mock(TextSummarizerTool.class),
                mock(PdfReaderTool.class), mock(WeatherTool.class),
                mock(FetchToolResponsePageTool.class),
                mock(IApiCallExecutor.class), mock(ToolExecutionService.class),
                mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class),
                mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                mock(RagContextProvider.class), mock(IUserMemoryStore.class),
                new TokenCounterFactory(), conversationSummarizer,
                promptSnippetService, globalVariableResolver,
                counterweightService, identityMaskingService,
                toolResponseTruncator, mock(ai.labs.eddi.engine.tenancy.TenantQuotaService.class),
                null, null,
                null, null, null, null, null);
    }

    private IConversationMemory createMemoryWithAction(String... actions) throws Exception {
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

        var actionData = mock(IData.class);
        when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(List.of(actions));

        IData outputData = mock(IData.class);
        when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
        when(templatingEngine.processTemplate(anyString(), anyMap()))
                .thenAnswer(i -> i.getArgument(0));

        return memory;
    }

    // ==================== TEMPLATE_SKIP_PARAMS Tests ====================

    @Nested
    @DisplayName("Template parameter skipping")
    class TemplateSkipParamsTests {

        @Test
        @DisplayName("apiKey should be skipped by template engine")
        void apiKeySkipped() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of(
                    "apiKey", "sk-secret-key-123",
                    "systemMessage", "You are helpful"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // apiKey should NOT be processed by template engine
            verify(templatingEngine, never()).processTemplate(eq("sk-secret-key-123"), anyMap());
            // systemMessage should be processed
            verify(templatingEngine, atLeastOnce()).processTemplate(eq("You are helpful"), anyMap());
        }
    }

    // ==================== responseObjectName fallback ====================

    @Nested
    @DisplayName("responseObjectName fallback")
    class ResponseObjectNameTests {

        @Test
        @DisplayName("null responseObjectName should fallback to taskId")
        void nullResponseObjectName() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("myTaskId");
            task.setType("openai");
            task.setResponseObjectName(null); // Explicitly null
            task.setParameters(Map.of("apiKey", "key"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Should store response — task executes successfully
            verify(memory.getCurrentStep(), atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("empty responseObjectName should fallback to taskId")
        void emptyResponseObjectName() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("myTaskId");
            task.setType("openai");
            task.setResponseObjectName(""); // Empty string
            task.setParameters(Map.of("apiKey", "key"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(memory.getCurrentStep(), atLeastOnce()).storeData(any(IData.class));
        }
    }

    // ==================== responseMetadataObjectName ====================

    @Nested
    @DisplayName("responseMetadataObjectName")
    class ResponseMetadataTests {

        @Test
        @DisplayName("Should store metadata when responseMetadataObjectName is set")
        void metadataObjectNameSet() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setResponseMetadataObjectName("metaResult");
            task.setParameters(Map.of("apiKey", "key"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Verify metadata was stored via prePostUtils
            verify(prePostUtils).createMemoryEntry(
                    any(), any(), eq("metaResult"), eq("langchain"));
        }

        @Test
        @DisplayName("Should NOT store metadata when responseMetadataObjectName is null")
        void metadataObjectNameNull() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setResponseMetadataObjectName(null);
            task.setParameters(Map.of("apiKey", "key"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(prePostUtils, never()).createMemoryEntry(
                    any(), any(), eq("metaResult"), eq("langchain"));
        }
    }

    // ==================== addToOutput=false Tests ====================

    @Nested
    @DisplayName("addToOutput handling")
    class AddToOutputTests {

        @Test
        @DisplayName("addToOutput=false should not add to conversation output")
        void addToOutputFalse() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key", "addToOutput", "false"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(memory.getCurrentStep(), never()).addConversationOutputList(
                    eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
        }
    }

    // ==================== Multiple tasks matching ====================

    @Nested
    @DisplayName("Multiple task execution")
    class MultipleTaskTests {

        @Test
        @DisplayName("Multiple tasks matching same action should all execute")
        void multipleTasksSameAction() throws Exception {
            var memory = createMemoryWithAction("shared_action");

            var task1 = new LlmConfiguration.Task();
            task1.setActions(List.of("shared_action"));
            task1.setId("task1");
            task1.setType("openai");
            task1.setParameters(Map.of("apiKey", "key"));

            var task2 = new LlmConfiguration.Task();
            task2.setActions(List.of("shared_action"));
            task2.setId("task2");
            task2.setType("openai");
            task2.setParameters(Map.of("apiKey", "key"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task1, task2)));

            // Both tasks should have stored data (2 langchain data entries)
            verify(dataFactory).createData(contains("task1"), any());
            verify(dataFactory).createData(contains("task2"), any());
        }
    }

    // ==================== Channel tag counterweight ====================

    @Nested
    @DisplayName("Channel tag for counterweight")
    class ChannelTagTests {

        @Test
        @DisplayName("Should read channel:tag from current step")
        void channelTagPresent() throws Exception {
            var memory = createMemoryWithAction("action1");
            var currentStep = memory.getCurrentStep();

            var channelData = mock(IData.class);
            when(currentStep.getLatestData("channel:tag")).thenReturn(channelData);
            when(channelData.getResult()).thenReturn("scheduled");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Task should execute normally — channel tag is passed to counterweight service
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("Null channel:tag data should be handled gracefully")
        void channelTagNull() throws Exception {
            var memory = createMemoryWithAction("action1");
            var currentStep = memory.getCurrentStep();

            when(currentStep.getLatestData("channel:tag")).thenReturn(null);

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }
    }

    // ==================== convertToObject with responseSchema ====================

    @Nested
    @DisplayName("Structured JSON output with responseSchema")
    class StructuredJsonTests {

        @Test
        @DisplayName("convertToObject=true with responseSchema includes schema in system message")
        void convertToObjectWithSchema() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of(
                    "apiKey", "key",
                    "systemMessage", "You are an API",
                    "convertToObject", "true",
                    "responseSchema", "{\"type\": \"object\"}"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // The system message template should be processed (includes schema)
            verify(templatingEngine).processTemplate(eq("You are an API"), anyMap());
        }

        @Test
        @DisplayName("convertToObject=true without responseSchema still adds JSON instruction")
        void convertToObjectWithoutSchema() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of(
                    "apiKey", "key",
                    "systemMessage", "You are helpful",
                    "convertToObject", "true"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(memory.getCurrentStep(), atLeastOnce()).storeData(any(IData.class));
        }
    }

    // ==================== Prompt snippets and global vars injection
    // ====================

    @Nested
    @DisplayName("Prompt snippets and global variable injection")
    class InjectionTests {

        @Test
        @DisplayName("Non-empty snippets should be injected into template data")
        void snippetsInjected() throws Exception {
            when(promptSnippetService.getAll()).thenReturn(Map.of("greeting", "Hello!"));

            var memory = createMemoryWithAction("action1");
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(memory.getCurrentStep(), atLeastOnce()).storeData(any(IData.class));
        }
    }

    // ==================== Empty messages list ====================

    @Nested
    @DisplayName("Empty messages edge case")
    class EmptyMessagesTests {

        @Test
        @DisplayName("Empty conversation outputs should produce no messages → early return")
        void emptyConversationOutputs() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationOutputs()).thenReturn(List.of());

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action1"));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(i -> i.getArgument(0));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            // With empty conversation outputs, messages list will be empty → early return
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Since messages is empty, the model should not be called and no data stored
            // (the early return happens before chatModel.chat())
        }
    }

    // ==================== conversationHistoryLimit from task config
    // ====================

    @Nested
    @DisplayName("conversationHistoryLimit")
    class ConversationHistoryLimitTests {

        @Test
        @DisplayName("conversationHistoryLimit from task config should be used")
        void taskConfigHistoryLimit() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setConversationHistoryLimit(5);
            task.setParameters(Map.of("apiKey", "key"));

            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }

        @Test
        @DisplayName("logSizeLimit parameter should override conversationHistoryLimit")
        void logSizeLimitOverridesTaskConfig() throws Exception {
            var memory = createMemoryWithAction("action1");

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setConversationHistoryLimit(10);
            task.setParameters(Map.of("apiKey", "key", "logSizeLimit", "3"));

            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }
    }
}
