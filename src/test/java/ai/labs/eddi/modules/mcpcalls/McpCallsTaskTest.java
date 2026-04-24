/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls;

import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager.McpToolsResult;
import ai.labs.eddi.modules.mcpcalls.impl.McpCallsTask;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link McpCallsTask} — deterministic MCP tool call lifecycle task.
 */
@DisplayName("McpCallsTask")
class McpCallsTaskTest {

    private McpCallsTask task;
    private IResourceClientLibrary resourceClientLibrary;
    private IMemoryItemConverter memoryItemConverter;
    private IJsonSerialization jsonSerialization;
    private McpToolProviderManager mcpToolProviderManager;
    private PrePostUtils prePostUtils;

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

    // ==================== Identity ====================

    @Test
    @DisplayName("getId returns correct identifier")
    void testGetId() {
        assertEquals("ai.labs.mcpcalls", task.getId());
    }

    @Test
    @DisplayName("getType returns 'mcpCalls'")
    void testGetType() {
        assertEquals("mcpCalls", task.getType());
    }

    // ==================== execute() ====================

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("returns early when no actions data in memory")
        void execute_noActions_returnsEarly() throws LifecycleException {
            MemoryContext ctx = TestMemoryFactory.create();
            McpCallsConfiguration config = new McpCallsConfiguration();

            task.execute(ctx.memory(), config);

            verifyNoInteractions(mcpToolProviderManager);
        }

        @Test
        @DisplayName("returns early when actions data result is null")
        void execute_nullActionsResult_returnsEarly() throws LifecycleException {
            MemoryContext ctx = TestMemoryFactory.create();
            when(ctx.currentStep().getLatestData(eq("actions")))
                    .thenReturn(new Data<>("actions", null));
            McpCallsConfiguration config = new McpCallsConfiguration();

            task.execute(ctx.memory(), config);

            verifyNoInteractions(mcpToolProviderManager);
        }

        @Test
        @DisplayName("returns early when mcpCalls list is empty")
        void execute_emptyMcpCalls_returnsEarly() throws LifecycleException {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("some_action"));
            McpCallsConfiguration config = new McpCallsConfiguration();
            config.setMcpCalls(Collections.emptyList());

            task.execute(ctx.memory(), config);

