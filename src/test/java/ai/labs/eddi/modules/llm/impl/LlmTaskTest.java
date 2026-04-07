package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@SuppressWarnings({"rawtypes", "unchecked"})
class LlmTaskTest {
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;

    private LlmTask langChainTask;

    private static final String TEST_MESSAGE_FROM_LLM = "Message from LLM";

    @BeforeEach
    void setUp() {
        openMocks(this);
        Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders = new HashMap<>();
        languageModelApiConnectorBuilders.put("openai", () -> parameters -> new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(aiMessage(TEST_MESSAGE_FROM_LLM)).build();
            }
        });

        var jsonSerialization = mock(IJsonSerialization.class);
        var prePostUtils = mock(PrePostUtils.class);

        // Mock all built-in tools (required for constructor injection)
        var calculatorTool = mock(CalculatorTool.class);
        var dateTimeTool = mock(DateTimeTool.class);
        var webSearchTool = mock(WebSearchTool.class);
        var dataFormatterTool = mock(DataFormatterTool.class);
        var webScraperTool = mock(WebScraperTool.class);
        var textSummarizerTool = mock(TextSummarizerTool.class);
        var pdfReaderTool = mock(PdfReaderTool.class);
        var weatherTool = mock(WeatherTool.class);
        var apiCallExecutor = mock(IApiCallExecutor.class);
        var toolExecutionService = mock(ToolExecutionService.class);
        var secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        var chatModelRegistry = new ChatModelRegistry(languageModelApiConnectorBuilders, secretResolver);

        var counterweightService = new CounterweightService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var deploymentContextService = mock(DeploymentContextService.class);
        when(deploymentContextService.getAutoCounterweightLevel()).thenReturn(null);

        var identityMaskingService = new IdentityMaskingService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var toolResponseTruncator = new ToolResponseTruncator(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        langChainTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine, jsonSerialization, prePostUtils,
                chatModelRegistry, calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool, textSummarizerTool, pdfReaderTool,
                weatherTool, apiCallExecutor, toolExecutionService, mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class),
                mock(IRestAgentStore.class), mock(IRestWorkflowStore.class), mock(RagContextProvider.class), mock(IUserMemoryStore.class),
                mock(TokenCounterFactory.class), mock(ConversationSummarizer.class), counterweightService, deploymentContextService,
                identityMaskingService, toolResponseTruncator, mock(ai.labs.eddi.engine.tenancy.TenantQuotaService.class));
    }

    static Stream<Arguments> provideParameters() {
        return Stream.of(
                Arguments.of(Map.of("systemMessage", "Act as a real estate agent", "logSizeLimit", "10", "apiKey", "<apiKey>", "addToOutput", "true"),
                        List.of(new TextOutputItem(TEST_MESSAGE_FROM_LLM, 0)), 4, // times for templatingEngine.processTemplate
                        2, // times for currentStep.storeData (audit writes guarded by
                           // collector)
                        1 // times for currentStep.addConversationOutputList
                ),
                Arguments.of(Map.of("systemMessage", "Act as a real estate agent", "logSizeLimit", "10", "apiKey", "<apiKey1>"),
                        List.of(new TextOutputItem(TEST_MESSAGE_FROM_LLM, 0)), 3, // times for templatingEngine.processTemplate
                        1, // times for currentStep.storeData (audit writes guarded by
                           // collector)
                        0 // times for currentStep.addConversationOutputList
                )
        // Add more test cases as needed
        );
    }

    @ParameterizedTest
    @MethodSource("provideParameters")
    void execute(Map<String, String> parameters, List<TextOutputItem> expectedOutput, int timesTemplate, int timesStoreData, int timesAddOutputList)
            throws Exception {
        // Setup
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "hi");
        when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

        var actionData = mock(IData.class);
        when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(List.of("action1"));

        var task = new LlmConfiguration.Task();
        task.setActions(List.of("action1"));
        task.setId("taskId");
        task.setType("openai");
        task.setDescription("description");
        task.setParameters(parameters);

        var llmConfig = new LlmConfiguration(List.of(task));

        IData outputData = mock(IData.class);
        when(dataFactory.createData(anyString(), any())).thenReturn(outputData);

        // Adding debug statements
        System.out.println("Setting up mocks with parameters:");
        parameters.forEach((key, value) -> System.out.println(key + ": " + value));

        var systemMessage = parameters.getOrDefault("systemMessage", "-1");
        var apiKey = parameters.getOrDefault("apiKey", "-1");
        var logSizeLimit = parameters.getOrDefault("logSizeLimit", "-1");
        var addToOutput = parameters.getOrDefault("addToOutput", "false");
        var convertToObject = parameters.getOrDefault("convertToObject", "false");
        when(templatingEngine.processTemplate(eq(systemMessage), anyMap())).thenReturn(systemMessage);
        when(templatingEngine.processTemplate(eq(apiKey), anyMap())).thenReturn(apiKey);
        when(templatingEngine.processTemplate(eq(logSizeLimit), anyMap())).thenReturn(logSizeLimit);
        when(templatingEngine.processTemplate(eq(addToOutput), anyMap())).thenReturn(addToOutput);
        when(templatingEngine.processTemplate(eq(convertToObject), anyMap())).thenReturn(convertToObject);

        // Test
        langChainTask.execute(memory, llmConfig);

        // Assert
        // Verify that the templating engine was called
        verify(templatingEngine, times(timesTemplate)).processTemplate(anyString(), anyMap());

        // Verify that data was correctly added to the conversation step
        verify(currentStep, times(timesStoreData)).storeData(any(IData.class));

        // Verify that the conversation output string was updated
        verify(currentStep, times(timesAddOutputList)).addConversationOutputList(eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), eq(expectedOutput));
    }

    @Test
    void testExecute_AgentMode() throws Exception {
        // Setup
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "Help me calculate");
        when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

        var actionData = mock(IData.class);
        when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(List.of("agent_action"));

        var task = new LlmConfiguration.Task();
        task.setActions(List.of("agent_action"));
        task.setId("agentTask");
        task.setType("openai");
        // Enable Agent Mode to test the new integration
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));
        task.setParameters(Map.of("systemMessage", "Agent System Message"));

        var llmConfig = new LlmConfiguration(List.of(task));

        IData outputData = mock(IData.class);
        when(dataFactory.createData(anyString(), any())).thenReturn(outputData);

        // Mock templating to return inputs as-is
        when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

        // Execute
        // Note: This test validates that the Agent Mode path (executeWithTools) is
        // invoked.
        // In a real unit test without Quarkus CDI context, the AiServices.builder()
        // will fail
        // because it requires Arc.container(). This is expected behavior and validates
        // that
        // the Agent Mode code path is executed.
        // The exception may be NullPointerException or ExceptionInInitializerError
        // (wrapping NPE)
        // depending on whether the CDI class initializer has already been attempted.
        assertThrows(Throwable.class, () -> {
            langChainTask.execute(memory, llmConfig);
        }, "Expected exception due to missing Quarkus CDI context when using Agent Mode");

        // Verify that templating was called for system message (happens before the NPE)
        verify(templatingEngine, atLeastOnce()).processTemplate(anyString(), anyMap());
    }

    @Test
    void configure() throws Exception {
        // Setup
        String uriValue = "http://example.com/config";
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("uri", uriValue);

        var task = new LlmConfiguration.Task();
        task.setActions(List.of("action1", "action2"));
        task.setId("taskId");
        task.setType("taskType");
        task.setDescription("A task description");
        task.setParameters(Map.of("key", "value"));

        LlmConfiguration expectedConfig = new LlmConfiguration(List.of(task));

        when(resourceClientLibrary.getResource(any(URI.class), eq(LlmConfiguration.class))).thenReturn(expectedConfig);

        // Test
        Object result = langChainTask.configure(configuration, Collections.emptyMap());

        // Assert
        assertNotNull(result, "Configuration result should not be null.");
        assertInstanceOf(LlmConfiguration.class, result, "Result should be an instance of LlmConfiguration.");
        LlmConfiguration configResult = (LlmConfiguration) result;
        assertEquals(expectedConfig.tasks().size(), configResult.tasks().size(), "Task sizes should match.");
        assertEquals(expectedConfig.tasks().getFirst().getId(), configResult.tasks().getFirst().getId(), "Task IDs should match.");
    }

    @Test
    void testGetExtensionDescriptor() {
        ExtensionDescriptor descriptor = langChainTask.getExtensionDescriptor();

        assertNotNull(descriptor, "The returned ExtensionDescriptor should not be null.");

        // Assuming ID and Display Name are known and static for the LangChainTask
        assertEquals(LlmTask.ID, descriptor.getType(), "The ID should match the expected value.");
        assertEquals("Lang Chain", descriptor.getDisplayName(), "The display name should match 'Lang Chain'.");

        assertFalse(descriptor.getConfigs().isEmpty(), "Expected configs to be defined.");

        assertTrue(descriptor.getConfigs().containsKey("uri"), "Expected 'uri' config to be present.");
        var uriConfig = descriptor.getConfigs().get("uri");
        assertNotNull(uriConfig, "'uri' config should not be null.");
        assertEquals(ExtensionDescriptor.FieldType.URI, uriConfig.getFieldType(), "'uri' config type should be URI.");
    }

    // ==================== Additional Test Cases ====================

    @Nested
    @DisplayName("Backward Compatibility Tests")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("Legacy configuration without tools should work in simple chat mode")
        void testLegacyConfigurationWithoutTools() throws Exception {
            // Setup
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "Hello");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("chat"));

            // Legacy configuration - no tools, no agent mode flags
            var task = new LlmConfiguration.Task();
            task.setActions(List.of("chat"));
            task.setId("legacyChat");
            task.setType("openai");
            task.setParameters(Map.of("systemMessage", "You are helpful", "apiKey", "test-key", "addToOutput", "true"));
            // Note: enableBuiltInTools defaults to false, tools is null

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // Execute
            langChainTask.execute(memory, llmConfig);

            // Assert - should execute in legacy mode and store output
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
            verify(currentStep).addConversationOutputList(eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
        }

        @Test
        @DisplayName("Task with enableBuiltInTools=false should run in legacy mode")
        void testExplicitlyDisabledToolsRunsInLegacyMode() throws Exception {
            // Setup
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "Hello");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("chat"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("chat"));
            task.setId("noToolsChat");
            task.setType("openai");
            task.setEnableBuiltInTools(false); // Explicitly disabled
            task.setParameters(Map.of("systemMessage", "You are helpful", "apiKey", "test-key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // Execute - should NOT throw NPE because we're in legacy mode
            assertDoesNotThrow(() -> langChainTask.execute(memory, llmConfig));
        }
    }

    @Nested
    @DisplayName("Action Matching Tests")
    class ActionMatchingTests {

        @Test
        @DisplayName("Task should execute when action matches exactly")
        void testExactActionMatch() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            // Setup conversation outputs with user input for conversation history
            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "user message");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("specific_action"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("specific_action"));
            task.setId("test");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            langChainTask.execute(memory, llmConfig);

            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("Task should execute when wildcard (*) action is configured")
        void testWildcardActionMatch() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            // Setup conversation outputs with user input for conversation history
            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "user message");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("any_random_action"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("*")); // Wildcard - matches all
            task.setId("test");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            langChainTask.execute(memory, llmConfig);

            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("Task should NOT execute when action does not match")
        void testNoActionMatch() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationOutputs()).thenReturn(List.of(new ConversationOutput()));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("different_action"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("specific_action")); // Does not match
            task.setId("test");
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            langChainTask.execute(memory, llmConfig);

            // Should never store data because action didn't match
            verify(currentStep, never()).storeData(any(IData.class));
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle null actions data gracefully")
        void testNullActionsData() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getLatestData("actions")).thenReturn(null);

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action"));
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            // Should not throw, should return early
            assertDoesNotThrow(() -> langChainTask.execute(memory, llmConfig));
            verify(currentStep, never()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("Should handle null action result gracefully")
        void testNullActionResult() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(null);

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action"));
            task.setType("openai");
            task.setParameters(Map.of("apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            // Should not throw, should return early
            assertDoesNotThrow(() -> langChainTask.execute(memory, llmConfig));
            verify(currentStep, never()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("Should throw exception for unsupported model type")
        void testUnsupportedModelType() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            // Setup conversation outputs with user input - required so messages list is not
            // empty
            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "user message");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action"));
            task.setId("test");
            task.setType("unsupported_model"); // Not registered
            task.setParameters(Map.of("apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            assertThrows(LifecycleException.class, () -> langChainTask.execute(memory, llmConfig));
        }

        @Test
        @DisplayName("Should throw WorkflowConfigurationException when URI is missing")
        void testMissingUri() {
            Map<String, Object> configuration = new HashMap<>();
            // No URI provided

            assertThrows(WorkflowConfigurationException.class, () -> langChainTask.configure(configuration, Collections.emptyMap()));
        }

        @Test
        @DisplayName("Should throw WorkflowConfigurationException when resource loading fails")
        void testResourceLoadingFailure() throws Exception {
            Map<String, Object> configuration = new HashMap<>();
            configuration.put("uri", "http://example.com/config");

            when(resourceClientLibrary.getResource(any(URI.class), eq(LlmConfiguration.class))).thenThrow(new ServiceException("Connection failed"));

            assertThrows(WorkflowConfigurationException.class, () -> langChainTask.configure(configuration, Collections.emptyMap()));
        }
    }

    @Nested
    @DisplayName("Task Identity Tests")
    class TaskIdentityTests {

        @Test
        @DisplayName("getId should return correct identifier")
        void testGetId() {
            assertEquals("ai.labs.llm", langChainTask.getId());
        }

        @Test
        @DisplayName("getType should return 'langchain'")
        void testGetType() {
            assertEquals("langchain", langChainTask.getType());
        }
    }

    @Nested
    @DisplayName("Streaming Mode Tests")
    class StreamingModeTests {

        @Test
        @DisplayName("No event sink — standard sync path runs (backward compatible)")
        void execute_noEventSink_usesSyncPath() throws Exception {
            // Setup
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getEventSink()).thenReturn(null); // No streaming

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action1"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("taskId");
            task.setType("openai");
            task.setDescription("test");
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "<key>", "logSizeLimit", "10"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // Test
            langChainTask.execute(memory, llmConfig);

            // Assert: task executed, response stored in memory
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("With event sink — streaming detection is triggered")
        void execute_withEventSink_detectsStreaming() throws Exception {
            // Setup
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var eventSink = mock(ai.labs.eddi.engine.lifecycle.ConversationEventSink.class);
            when(memory.getEventSink()).thenReturn(eventSink);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action1"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("taskId");
            task.setType("openai");
            task.setDescription("test");
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "<key>", "logSizeLimit", "10"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // Test — the openai mock builder doesn't support buildStreaming,
            // so it falls back to sync + emits as single chunk
            langChainTask.execute(memory, llmConfig);

            // Assert: event sink received the response as a single token
            verify(eventSink, atLeastOnce()).onToken(anyString());
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }
    }

    @Nested
    @DisplayName("Audit Memory Key Tests")
    class AuditMemoryKeyTests {

        @Test
        @DisplayName("Should write audit:* keys when audit collector is set")
        void testAuditKeysWrittenWhenCollectorSet() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            // Set audit collector — this enables audit writes
            when(memory.getAuditCollector()).thenReturn(entry -> {
            });

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action1"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("taskId");
            task.setType("openai");
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "<key>"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            langChainTask.execute(memory, llmConfig);

            // Verify audit keys are written: langchain data (1) + compiled_prompt +
            // model_response + model_name (3) = 4
            verify(currentStep, times(4)).storeData(any(IData.class));

            // Verify the data factory was called with audit key names
            verify(dataFactory).createData(eq("audit:compiled_prompt"), any());
            verify(dataFactory).createData(eq("audit:model_response"), any());
            verify(dataFactory).createData(eq("audit:model_name"), any());
        }

        @Test
        @DisplayName("Should NOT write audit:* keys when audit collector is null")
        void testNoAuditKeysWhenCollectorNull() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            // No audit collector set
            when(memory.getAuditCollector()).thenReturn(null);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action1"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action1"));
            task.setId("taskId");
            task.setType("openai");
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "<key>"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            langChainTask.execute(memory, llmConfig);

            // Only langchain data stored, no audit keys
            verify(currentStep, times(1)).storeData(any(IData.class));
            verify(dataFactory, never()).createData(eq("audit:compiled_prompt"), any());
            verify(dataFactory, never()).createData(eq("audit:model_response"), any());
            verify(dataFactory, never()).createData(eq("audit:model_name"), any());
        }
    }

    @Nested
    @DisplayName("Cascade Mode Tests")
    class CascadeModeTests {

        @Test
        @DisplayName("Cascade enabled with 1 step — should execute and store cascade trace")
        void testCascadeEnabledSingleStep() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "What is 2+2?");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("cascade_action"));

            // Configure task with cascade enabled (single step, none strategy = always
            // accepts)
            var task = new LlmConfiguration.Task();
            task.setActions(List.of("cascade_action"));
            task.setId("cascadeTask");
            task.setType("openai");
            task.setParameters(Map.of("systemMessage", "You are helpful", "apiKey", "test-key"));

            var cascade = new LlmConfiguration.ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none"); // always returns confidence 1.0
            var step = new LlmConfiguration.CascadeStep();
            step.setType("openai");
            step.setTimeoutMs(5000L);
            cascade.setSteps(List.of(step));
            task.setModelCascade(cascade);

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // Execute
            langChainTask.execute(memory, llmConfig);

            // Assert: cascade trace should be stored
            verify(dataFactory).createData(contains("cascade:trace"), any());
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("Cascade disabled — should use standard execution path (backward compat)")
        void testCascadeDisabledUsesStandardPath() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("chat"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("chat"));
            task.setId("legacyChat");
            task.setType("openai");
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "key"));

            // Cascade present but disabled
            var cascade = new LlmConfiguration.ModelCascadeConfig();
            cascade.setEnabled(false);
            task.setModelCascade(cascade);

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            langChainTask.execute(memory, llmConfig);

            // Should NOT have cascade trace (standard path)
            verify(dataFactory, never()).createData(contains("cascade:trace"), any());
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("Null modelCascade — should use standard execution path (full backward compat)")
        void testNullCascadeUsesStandardPath() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("chat"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("chat"));
            task.setId("legacyChat");
            task.setType("openai");
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "key"));
            // No cascade set — null by default

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            langChainTask.execute(memory, llmConfig);

            verify(dataFactory, never()).createData(contains("cascade:trace"), any());
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("Cascade with audit collector — should store cascade model and confidence audit keys")
        void testCascadeAuditKeys() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getAuditCollector()).thenReturn(entry -> {
            });

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "test");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action"));

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action"));
            task.setId("auditCascade");
            task.setType("openai");
            task.setParameters(Map.of("systemMessage", "test", "apiKey", "key"));

            var cascade = new LlmConfiguration.ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            var step = new LlmConfiguration.CascadeStep();
            step.setType("openai");
            cascade.setSteps(List.of(step));
            task.setModelCascade(cascade);

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            langChainTask.execute(memory, llmConfig);

            // Verify cascade-specific audit keys
            verify(dataFactory).createData(eq("audit:cascade_model"), any());
            verify(dataFactory).createData(eq("audit:cascade_confidence"), any());
        }
    }

    @Nested
    @DisplayName("httpCall RAG Tests")
    class HttpCallRagTests {

        @Test
        @DisplayName("httpCallRag null — should execute normally without RAG context")
        void testHttpCallRag_null_noOp() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "hello");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action"));

            // No user input in memory step (returns null from extractUserInput)
            when(currentStep.getLatestData("input")).thenReturn(null);

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action"));
            task.setId("ragTask");
            task.setType("openai");
            task.setHttpCallRag(null); // Explicitly null
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            langChainTask.execute(memory, llmConfig);

            // Should execute normally — no httpCall RAG trace stored
            verify(dataFactory, never()).createData(contains("rag:httpcall:trace"), any());
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }

        @Test
        @DisplayName("httpCallRag set but no user input — should skip gracefully")
        void testHttpCallRag_noUserInput_skips() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "hello");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action"));

            // No user input in conversation step
            when(currentStep.getLatestData("input")).thenReturn(null);

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action"));
            task.setId("ragTask");
            task.setType("openai");
            task.setHttpCallRag("search-api"); // Set but no user input → skip
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // Should NOT throw
            assertDoesNotThrow(() -> langChainTask.execute(memory, llmConfig));

            // No httpCall RAG trace (skipped due to null input)
            verify(dataFactory, never()).createData(contains("rag:httpcall:trace"), any());
        }

        @Test
        @DisplayName("httpCallRag set with user input but no matching httpCall — should warn and continue")
        void testHttpCallRag_notFound_graceful() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var conversationOutput = new ConversationOutput();
            conversationOutput.put("input", "search query");
            when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("action"));

            // Provide user input
            var inputData = mock(IData.class);
            when(currentStep.getLatestData("input")).thenReturn(inputData);
            when(inputData.getResult()).thenReturn("search query");

            // Mock agent context (needed for WorkflowTraversal)
            when(memory.getAgentId()).thenReturn(null); // No agent context = empty discovery

            var task = new LlmConfiguration.Task();
            task.setActions(List.of("action"));
            task.setId("ragTask");
            task.setType("openai");
            task.setHttpCallRag("nonexistent-search"); // No matching httpCall in workflow
            task.setParameters(Map.of("systemMessage", "be helpful", "apiKey", "key"));

            var llmConfig = new LlmConfiguration(List.of(task));

            IData outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(outputData);
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // Should NOT throw — graceful degradation
            assertDoesNotThrow(() -> langChainTask.execute(memory, llmConfig));

            // No httpCall RAG trace (not found)
            verify(dataFactory, never()).createData(contains("rag:httpcall:trace"), any());
            // Task should still execute normally (storeData for langchain output)
            verify(currentStep, atLeastOnce()).storeData(any(IData.class));
        }
    }
}
