/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.MockResponse;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolMock;
import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.agenttests.AgentTestConversations;
import ai.labs.eddi.engine.agenttests.ToolMockRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rule-triggered MCP calls of an agent-test conversation: answered by the
 * suite, and — when nothing may run live — the MCP server is not contacted at
 * all, not even to list its tools.
 */
@DisplayName("McpCallsTask — agent-test mocks")
class McpCallsTaskAgentTestMockTest {

    private static final String TEST_USER = AgentTestConversations.userIdFor("0123456789abcdef01234567", 0, 1);

    private McpToolProviderManager mcpToolProviderManager;
    private ToolExecutionService toolExecutionService;
    private PrePostUtils prePostUtils;
    private IConversationMemory memory;
    private McpCallsTask task;

    @BeforeEach
    void setUp() {
        mcpToolProviderManager = mock(McpToolProviderManager.class);
        toolExecutionService = mock(ToolExecutionService.class);
        prePostUtils = mock(PrePostUtils.class);
        IMemoryItemConverter converter = mock(IMemoryItemConverter.class);
        task = new McpCallsTask(mock(IResourceClientLibrary.class), converter, mock(IJsonSerialization.class), mcpToolProviderManager,
                prePostUtils, toolExecutionService);

        memory = mock(IConversationMemory.class);
        var step = mock(IWritableConversationStep.class);
        IData<List<String>> actions = new Data<>("actions", List.of("do_transfer"));
        when(memory.getCurrentStep()).thenReturn(step);
        when(step.<List<String>>getLatestData("actions")).thenReturn(actions);
        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getUserId()).thenReturn(TEST_USER);
        when(converter.convert(memory)).thenReturn(new HashMap<>());
    }

    @AfterEach
    void tearDown() {
        ToolMockRegistry.unregister(TEST_USER);
    }

    private static McpCallsConfiguration config() {
        var call = new McpCall();
        call.setName("transfer");
        call.setToolName("wire_transfer");
        call.setActions(List.of("do_transfer"));
        call.setPostResponse(new PostResponse());
        var config = new McpCallsConfiguration();
        config.setMcpServerUrl("http://mcp.example.com/mcp");
        config.setMcpCalls(List.of(call));
        return config;
    }

    @Test
    @DisplayName("a mocked call runs postResponse without contacting the server")
    void mockedCallNeverContactsTheServer() throws Exception {
        ToolMockRegistry.register(TEST_USER, new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null,
                List.of(new ToolMock("wire_transfer", null, new MockResponse(200, "done"))), null)));

        task.execute(memory, config());

        verify(mcpToolProviderManager, never()).discoverTools(anyList());
        verifyNoInteractions(toolExecutionService);
        verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(200), eq(false));
    }

    @Test
    @DisplayName("an unmocked call is not executed and the server is not contacted")
    void unmockedCallIsNotExecuted() throws Exception {
        var session = new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null, List.of(), null));
        ToolMockRegistry.register(TEST_USER, session);

        task.execute(memory, config());

        verify(mcpToolProviderManager, never()).discoverTools(anyList());
        verifyNoInteractions(toolExecutionService);
        assertEquals(ToolMockRegistry.Outcome.NOT_EXECUTED, session.calls().getFirst().outcome());
    }
}
