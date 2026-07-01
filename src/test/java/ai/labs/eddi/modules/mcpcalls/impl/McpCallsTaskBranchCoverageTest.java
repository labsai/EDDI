/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("McpCallsTask — Branch Coverage")
class McpCallsTaskBranchCoverageTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private McpToolProviderManager mcpToolProviderManager;
    @Mock
    private PrePostUtils prePostUtils;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;

    private McpCallsTask task;

    @BeforeEach
    void setUp() {
        openMocks(this);
        task = new McpCallsTask(resourceClientLibrary, memoryItemConverter, jsonSerialization,
                mcpToolProviderManager, prePostUtils);
        when(memory.getCurrentStep()).thenReturn(currentStep);
    }

    // ─── getId / getType ─────────────────────────────────────────────────

    @Test
    @DisplayName("getId returns correct TaskId")
    void getId() {
        assertEquals("ai.labs.mcpcalls", task.getId().name());
    }

    @Test
    @DisplayName("getType returns mcpCalls")
    void getType() {
        assertEquals("mcpCalls", task.getType());
    }

    // ─── execute — early returns ─────────────────────────────────────────

    @Nested
    @DisplayName("execute — early returns")
    class ExecuteEarlyReturns {

        @Test
        @DisplayName("null actionsData → return early")
        void nullActionsData() throws LifecycleException {
            when(currentStep.getLatestData("actions")).thenReturn(null);
            var config = new McpCallsConfiguration();

            task.execute(memory, config);
            // No exception, no further interaction
            verifyNoInteractions(mcpToolProviderManager);
        }

        @Test
        @DisplayName("null actions result → return early")
        void nullActionsResult() throws LifecycleException {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(null);
            doReturn(actionsData).when(currentStep).getLatestData("actions");
            var config = new McpCallsConfiguration();

            task.execute(memory, config);
            verifyNoInteractions(mcpToolProviderManager);
        }

        @Test
        @DisplayName("null mcpCalls list → return early")
        void nullMcpCalls() throws LifecycleException {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpCalls(null);

            task.execute(memory, config);
            verifyNoInteractions(mcpToolProviderManager);
        }

        @Test
        @DisplayName("empty mcpCalls list → return early")
        void emptyMcpCalls() throws LifecycleException {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpCalls(List.of());

            task.execute(memory, config);
            verifyNoInteractions(mcpToolProviderManager);
        }

        @Test
        @DisplayName("null mcpTools from discoverTools → return early")
        void nullMcpTools() throws LifecycleException {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("send_message"));
            call.setToolName("myTool");
            config.setMcpCalls(List.of(call));

            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(null);

            task.execute(memory, config);
            verifyNoInteractions(memoryItemConverter);
        }

        @Test
        @DisplayName("empty toolSpecs → return early")
        void emptyToolSpecs() throws LifecycleException {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("send_message"));
            call.setToolName("myTool");
            config.setMcpCalls(List.of(call));

            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(), Map.of());
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);

            task.execute(memory, config);
            verifyNoInteractions(memoryItemConverter);
        }
    }

    // ─── execute — action matching ───────────────────────────────────────

    @Nested
    @DisplayName("execute — action matching and tool execution")
    class ExecuteActionMatching {

        @Test
        @DisplayName("null mcpCall actions → skip that call")
        void nullMcpCallActions() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(null); // null actions
            call.setToolName("myTool");
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("myTool").build();
            var executor = mock(ToolExecutor.class);
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("myTool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            task.execute(memory, config);
            // Tool should NOT be executed since actions=null
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("wildcard '*' action triggers call")
        void wildcardAction() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("something"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("*"));
            call.setToolName("myTool");
            call.setName("testCall");
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("myTool").build();
            var executor = mock(ToolExecutor.class);
            when(executor.execute(any(), any())).thenReturn("result");
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("myTool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            task.execute(memory, config);
            verify(executor).execute(any(), any());
        }

        @Test
        @DisplayName("action mismatch → tool not executed")
        void actionMismatch() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("other_action"));
            call.setToolName("myTool");
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("myTool").build();
            var executor = mock(ToolExecutor.class);
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("myTool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            task.execute(memory, config);
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("tool blocked by whitelist → return early")
        void toolBlockedByWhitelist() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            config.setToolsWhitelist(List.of("allowedTool"));
            var call = new McpCall();
            call.setActions(List.of("send_message"));
            call.setToolName("blockedTool");
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("blockedTool").build();
            var executor = mock(ToolExecutor.class);
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("blockedTool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            task.execute(memory, config);
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("tool on blacklist → excluded")
        void toolOnBlacklist() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            config.setToolsBlacklist(List.of("myTool"));
            var call = new McpCall();
            call.setActions(List.of("send_message"));
            call.setToolName("myTool");
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("myTool").build();
            var executor = mock(ToolExecutor.class);
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("myTool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            task.execute(memory, config);
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("executor not found → skip")
        void executorNotFound() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("send_message"));
            call.setToolName("nonexistent");
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("nonexistent").build();
            // Executor map does NOT contain "nonexistent"
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of());
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            // Should not throw
            task.execute(memory, config);
        }
    }

    // ─── execute — saveResponse branches ─────────────────────────────────

    @Nested
    @DisplayName("execute — saveResponse and postResponse")
    class SaveAndPostResponse {

        @Test
        @DisplayName("saveResponse=true stores result in memory")
        void saveResponseTrue() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("send_message"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("send_message"));
            call.setToolName("myTool");
            call.setName("myCall");
            call.setSaveResponse(true);
            call.setResponseObjectName("myResult");
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("myTool").build();
            var executor = mock(ToolExecutor.class);
            when(executor.execute(any(), any())).thenReturn("{\"data\":\"value\"}");
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("myTool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(jsonSerialization.deserialize(anyString(), eq(Object.class))).thenReturn(Map.of("data", "value"));

            task.execute(memory, config);

            verify(prePostUtils).createMemoryEntry(eq(currentStep), any(), eq("myResult"), eq("mcpCalls"));
        }

        @Test
        @DisplayName("saveResponse=true with blank responseObjectName defaults to callName + Response")
        void saveResponseDefaultName() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("act"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("act"));
            call.setToolName("tool");
            call.setName("callXY");
            call.setSaveResponse(true);
            call.setResponseObjectName("  "); // blank
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("tool").build();
            var executor = mock(ToolExecutor.class);
            when(executor.execute(any(), any())).thenReturn("raw text");
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("tool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(jsonSerialization.deserialize(anyString(), eq(Object.class))).thenThrow(new IOException("not json"));

            task.execute(memory, config);

            verify(prePostUtils).createMemoryEntry(eq(currentStep), eq("raw text"), eq("callXYResponse"), eq("mcpCalls"));
        }

        @Test
        @DisplayName("saveResponse=false → no memory entry")
        void saveResponseFalse() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("act"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("act"));
            call.setToolName("tool");
            call.setSaveResponse(false);
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("tool").build();
            var executor = mock(ToolExecutor.class);
            when(executor.execute(any(), any())).thenReturn("result");
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("tool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            task.execute(memory, config);

            verify(prePostUtils, never()).createMemoryEntry(any(), any(), any(), any());
        }
    }

    // ─── execute — tool arguments templating ─────────────────────────────

    @Nested
    @DisplayName("execute — tool arguments")
    class ToolArguments {

        @Test
        @DisplayName("string arguments are templated")
        void stringArgTemplated() throws Exception {
            @SuppressWarnings("unchecked")
            IData<List<String>> actionsData = mock(IData.class);
            when(actionsData.getResult()).thenReturn(List.of("act"));
            doReturn(actionsData).when(currentStep).getLatestData("actions");

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var call = new McpCall();
            call.setActions(List.of("act"));
            call.setToolName("tool");
            call.setToolArguments(Map.of("param1", "{{value}}", "param2", Integer.valueOf(42)));
            config.setMcpCalls(List.of(call));

            var toolSpec = ToolSpecification.builder().name("tool").build();
            var executor = mock(ToolExecutor.class);
            when(executor.execute(any(), any())).thenReturn("result");
            var mcpResult = new McpToolProviderManager.McpToolsResult(List.of(toolSpec), Map.of("tool", executor));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(mcpResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
            when(prePostUtils.templateValues(eq("{{value}}"), anyMap())).thenReturn("resolved");
            when(jsonSerialization.serialize(anyMap())).thenReturn("{\"param1\":\"resolved\",\"param2\":42}");

            task.execute(memory, config);

            verify(prePostUtils).templateValues(eq("{{value}}"), anyMap());
            verify(executor).execute(any(), any());
        }
    }

    // ─── configure ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("configure")
    class Configure {

        @Test
        @DisplayName("null URI throws WorkflowConfigurationException")
        void nullUri() {
            Map<String, Object> config = Map.of();
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
        }

        @Test
        @DisplayName("valid URI returns config from resourceClientLibrary")
        void validUri() throws Exception {
            var expected = new McpCallsConfiguration();
            when(resourceClientLibrary.getResource(any(), eq(McpCallsConfiguration.class))).thenReturn(expected);

            Map<String, Object> config = new HashMap<>();
            config.put("uri", "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/abc?version=1");
            Object result = task.configure(config, Map.of());
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("ServiceException wraps in WorkflowConfigurationException")
        void serviceException() throws Exception {
            when(resourceClientLibrary.getResource(any(), eq(McpCallsConfiguration.class)))
                    .thenThrow(new ServiceException("fail"));

            Map<String, Object> config = new HashMap<>();
            config.put("uri", "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/abc?version=1");
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Map.of()));
        }
    }

    // ─── getExtensionDescriptor ──────────────────────────────────────────

    @Test
    @DisplayName("getExtensionDescriptor returns valid descriptor")
    void extensionDescriptor() {
        ExtensionDescriptor desc = task.getExtensionDescriptor();
        assertNotNull(desc);
        assertEquals("MCP Calls", desc.getDisplayName());
        assertTrue(desc.getConfigs().containsKey("uri"));
    }
}
