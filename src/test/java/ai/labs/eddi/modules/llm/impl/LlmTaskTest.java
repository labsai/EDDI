/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LlmTask}. Covers getId, getType, execute early-return
 * paths, configure scenarios, getExtensionDescriptor, and private helper
 * methods via reflection.
 */
class LlmTaskTest {

    // === Constructor dependencies ===
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
    @Mock
    private ChatModelRegistry chatModelRegistry;
    @Mock
    private IApiCallExecutor apiCallExecutor;
    @Mock
    private IRestAgentStore restAgentStore;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
    @Mock
    private RagContextProvider ragContextProvider;
    @Mock
    private TokenCounterFactory tokenCounterFactory;
    @Mock
    private ConversationSummarizer conversationSummarizer;
    @Mock
    private PromptSnippetService promptSnippetService;
    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private CounterweightService counterweightService;
    @Mock
    private IdentityMaskingService identityMaskingService;

    // === Memory mocks ===
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private LlmTask llmTask;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        llmTask = new LlmTask(
                resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                apiCallExecutor, restAgentStore, restWorkflowStore,
                ragContextProvider, tokenCounterFactory,
                conversationSummarizer,
                promptSnippetService,
                globalVariableResolver,
                counterweightService,
                identityMaskingService,
                mock(AgentOrchestrator.class), new ConversationHistoryBuilder(),
                new SimpleMeterRegistry());
    }

    // ====================================================================
    // 1. getId()
    // ====================================================================

    @Test
    void getId_returnsCorrectTaskId() {
        TaskId id = llmTask.getId();
        assertEquals(new TaskId("ai.labs.llm"), id);
    }

    // ====================================================================
    // 2. getType()
    // ====================================================================

    @Test
    void getType_returnsLangchain() {
        assertEquals("langchain", llmTask.getType());
    }

    // ====================================================================
    // 3. execute — null latestData (actions) → returns early
    // ====================================================================

    @Test
    void execute_nullLatestData_returnsEarlyNoException() throws Exception {
        doReturn(currentStep).when(memory).getCurrentStep();
        doReturn(null).when(currentStep).getLatestData(MemoryKeys.ACTIONS);

        LlmConfiguration config = new LlmConfiguration(List.of());

        // Should return without error
        assertDoesNotThrow(() -> llmTask.execute(memory, config));

        // memoryItemConverter should never be called because we returned early
        verify(memoryItemConverter, never()).convert(any());
    }

    // ====================================================================
    // 4. execute — null actions result → returns early
    // ====================================================================

    @SuppressWarnings("unchecked")
    @Test
    void execute_nullActionsResult_returnsEarly() throws Exception {
        doReturn(currentStep).when(memory).getCurrentStep();

        IData<List<String>> actionsData = mock(IData.class);
        doReturn(actionsData).when(currentStep).getLatestData(MemoryKeys.ACTIONS);
        doReturn(null).when(actionsData).getResult();

        // memoryItemConverter.convert will be called before the null-check on actions
        // result
        doReturn(new HashMap<>()).when(memoryItemConverter).convert(any());
        doReturn(Map.of()).when(promptSnippetService).getAll();
        doReturn(Map.of()).when(globalVariableResolver).getTemplateData();

        LlmConfiguration config = new LlmConfiguration(List.of());

        assertDoesNotThrow(() -> llmTask.execute(memory, config));

        // No task should have been processed — verify no data stored
        verify(currentStep, never()).storeData(any());
    }

    // ====================================================================
    // 5. execute — actions don't match any task → no task output stored
    // ====================================================================

    @SuppressWarnings("unchecked")
    @Test
    void execute_actionsDoNotMatchAnyTask_noOutputStored() throws Exception {
        doReturn(currentStep).when(memory).getCurrentStep();

        IData<List<String>> actionsData = mock(IData.class);
        doReturn(actionsData).when(currentStep).getLatestData(MemoryKeys.ACTIONS);
        doReturn(List.of("someAction")).when(actionsData).getResult();

        doReturn(new HashMap<>()).when(memoryItemConverter).convert(any());
        doReturn(Map.of()).when(promptSnippetService).getAll();
        doReturn(Map.of()).when(globalVariableResolver).getTemplateData();

        // Task that only matches "otherAction"
        Task task = new Task();
        task.setActions(List.of("otherAction"));
        task.setId("test");
        task.setType("openai");
        task.setParameters(Map.of("systemMessage", "hello"));

        LlmConfiguration config = new LlmConfiguration(List.of(task));

        assertDoesNotThrow(() -> llmTask.execute(memory, config));

        // No storeData should be called because no task matched
        verify(currentStep, never()).storeData(any());
    }

    // ====================================================================
    // 6. execute — actions match via wildcard "*" → task executed
    // (tests up to the point of model invocation, which would need
    // a full ChatModel mock chain)
    // ====================================================================

    @SuppressWarnings("unchecked")
    @Test
    void execute_wildcardMatchTriggersTask() throws Exception {
        doReturn(currentStep).when(memory).getCurrentStep();

        IData<List<String>> actionsData = mock(IData.class);
        doReturn(actionsData).when(currentStep).getLatestData(MemoryKeys.ACTIONS);
        doReturn(List.of("anyAction")).when(actionsData).getResult();

        Map<String, Object> templateData = new HashMap<>();
        doReturn(templateData).when(memoryItemConverter).convert(any());
        doReturn(Map.of()).when(promptSnippetService).getAll();
        doReturn(Map.of()).when(globalVariableResolver).getTemplateData();

        // Return input string unchanged for template processing
        doReturn("hello").when(templatingEngine).processTemplate(anyString(), any());

        // Identity masking + counterweight pass-through
        doReturn("hello").when(identityMaskingService).apply(anyString(), any());
        doReturn("hello").when(counterweightService).apply(anyString(), any(), any());

        // Global variable resolver for task type
        doReturn("openai").when(globalVariableResolver).resolveValue(anyString());

        // Task with wildcard match
        Task task = new Task();
        task.setActions(List.of("*"));
        task.setId("test");
        task.setType("openai");
        task.setParameters(Map.of("systemMessage", "hello"));

        LlmConfiguration config = new LlmConfiguration(List.of(task));

        // chatModelRegistry.getOrCreate will be called — let it throw to stop execution
        // at a known point without needing to mock the entire LLM call chain
        doThrow(new ChatModelRegistry.UnsupportedLlmTaskException("test-stop"))
                .when(chatModelRegistry).getOrCreate(anyString(), any());

        LifecycleException ex = assertThrows(LifecycleException.class,
                () -> llmTask.execute(memory, config));

        // Verify the exception was thrown from chatModelRegistry, confirming
        // the task was matched and execution proceeded past action matching
        assertTrue(ex.getMessage().contains("test-stop"));
    }

    // ====================================================================
    // 7. configure — null/empty URI throws WorkflowConfigurationException
    // ====================================================================

    @Test
    void configure_nullUri_throwsWorkflowConfigurationException() {
        Map<String, Object> configuration = new HashMap<>();
        // no "uri" key at all

        WorkflowConfigurationException ex = assertThrows(
                WorkflowConfigurationException.class,
                () -> llmTask.configure(configuration, Map.of()));

        assertTrue(ex.getMessage().contains("No resource URI has been defined"));
    }

    @Test
    void configure_emptyUri_throwsWorkflowConfigurationException() {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("uri", "");

        WorkflowConfigurationException ex = assertThrows(
                WorkflowConfigurationException.class,
                () -> llmTask.configure(configuration, Map.of()));

        assertTrue(ex.getMessage().contains("No resource URI has been defined"));
    }

    // ====================================================================
    // 8. configure — valid URI with ServiceException → wraps
    // ====================================================================

    @Test
    void configure_validUriWithServiceException_wrapsInWorkflowConfigurationException() throws Exception {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("uri", "eddi://ai.labs.llm/llmstore/llms/abc123?version=1");

        doThrow(new ServiceException("resource not found"))
                .when(resourceClientLibrary).getResource(any(URI.class), eq(LlmConfiguration.class));

        WorkflowConfigurationException ex = assertThrows(
                WorkflowConfigurationException.class,
                () -> llmTask.configure(configuration, Map.of()));

        assertTrue(ex.getMessage().contains("resource not found"));
        assertNotNull(ex.getCause());
        assertInstanceOf(ServiceException.class, ex.getCause());
    }

    // ====================================================================
    // 9. configure — valid URI → loads and returns LlmConfiguration
    // ====================================================================

    @Test
    void configure_validUri_loadsAndReturnsConfig() throws Exception {
        Task task = new Task();
        task.setActions(List.of("help"));
        task.setType("openai");
        task.setParameters(Map.of("systemMessage", "You are helpful"));

        LlmConfiguration expectedConfig = new LlmConfiguration(List.of(task));

        Map<String, Object> configuration = new HashMap<>();
        configuration.put("uri", "eddi://ai.labs.llm/llmstore/llms/abc123?version=1");

        doReturn(expectedConfig)
                .when(resourceClientLibrary).getResource(any(URI.class), eq(LlmConfiguration.class));

        Object result = llmTask.configure(configuration, Map.of());

        assertSame(expectedConfig, result);
        verify(resourceClientLibrary).getResource(any(URI.class), eq(LlmConfiguration.class));
    }

    // ====================================================================
    // 10. getExtensionDescriptor — displayName and configs
    // ====================================================================

    @Test
    void getExtensionDescriptor_returnsCorrectDisplayNameAndConfigs() {
        ExtensionDescriptor descriptor = llmTask.getExtensionDescriptor();

        assertNotNull(descriptor);
        assertEquals("Lang Chain", descriptor.getDisplayName());
        assertNotNull(descriptor.getConfigs());
        assertTrue(descriptor.getConfigs().containsKey("uri"));

        ExtensionDescriptor.ConfigValue uriConfig = descriptor.getConfigs().get("uri");
        assertNotNull(uriConfig);
        assertEquals("Resource URI", uriConfig.getDisplayName());
        assertEquals(ExtensionDescriptor.FieldType.URI, uriConfig.getFieldType());
    }

    // ====================================================================
    // 11. resolveModelName via reflection
    // ====================================================================

    @Test
    void resolveModelName_modelNameWins() throws Exception {
        Method method = LlmTask.class.getDeclaredMethod("resolveModelName", Map.class);
        method.setAccessible(true);

        Map<String, String> params = new HashMap<>();
        params.put("modelName", "gpt-4");
        params.put("model", "gpt-3.5");
        params.put("modelId", "some-id");
        params.put("deploymentName", "some-deploy");

        String result = (String) method.invoke(null, params);
        assertEquals("gpt-4", result);
    }

    @Test
    void resolveModelName_modelFallback() throws Exception {
        Method method = LlmTask.class.getDeclaredMethod("resolveModelName", Map.class);
        method.setAccessible(true);

        Map<String, String> params = new HashMap<>();
        params.put("model", "gpt-3.5");
        params.put("modelId", "some-id");
        params.put("deploymentName", "some-deploy");

        String result = (String) method.invoke(null, params);
        assertEquals("gpt-3.5", result);
    }

    @Test
    void resolveModelName_modelIdFallback() throws Exception {
        Method method = LlmTask.class.getDeclaredMethod("resolveModelName", Map.class);
        method.setAccessible(true);

        Map<String, String> params = new HashMap<>();
        params.put("modelId", "anthropic-id");
        params.put("deploymentName", "some-deploy");

        String result = (String) method.invoke(null, params);
        assertEquals("anthropic-id", result);
    }

    @Test
    void resolveModelName_deploymentNameFallback() throws Exception {
        Method method = LlmTask.class.getDeclaredMethod("resolveModelName", Map.class);
        method.setAccessible(true);

        Map<String, String> params = new HashMap<>();
        params.put("deploymentName", "azure-deploy");

        String result = (String) method.invoke(null, params);
        assertEquals("azure-deploy", result);
    }

    @Test
    void resolveModelName_allNull_returnsNull() throws Exception {
        Method method = LlmTask.class.getDeclaredMethod("resolveModelName", Map.class);
        method.setAccessible(true);

        Map<String, String> params = new HashMap<>();

        String result = (String) method.invoke(null, params);
        assertNull(result);
    }

    // ====================================================================
    // 12. extractUserInput via reflection
    // ====================================================================

    @SuppressWarnings("unchecked")
    @Test
    void extractUserInput_nullData_returnsNull() throws Exception {
        Method method = LlmTask.class.getDeclaredMethod("extractUserInput", IConversationMemory.class);
        method.setAccessible(true);

        doReturn(currentStep).when(memory).getCurrentStep();
        doReturn(null).when(currentStep).getLatestData("input");

        String result = (String) method.invoke(llmTask, memory);
        assertNull(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void extractUserInput_validData_returnsString() throws Exception {
        Method method = LlmTask.class.getDeclaredMethod("extractUserInput", IConversationMemory.class);
        method.setAccessible(true);

        doReturn(currentStep).when(memory).getCurrentStep();

        IData<String> inputData = mock(IData.class);
        doReturn(inputData).when(currentStep).getLatestData("input");
        doReturn("Hello world").when(inputData).getResult();

        String result = (String) method.invoke(llmTask, memory);
        assertEquals("Hello world", result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void extractUserInput_dataWithNullResult_returnsNull() throws Exception {
        Method method = LlmTask.class.getDeclaredMethod("extractUserInput", IConversationMemory.class);
        method.setAccessible(true);

        doReturn(currentStep).when(memory).getCurrentStep();

        IData<String> inputData = mock(IData.class);
        doReturn(inputData).when(currentStep).getLatestData("input");
        doReturn(null).when(inputData).getResult();

        String result = (String) method.invoke(llmTask, memory);
        assertNull(result);
    }
}
