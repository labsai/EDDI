package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("McpCallsTask Tests")
class McpCallsTaskTest {

    private IResourceClientLibrary resourceClientLibrary;
    private IMemoryItemConverter memoryItemConverter;
    private IJsonSerialization jsonSerialization;
    private McpToolProviderManager mcpToolProviderManager;
    private PrePostUtils prePostUtils;
    private McpCallsTask task;

    @BeforeEach
    void setUp() {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        jsonSerialization = mock(IJsonSerialization.class);
        mcpToolProviderManager = mock(McpToolProviderManager.class);
        prePostUtils = mock(PrePostUtils.class);

        task = new McpCallsTask(resourceClientLibrary, memoryItemConverter,
                jsonSerialization, mcpToolProviderManager, prePostUtils);
    }

    @Test
    @DisplayName("getId returns correct ID")
    void getId() {
        assertEquals("ai.labs.mcpcalls", task.getId());
    }

    @Test
    @DisplayName("getType returns 'mcpCalls'")
    void getType() {
        assertEquals("mcpCalls", task.getType());
    }

    @Test
    @DisplayName("getExtensionDescriptor returns valid descriptor")
    void getExtensionDescriptor() {
        var descriptor = task.getExtensionDescriptor();
        assertNotNull(descriptor);
        assertEquals("MCP Calls", descriptor.getDisplayName());
        assertTrue(descriptor.getConfigs().containsKey("uri"));
    }

    @Nested
    @DisplayName("execute Tests")
    class ExecuteTests {

        @Test
        @DisplayName("no actions data — returns early")
        void noActions() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getLatestData("actions")).thenReturn(null);

            var config = new McpCallsConfiguration();

