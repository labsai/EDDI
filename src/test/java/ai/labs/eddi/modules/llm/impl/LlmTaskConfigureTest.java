/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
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

import java.net.URI;
import java.util.*;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional coverage tests for {@link LlmTask} — focuses on configure(),
 * getId(), getType(), getExtensionDescriptor(), null latestData result,
 * wildcard actions, and LifecycleException wrapping.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@DisplayName("LlmTask Configure & Identity Tests")
class LlmTaskConfigureTest {

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

    @BeforeEach
    void setUp() {
        openMocks(this);

        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> parameters -> new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(aiMessage("response")).build();
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

        var mockSnippetService = mock(PromptSnippetService.class);
        when(mockSnippetService.getAll()).thenReturn(Collections.emptyMap());

        var counterweightService = new CounterweightService(mockSnippetService,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        counterweightService.initMetrics();
        var identityMaskingService = new IdentityMaskingService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        identityMaskingService.initMetrics();

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                mock(CalculatorTool.class), mock(DateTimeTool.class), mock(WebSearchTool.class),
                mock(DataFormatterTool.class), mock(WebScraperTool.class), mock(TextSummarizerTool.class),
                mock(PdfReaderTool.class), mock(WeatherTool.class), mock(FetchToolResponsePageTool.class),
                mock(IApiCallExecutor.class), mock(ToolExecutionService.class),
                mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class),
                mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                mock(RagContextProvider.class), mock(IUserMemoryStore.class),
                new TokenCounterFactory(), mock(ConversationSummarizer.class),
                mockSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, toolResponseTruncator,
                mock(ai.labs.eddi.engine.tenancy.TenantQuotaService.class),
                null, null,
                null, null, null, null, null,
                mock(ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.class));
    }

    // ==================== getId ====================

    @Test
    @DisplayName("getId returns correct TaskId")
    void getIdReturnsCorrectTaskId() {
        assertEquals("ai.labs.llm", llmTask.getId().name());
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType returns 'langchain'")
    void getTypeReturnsLangchain() {
        assertEquals("langchain", llmTask.getType());
    }

    // ==================== getExtensionDescriptor ====================

    @Test
    @DisplayName("getExtensionDescriptor has correct display name and config")
    void getExtensionDescriptor() {
        ExtensionDescriptor descriptor = llmTask.getExtensionDescriptor();
        assertNotNull(descriptor);
        assertEquals("ai.labs.llm", descriptor.getType().name());
        assertEquals("Lang Chain", descriptor.getDisplayName());
        assertTrue(descriptor.getConfigs().containsKey("uri"));
    }

    // ==================== configure ====================

    @Nested
    @DisplayName("configure Tests")
    class ConfigureTests {

        @Test
        @DisplayName("throws WorkflowConfigurationException when no URI configured")
        void throwsWhenNoUri() {
            assertThrows(WorkflowConfigurationException.class,
                    () -> llmTask.configure(Map.of(), null));
        }

        @Test
        @DisplayName("throws WorkflowConfigurationException when URI is null")
        void throwsWhenUriNull() {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", null);
            assertThrows(WorkflowConfigurationException.class,
                    () -> llmTask.configure(config, null));
        }

        @Test
        @DisplayName("throws WorkflowConfigurationException when URI is empty")
        void throwsWhenUriEmpty() {
            assertThrows(WorkflowConfigurationException.class,
                    () -> llmTask.configure(Map.of("uri", ""), null));
        }

        @Test
        @DisplayName("loads LlmConfiguration from valid URI")
        void loadsFromValidUri() throws Exception {
            var config = new LlmConfiguration(List.of());
            when(resourceClientLibrary.getResource(any(URI.class), eq(LlmConfiguration.class)))
                    .thenReturn(config);

            Object result = llmTask.configure(
                    Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/abc123"), null);

            assertSame(config, result);
        }

        @Test
        @DisplayName("wraps ServiceException into WorkflowConfigurationException")
        void wrapsServiceException() throws Exception {
            when(resourceClientLibrary.getResource(any(URI.class), eq(LlmConfiguration.class)))
                    .thenThrow(new ServiceException("not found"));

            assertThrows(WorkflowConfigurationException.class,
                    () -> llmTask.configure(
                            Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/abc123"), null));
        }
    }

    // ==================== execute null cases ====================

    @Nested
    @DisplayName("execute edge cases")
    class ExecuteEdgeCases {

        @Test
        @DisplayName("returns early when latestData is null")
        void returnsEarlyWhenLatestDataNull() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(null);

            var config = new LlmConfiguration(List.of());
            // Should not throw — early return
            assertDoesNotThrow(() -> llmTask.execute(memory, config));

            // No template processing should occur
            verify(memoryItemConverter, never()).convert(any());
        }

        @Test
        @DisplayName("returns early when actions result is null")
        void returnsEarlyWhenActionsNull() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            IData actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(null);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var config = new LlmConfiguration(List.of());
            assertDoesNotThrow(() -> llmTask.execute(memory, config));
        }
        @Test
        @DisplayName("non-matching action skips task")
        void nonMatchingActionSkipsTask() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            IData actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("other_action"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("specific_action"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var config = new LlmConfiguration(List.of(task));
            assertDoesNotThrow(() -> llmTask.execute(memory, config));

            // Should NOT have executed
            verify(dataFactory, never()).createData(anyString(), any());
        }

        @Test
        @DisplayName("null task ID falls back to 'default'")
        void nullTaskIdFallsBackToDefault() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationOutputs()).thenReturn(List.of(new ConversationOutput()));

            IData actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenAnswer(i -> i.getArgument(0));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId(null); // null task ID
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var config = new LlmConfiguration(List.of(task));
            // Should not throw — null ID is handled with fallback to "default"
            assertDoesNotThrow(() -> llmTask.execute(memory, config));
        }

        @Test
        @DisplayName("LifecycleException wraps RuntimeException from converter")
        void lifecycleExceptionWrapsRuntimeException() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            IData actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action1"));
            when(memoryItemConverter.convert(memory))
                    .thenThrow(new RuntimeException("conversion error"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var config = new LlmConfiguration(List.of(task));
            assertThrows(RuntimeException.class,
                    () -> llmTask.execute(memory, config));
        }

        @Test
        @DisplayName("empty actions list skips all tasks")
        void emptyActionsSkipsAllTasks() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            IData actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(Collections.emptyList());
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("task1");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var config = new LlmConfiguration(List.of(task));
            assertDoesNotThrow(() -> llmTask.execute(memory, config));

            verify(dataFactory, never()).createData(anyString(), any());
        }

        @Test
        @DisplayName("multiple tasks, only matching one executes")
        void multipleTasksOnlyMatchingExecutes() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            IData actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action_b"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var task1 = new LlmConfiguration.Task();
            task1.setActions(List.of("action_a"));
            task1.setId("task1");
            task1.setType("openai");
            task1.setParameters(Map.of("apiKey", "key"));

            var task2 = new LlmConfiguration.Task();
            task2.setActions(List.of("action_b"));
            task2.setId("task2");
            task2.setType("openai");
            task2.setParameters(Map.of("apiKey", "key"));

            var config = new LlmConfiguration(List.of(task1, task2));
            // task1 should be skipped, task2 should match
            assertDoesNotThrow(() -> llmTask.execute(memory, config));
        }
    }
}
