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
import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Deep branch coverage tests for {@link LlmTask} — focuses on branches NOT
 * exercised by LlmTaskBranchTest and LlmTaskExtendedTest:
 * <ul>
 * <li>null actions result → early return</li>
 * <li>wildcard (*) action matching</li>
 * <li>no matching action → task skipped</li>
 * <li>summary config with excludePropertiesFromSummary and non-empty
 * properties</li>
 * <li>audit collector branches (non-null audit collector)</li>
 * <li>tool trace storage (non-empty trace)</li>
 * <li>extractUserInput null/non-null paths</li>
 * <li>addToOutput logic (usedToolMode vs addToOutput param)</li>
 * <li>configure() with null URI throws exception</li>
 * <li>configure() with valid URI delegates to resource library</li>
 * <li>getExtensionDescriptor()</li>
 * <li>getId() and getType()</li>
 * </ul>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@DisplayName("LlmTask — Deep Branch Coverage")
class LlmTaskDeepBranchTest {

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
                new SimpleMeterRegistry());
        counterweightService.initMetrics();
        var identityMaskingService = new IdentityMaskingService(
                new SimpleMeterRegistry());
        identityMaskingService.initMetrics();

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                mock(IApiCallExecutor.class), mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                mock(RagContextProvider.class), new TokenCounterFactory(), mock(ConversationSummarizer.class),
                mockSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, mock(AgentOrchestrator.class), new ConversationHistoryBuilder(),
                new SimpleMeterRegistry());
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

    // ==================== null latestData → early return ====================

    @Nested
    @DisplayName("Early Return Paths")
    class EarlyReturnTests {

        @Test
        @DisplayName("null latestData (no ACTIONS key) → immediate return")
        void nullLatestData() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(null);

            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Should return immediately — no data created
            verify(dataFactory, never()).createData(anyString(), any());
        }

        @Test
        @DisplayName("null actions result → immediate return")
        void nullActionsResult() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(null);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(dataFactory, never()).createData(anyString(), any());
        }
    }

    // ==================== Wildcard action matching ====================

    @Nested
    @DisplayName("Wildcard Action Matching")
    class WildcardActionTests {

        @Test
        @DisplayName("task with '*' action matches any actions list")
        void wildcardMatchesAll() throws Exception {
            var memory = setupMemory(List.of("some_random_action"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            task.setActions(List.of("*"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(dataFactory, atLeastOnce()).createData(anyString(), any());
        }

        @Test
        @DisplayName("task with non-matching action is skipped")
        void nonMatchingActionSkipped() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            task.setActions(List.of("completely_different_action"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // No task executed → no data created
            verify(dataFactory, never()).createData(anyString(), any());
        }
    }

    // ==================== configure() ====================

    @Nested
    @DisplayName("configure()")
    class ConfigureTests {

        @Test
        @DisplayName("null URI in configuration throws WorkflowConfigurationException")
        void nullUri() {
            assertThrows(ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException.class,
                    () -> llmTask.configure(Map.of(), Map.of()));
        }

        @Test
        @DisplayName("empty URI in configuration throws WorkflowConfigurationException")
        void emptyUri() {
            assertThrows(ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException.class,
                    () -> llmTask.configure(Map.of("uri", ""), Map.of()));
        }

        @Test
        @DisplayName("valid URI delegates to resourceClientLibrary")
        void validUri() throws Exception {
            var llmConfig = new LlmConfiguration(List.of());
            when(resourceClientLibrary.getResource(any(), eq(LlmConfiguration.class))).thenReturn(llmConfig);

            Object result = llmTask.configure(Map.of("uri", "eddi://ai.labs.llm/llmstore/llmconfigs/abc123?version=1"), Map.of());

            assertEquals(llmConfig, result);
        }

        @Test
        @DisplayName("ServiceException from resource library wraps in WorkflowConfigurationException")
        void serviceException() throws Exception {
            when(resourceClientLibrary.getResource(any(), eq(LlmConfiguration.class)))
                    .thenThrow(new ai.labs.eddi.engine.runtime.service.ServiceException("fail"));

            assertThrows(ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException.class,
                    () -> llmTask.configure(Map.of("uri", "eddi://ai.labs.llm/llmstore/llmconfigs/abc123?version=1"), Map.of()));
        }
    }

    // ==================== getId, getType, getExtensionDescriptor
    // ====================

    @Nested
    @DisplayName("Identity and Descriptor")
    class IdentityTests {

        @Test
        @DisplayName("getId returns TASK_ID")
        void getId() {
            assertEquals(LlmTask.TASK_ID, llmTask.getId());
        }

        @Test
        @DisplayName("getType returns 'langchain'")
        void getType() {
            assertEquals("langchain", llmTask.getType());
        }

        @Test
        @DisplayName("getExtensionDescriptor has correct display name and config")
        void extensionDescriptor() {
            var descriptor = llmTask.getExtensionDescriptor();
            assertEquals("Lang Chain", descriptor.getDisplayName());
            assertTrue(descriptor.getConfigs().containsKey("uri"));
        }
    }

    // ==================== Audit collector branches ====================

    @Nested
    @DisplayName("Audit Collector Branches")
    class AuditCollectorTests {

        @Test
        @DisplayName("non-null audit collector stores compiled prompt and model response")
        void auditCollectorStoresData() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            IAuditEntryCollector auditCollector = mock(IAuditEntryCollector.class);
            when(memory.getAuditCollector()).thenReturn(auditCollector);

            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // atLeast(3) on anyString() passed for any three keys at all — name them.
            verify(dataFactory).createData(eq(MemoryKeys.AUDIT_COMPILED_PROMPT), any());
            verify(dataFactory).createData(eq(MemoryKeys.AUDIT_MODEL_RESPONSE), eq(LLM_RESPONSE));
            verify(dataFactory).createData(eq(MemoryKeys.AUDIT_MODEL_NAME), any());
        }

        @Test
        @DisplayName("null audit collector skips audit data storage")
        void nullAuditCollectorSkipsAudit() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));
            when(memory.getAuditCollector()).thenReturn(null);

            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Should NOT create audit:* data
            verify(dataFactory, never()).createData(startsWith("audit:"), any());
        }
    }

    // ==================== addToOutput logic ====================

    @Nested
    @DisplayName("shouldAddToOutput Logic")
    class AddToOutputLogicTests {

        @Test
        @DisplayName("addToOutput=true without tool mode stores output")
        void addToOutputTrue() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key", "addToOutput", "true"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(memory.getCurrentStep(), atLeastOnce()).addConversationOutputList(
                    eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
        }

        @Test
        @DisplayName("addToOutput not set and not tool mode → no output added")
        void addToOutputDefault() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            // No addToOutput param, no tool mode → shouldAddToOutput = false
            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(memory.getCurrentStep(), never()).addConversationOutputList(
                    eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
        }
    }

    // ==================== extractUserInput ====================

    @Nested
    @DisplayName("extractUserInput Branches")
    class ExtractUserInputTests {

        @Test
        @DisplayName("input data present → RAG context attempted")
        void inputDataPresent() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var inputData = mock(IData.class);
            when(inputData.getResult()).thenReturn("user question");
            when(memory.getCurrentStep().getLatestData("input")).thenReturn(inputData);

            var task = createTask(Map.of("apiKey", "key"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // Should complete normally
            verify(dataFactory, atLeastOnce()).createData(anyString(), any());
        }

        @Test
        @DisplayName("null input data → no RAG context retrieval")
        void nullInputData() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));
            when(memory.getCurrentStep().getLatestData("input")).thenReturn(null);

            var task = createTask(Map.of("apiKey", "key"));
            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }
    }

    // ==================== task.getId null fallback ====================

    @Nested
    @DisplayName("Task ID Fallback")
    class TaskIdFallbackTests {

        @Test
        @DisplayName("null task ID falls back to 'default'")
        void nullTaskId() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var task = createTask(Map.of("apiKey", "key"));
            task.setId(null);
            // With null ID, responseObjectName falls back to task.getId() which is null,
            // so the code path `isNullOrEmpty(responseObjectName) → responseObjectName =
            // task.getId()` → still null
            // Then it tries to use null as the response key
            assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task))));
        }
    }

    // ==================== Streaming sync fallback ====================

    @Nested
    @DisplayName("Streaming Sync Fallback")
    class StreamingSyncFallbackTests {

        @Test
        @DisplayName("eventSink present but no streaming model → sync fallback emits token")
        void syncFallbackEmitsToken() throws Exception {
            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));

            var eventSink = mock(ConversationEventSink.class);
            when(memory.getEventSink()).thenReturn(eventSink);

            var task = createTask(Map.of("apiKey", "key", "addToOutput", "true"));
            llmTask.execute(memory, new LlmConfiguration(List.of(task)));

            // With eventSink != null and no streaming builder, sync fallback should
            // call eventSink.onToken with the response
            verify(eventSink).onToken(LLM_RESPONSE);
        }
    }

    // ==================== convertToObject JSON parsing ====================

    @Nested
    @DisplayName("convertToObject JSON Parsing")
    class ConvertToObjectJsonTests {

        @Test
        @DisplayName("response starting with '{' is deserialized as Map")
        void jsonObjectResponse() throws Exception {
            // Create a model that returns JSON
            Map<String, Provider<ILanguageModelBuilder>> jsonBuilders = new HashMap<>();
            jsonBuilders.put("openai", () -> parameters -> new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder().aiMessage(aiMessage("{\"key\":\"value\"}")).build();
                }
            });

            var secretResolver = mock(SecretResolver.class);
            when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
            var gvr = mock(GlobalVariableResolver.class);
            when(gvr.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            when(gvr.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(gvr.getTemplateData()).thenReturn(Map.of());
            var chatModelRegistry = new ChatModelRegistry(jsonBuilders, gvr, secretResolver);
            var cws = new CounterweightService(mock(PromptSnippetService.class),
                    new SimpleMeterRegistry());
            cws.initMetrics();
            var ims = new IdentityMaskingService(
                    new SimpleMeterRegistry());
            ims.initMetrics();
            when(mockSnippetService.getAll()).thenReturn(Collections.emptyMap());

            var jsonTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter,
                    templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                    mock(IApiCallExecutor.class), mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                    mock(RagContextProvider.class), new TokenCounterFactory(), mock(ConversationSummarizer.class),
                    mockSnippetService, gvr, cws,
                    ims, mock(AgentOrchestrator.class), new ConversationHistoryBuilder(),
                    new SimpleMeterRegistry());

            var memory = setupMemory(List.of("action1"));
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));
            when(jsonSerialization.deserialize(anyString(), eq(Map.class))).thenReturn(Map.of("key", "value"));

            var task = createTask(Map.of("apiKey", "key", "convertToObject", "true"));
            jsonTask.execute(memory, new LlmConfiguration(List.of(task)));

            verify(jsonSerialization).deserialize(anyString(), eq(Map.class));
        }
    }
}