            assertDoesNotThrow(() -> task.execute(memory, config));
            verify(mcpToolProviderManager, never()).discoverTools(anyList());
        }

        @Test
        @DisplayName("actions but no mcpCalls configured — returns early")
        void noMcpCalls() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("some_action"));

            var config = new McpCallsConfiguration();
            config.setMcpCalls(List.of()); // empty

            assertDoesNotThrow(() -> task.execute(memory, config));
            verify(mcpToolProviderManager, never()).discoverTools(anyList());
        }

        @Test
        @DisplayName("no tools discovered — logs warning and returns")
        void noToolsDiscovered() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("call_tool"));

            var mcpCall = new McpCall();
            mcpCall.setActions(List.of("call_tool"));
            mcpCall.setToolName("test_tool");

            var config = new McpCallsConfiguration();
            config.setMcpCalls(List.of(mcpCall));
            config.setMcpServerUrl("http://localhost:8080/mcp");

            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(null);

            assertDoesNotThrow(() -> task.execute(memory, config));
        }

        @Test
        @DisplayName("action matches and tool found — executes tool")
        void actionMatchesAndToolFound() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("call_weather"));

            var mcpCall = new McpCall();
            mcpCall.setActions(List.of("call_weather"));
            mcpCall.setToolName("get_weather");
            mcpCall.setName("weather_call");

            var config = new McpCallsConfiguration();
            config.setMcpCalls(List.of(mcpCall));
            config.setMcpServerUrl("http://localhost:8080/mcp");

            var toolSpec = ToolSpecification.builder().name("get_weather").description("Get weather").build();
            var executor = mock(ToolExecutor.class);
            when(executor.execute(any(), any())).thenReturn("{\"temp\": 72}");

            var mcpTools = new McpToolProviderManager.McpToolsResult(
                    List.of(toolSpec), Map.of("get_weather", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpTools);

            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> task.execute(memory, config));
            verify(executor).execute(any(), any());
        }

        @Test
        @DisplayName("wildcard action — matches all")
        void wildcardAction() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("any_action"));

            var mcpCall = new McpCall();
            mcpCall.setActions(List.of("*")); // wildcard
            mcpCall.setToolName("universal_tool");

            var config = new McpCallsConfiguration();
            config.setMcpCalls(List.of(mcpCall));
            config.setMcpServerUrl("http://localhost:8080/mcp");

            var toolSpec = ToolSpecification.builder().name("universal_tool").description("Universal").build();
            var executor = mock(ToolExecutor.class);
            when(executor.execute(any(), any())).thenReturn("ok");

            var mcpTools = new McpToolProviderManager.McpToolsResult(
                    List.of(toolSpec), Map.of("universal_tool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpTools);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> task.execute(memory, config));
            verify(executor).execute(any(), any());
        }

        @Test
        @DisplayName("tool blocked by blacklist — should not execute")
        void toolBlacklisted() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("call_dangerous"));

            var mcpCall = new McpCall();
            mcpCall.setActions(List.of("call_dangerous"));
            mcpCall.setToolName("dangerous_tool");

            var config = new McpCallsConfiguration();
            config.setMcpCalls(List.of(mcpCall));
            config.setMcpServerUrl("http://localhost:8080/mcp");
            config.setToolsBlacklist(List.of("dangerous_tool")); // blocked

            var toolSpec = ToolSpecification.builder().name("dangerous_tool").description("Dangerous").build();
            var executor = mock(ToolExecutor.class);

            var mcpTools = new McpToolProviderManager.McpToolsResult(
                    List.of(toolSpec), Map.of("dangerous_tool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpTools);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> task.execute(memory, config));
            verify(executor, never()).execute(any(), any());
        }

        @Test
        @DisplayName("action doesn't match — tool not executed")
        void actionNoMatch() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("greet"));

            var mcpCall = new McpCall();
            mcpCall.setActions(List.of("call_weather")); // doesn't match "greet"
            mcpCall.setToolName("get_weather");

            var config = new McpCallsConfiguration();
            config.setMcpCalls(List.of(mcpCall));
            config.setMcpServerUrl("http://localhost:8080/mcp");

            var toolSpec = ToolSpecification.builder().name("get_weather").description("Get weather").build();
            var executor = mock(ToolExecutor.class);

            var mcpTools = new McpToolProviderManager.McpToolsResult(
                    List.of(toolSpec), Map.of("get_weather", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpTools);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> task.execute(memory, config));
            verify(executor, never()).execute(any(), any());
        }

        @Test
        @DisplayName("null actions in mcpCall — skipped gracefully")
        void nullActionsInCall() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var actionsData = mock(IData.class);
            when(currentStep.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("greet"));

            var mcpCall = new McpCall();
            mcpCall.setActions(null); // null actions
            mcpCall.setToolName("some_tool");

            var config = new McpCallsConfiguration();
            config.setMcpCalls(List.of(mcpCall));
            config.setMcpServerUrl("http://localhost:8080/mcp");

            var toolSpec = ToolSpecification.builder().name("some_tool").description("Tool").build();
            var mcpTools = new McpToolProviderManager.McpToolsResult(
                    List.of(toolSpec), Map.of("some_tool", mock(ToolExecutor.class)));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpTools);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> task.execute(memory, config));
        }
    }

    @Nested
    @DisplayName("configure Tests")
    class ConfigureTests {

        @Test
        @DisplayName("null URI — throws WorkflowConfigurationException")
        void nullUri() {
            var config = new HashMap<String, Object>();
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
        }

        @Test
        @DisplayName("empty URI — throws WorkflowConfigurationException")
        void emptyUri() {
            var config = new HashMap<String, Object>();
            config.put("uri", "");
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
        }

        @Test
        @DisplayName("valid URI — loads from resource library")
        void validUri() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("uri", "eddi://ai.labs.mcpcalls/mcpcallsstore/abc?version=1");

            var mcpConfig = new McpCallsConfiguration();
            when(resourceClientLibrary.getResource(any(), eq(McpCallsConfiguration.class)))
                    .thenReturn(mcpConfig);

            var result = task.configure(config, Map.of());
            assertNotNull(result);
            assertSame(mcpConfig, result);
        }
    }
}