            verifyNoInteractions(mcpToolProviderManager);
        }

        @Test
        @DisplayName("returns early when no tools discovered from MCP server")
        void execute_noToolsDiscovered_returnsEarly() throws LifecycleException {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("trigger"));
            McpCallsConfiguration config = createConfigWithCall("trigger", "myTool");
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(null);

            task.execute(ctx.memory(), config);

            verify(mcpToolProviderManager).discoverTools(anyList());
            verifyNoInteractions(memoryItemConverter);
        }

        @Test
        @DisplayName("skips call when action does not match")
        void execute_actionMismatch_skipsCall() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("unrelated_action"));
            McpCallsConfiguration config = createConfigWithCall("specific_action", "myTool");
            McpToolsResult toolsResult = createToolsResult("myTool");
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(toolsResult);
            when(memoryItemConverter.convert(ctx.memory())).thenReturn(new HashMap<>());

            task.execute(ctx.memory(), config);

            // Tool executor should not be called
            verify(toolsResult.executors().get("myTool"), never()).execute(any(), any());
        }

        @Test
        @DisplayName("executes tool on matching action")
        void execute_matchingAction_executesTool() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("trigger"));
            McpCallsConfiguration config = createConfigWithCall("trigger", "myTool");
            McpToolsResult toolsResult = createToolsResult("myTool");
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(toolsResult);
            when(memoryItemConverter.convert(ctx.memory())).thenReturn(new HashMap<>());
            when(jsonSerialization.serialize(anyMap())).thenReturn("{}");
            when(toolsResult.executors().get("myTool").execute(any(), any())).thenReturn("result");

            task.execute(ctx.memory(), config);

            verify(toolsResult.executors().get("myTool")).execute(any(), any());
        }

        @Test
        @DisplayName("wildcard action '*' matches any action")
        void execute_wildcardAction_matchesAny() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("any_action"));
            McpCallsConfiguration config = createConfigWithCall("*", "myTool");
            McpToolsResult toolsResult = createToolsResult("myTool");
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(toolsResult);
            when(memoryItemConverter.convert(ctx.memory())).thenReturn(new HashMap<>());
            when(jsonSerialization.serialize(anyMap())).thenReturn("{}");
            when(toolsResult.executors().get("myTool").execute(any(), any())).thenReturn("done");

            task.execute(ctx.memory(), config);

            verify(toolsResult.executors().get("myTool")).execute(any(), any());
        }

        @Test
        @DisplayName("tool blocked by whitelist is not executed")
        void execute_toolBlockedByWhitelist_skipped() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("trigger"));
            McpCallsConfiguration config = createConfigWithCall("trigger", "blockedTool");
            config.setToolsWhitelist(List.of("allowedTool")); // blockedTool not in whitelist
            McpToolsResult toolsResult = createToolsResult("blockedTool");
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(toolsResult);
            when(memoryItemConverter.convert(ctx.memory())).thenReturn(new HashMap<>());

            task.execute(ctx.memory(), config);

            verify(toolsResult.executors().get("blockedTool"), never()).execute(any(), any());
        }

        @Test
        @DisplayName("tool blocked by blacklist is not executed")
        void execute_toolBlockedByBlacklist_skipped() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("trigger"));
            McpCallsConfiguration config = createConfigWithCall("trigger", "blockedTool");
            config.setToolsBlacklist(List.of("blockedTool"));
            McpToolsResult toolsResult = createToolsResult("blockedTool");
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(toolsResult);
            when(memoryItemConverter.convert(ctx.memory())).thenReturn(new HashMap<>());

            task.execute(ctx.memory(), config);

            verify(toolsResult.executors().get("blockedTool"), never()).execute(any(), any());
        }

        @Test
        @DisplayName("skips mcpCall with null actions list")
        void execute_nullActionsInCall_skips() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("trigger"));
            McpCallsConfiguration config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://localhost:3000");
            McpCall call = new McpCall();
            call.setToolName("myTool");
            // actions is null
            config.setMcpCalls(List.of(call));

            McpToolsResult toolsResult = createToolsResult("myTool");
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(toolsResult);
            when(memoryItemConverter.convert(ctx.memory())).thenReturn(new HashMap<>());

            task.execute(ctx.memory(), config);

            verify(toolsResult.executors().get("myTool"), never()).execute(any(), any());
        }
    }

    // ==================== configure() ====================

    @Nested
    @DisplayName("configure()")
    class ConfigureTests {

        @Test
        @DisplayName("throws WorkflowConfigurationException when no URI")
        void configure_noUri_throws() {
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(Collections.emptyMap(), Collections.emptyMap()));
        }

        @Test
        @DisplayName("throws WorkflowConfigurationException on service error")
        void configure_serviceError_throws() throws ServiceException {
            when(resourceClientLibrary.getResource(any(URI.class), eq(McpCallsConfiguration.class)))
                    .thenThrow(new ServiceException("not found"));

            Map<String, Object> config = Map.of("uri", "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/abc123");

            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, Collections.emptyMap()));
        }

        @Test
        @DisplayName("returns config from resource client library")
        void configure_validUri_returnsConfig() throws Exception {
            McpCallsConfiguration expectedConfig = new McpCallsConfiguration();
            when(resourceClientLibrary.getResource(any(URI.class), eq(McpCallsConfiguration.class)))
                    .thenReturn(expectedConfig);

            Map<String, Object> config = Map.of("uri", "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/abc123");
            var result = task.configure(config, Collections.emptyMap());

            assertSame(expectedConfig, result);
        }
    }

    // ==================== ExtensionDescriptor ====================

    @Test
    @DisplayName("getExtensionDescriptor returns correct descriptor")
    void testExtensionDescriptor() {
        var descriptor = task.getExtensionDescriptor();

        assertNotNull(descriptor);
        assertEquals("ai.labs.mcpcalls", descriptor.getType());
        assertEquals("MCP Calls", descriptor.getDisplayName());
        assertTrue(descriptor.getConfigs().containsKey("uri"));
    }

    // ==================== Helpers ====================

    private McpCallsConfiguration createConfigWithCall(String action, String toolName) {
        McpCallsConfiguration config = new McpCallsConfiguration();
        config.setMcpServerUrl("http://localhost:3000");
        McpCall call = new McpCall();
        call.setToolName(toolName);
        call.setActions(List.of(action));
        config.setMcpCalls(List.of(call));
        return config;
    }

    private McpToolsResult createToolsResult(String... toolNames) {
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();
        for (String name : toolNames) {
            specs.add(ToolSpecification.builder().name(name).description("test").build());
            executors.put(name, mock(ToolExecutor.class));
        }
        return new McpToolsResult(specs, executors);
    }
}
