/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("McpCallsTask Tests")
class McpCallsTaskTest {

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

    private McpCallsTask task;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        task = new McpCallsTask(resourceClientLibrary, memoryItemConverter, jsonSerialization,
                mcpToolProviderManager, prePostUtils);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    @DisplayName("getId returns correct task ID")
    void getId() {
        assertEquals("ai.labs.mcpcalls", task.getId().name());
    }

    @Test
    @DisplayName("getType returns 'mcpCalls'")
    void getType() {
        assertEquals("mcpCalls", task.getType());
    }

    @Test
    @DisplayName("getExtensionDescriptor returns valid descriptor")
    void getExtensionDescriptor() {
        ExtensionDescriptor desc = task.getExtensionDescriptor();
        assertNotNull(desc);
        assertEquals("MCP Calls", desc.getDisplayName());
        assertTrue(desc.getConfigs().containsKey("uri"));
    }

    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("null actions data — returns early")
        void nullActionsData() throws Exception {
            var memory = mock(IConversationMemory.class);
            var step = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            when(step.getLatestData("actions")).thenReturn(null);

            var config = new McpCallsConfiguration();
            assertDoesNotThrow(() -> task.execute(memory, config));
        }

        @Test
        @DisplayName("null actions result — returns early")
        void nullActionsResult() throws Exception {
            var memory = mock(IConversationMemory.class);
            var step = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            var actionsData = mock(IData.class);
            when(step.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(null);

            var config = new McpCallsConfiguration();
            assertDoesNotThrow(() -> task.execute(memory, config));
        }

        @Test
        @DisplayName("null mcpCalls in config — returns early")
        void nullMcpCalls() throws Exception {
            var memory = mock(IConversationMemory.class);
            var step = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            var actionsData = mock(IData.class);
            when(step.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("action1"));

            var config = new McpCallsConfiguration();
            config.setMcpCalls(null);
            assertDoesNotThrow(() -> task.execute(memory, config));
        }

        @Test
        @DisplayName("empty mcpCalls — returns early")
        void emptyMcpCalls() throws Exception {
            var memory = mock(IConversationMemory.class);
            var step = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            var actionsData = mock(IData.class);
            when(step.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("action1"));

            var config = new McpCallsConfiguration();
            config.setMcpCalls(new ArrayList<>());
            assertDoesNotThrow(() -> task.execute(memory, config));
        }

        @Test
        @DisplayName("no tools discovered — returns early")
        void noToolsDiscovered() throws Exception {
            var memory = mock(IConversationMemory.class);
            var step = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            var actionsData = mock(IData.class);
            when(step.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("action1"));

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var mcpCall = new McpCall();
            mcpCall.setActions(List.of("action1"));
            mcpCall.setToolName("tool1");
            config.setMcpCalls(List.of(mcpCall));

            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(null);

            assertDoesNotThrow(() -> task.execute(memory, config));
        }

        @Test
        @DisplayName("mcpCall with null actions — skipped")
        void mcpCallNullActions() throws Exception {
            var memory = mock(IConversationMemory.class);
            var step = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            var actionsData = mock(IData.class);
            when(step.getLatestData("actions")).thenReturn(actionsData);
            when(actionsData.getResult()).thenReturn(List.of("action1"));

            var config = new McpCallsConfiguration();
            config.setMcpServerUrl("http://mcp.local");
            var mcpCall = new McpCall();
            mcpCall.setActions(null);
            config.setMcpCalls(List.of(mcpCall));

            var toolSpec = ToolSpecification.builder().name("tool1").build();
            var toolsResult = new McpToolProviderManager.McpToolsResult(
                    List.of(toolSpec), Map.of("tool1", mock(ToolExecutor.class)));
            when(mcpToolProviderManager.discoverTools(anyList())).thenReturn(toolsResult);
            when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> task.execute(memory, config));
        }
    }

    @Nested
    @DisplayName("configure")
    class ConfigureTests {

        @Test
        @DisplayName("null URI — throws WorkflowConfigurationException")
        void nullUri() {
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(Map.of(), Map.of()));
        }

        @Test
        @DisplayName("empty URI — throws WorkflowConfigurationException")
        void emptyUri() {
            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(Map.of("uri", ""), Map.of()));
        }

        @Test
        @DisplayName("service exception — throws WorkflowConfigurationException")
        void serviceException() throws Exception {
            when(resourceClientLibrary.getResource(any(), eq(McpCallsConfiguration.class)))
                    .thenThrow(new ServiceException("failed"));

            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(Map.of("uri", "eddi://ai.labs.mcpcalls/mcpcalls/id1"), Map.of()));
        }

        @Test
        @DisplayName("valid URI — returns McpCallsConfiguration")
        void validUri() throws Exception {
            var config = new McpCallsConfiguration();
            when(resourceClientLibrary.getResource(any(), eq(McpCallsConfiguration.class)))
                    .thenReturn(config);

            var result = task.configure(Map.of("uri", "eddi://ai.labs.mcpcalls/mcpcalls/id1"), Map.of());
            assertSame(config, result);
        }
    }
}
