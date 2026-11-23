/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The email approval gate for rule-driven mcpcalls (email plan v3 §8.1): a mail
 * turn's call that {@code readOnlyTools} does not name is refused before
 * anything of it runs.
 */
class McpCallsTaskEmailGateTest {

    private ToolExecutionService toolExecutionService;
    private PrePostUtils prePostUtils;
    private IConversationMemory memory;
    private IWritableConversationStep step;
    private McpCallsTask task;
    private McpCallsConfiguration config;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        toolExecutionService = mock(ToolExecutionService.class);
        lenient().when(toolExecutionService.executeToolWrapped(anyString(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyInt())).thenAnswer(inv -> ((Supplier<String>) inv.getArgument(4)).get());
        prePostUtils = mock(PrePostUtils.class);
        IMemoryItemConverter converter = mock(IMemoryItemConverter.class);
        McpToolProviderManager manager = mock(McpToolProviderManager.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn("ok");
        when(manager.discoverTools(anyList())).thenReturn(new McpToolProviderManager.McpToolsResult(
                List.of(ToolSpecification.builder().name("search").build(), ToolSpecification.builder().name("refund").build()),
                Map.of("search", executor, "refund", executor)));
        task = new McpCallsTask(mock(IResourceClientLibrary.class), converter, mock(IJsonSerialization.class), manager, prePostUtils,
                toolExecutionService);

        memory = mock(IConversationMemory.class);
        step = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);
        IData<List<String>> actions = mock(IData.class);
        when(actions.getResult()).thenReturn(List.of("act"));
        when(step.getLatestData("actions")).thenReturn((IData) actions);
        when(converter.convert(memory)).thenReturn(new HashMap<>());

        config = new McpCallsConfiguration();
        config.setMcpServerUrl("https://mcp.example.com");
        config.setMcpCalls(List.of(call("refund"), call("search")));
    }

    private static McpCall call(String tool) {
        var call = new McpCall();
        call.setName(tool + "Call");
        call.setToolName(tool);
        call.setActions(List.of("act"));
        return call;
    }

    private void mailTurn(String... readOnlyTools) {
        when(memory.getOrigin()).thenReturn(TurnOrigin.EMAIL);
        IData<Object> gate = new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("actions", List.of(readOnlyTools), false, null, null));
        when(step.getData("context:" + EmailApprovalGate.CONTEXT_KEY)).thenReturn(gate);
    }

    @Test
    @DisplayName("a tainted rule-driven mcpcall is refused unless readOnlyTools names it")
    void taintedRefused() throws Exception {
        mailTurn("mcp:search");

        task.execute(memory, config);

        verify(toolExecutionService, times(1)).executeToolWrapped(eq("search"), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyInt());
        verify(toolExecutionService, never()).executeToolWrapped(eq("refund"), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyInt());
        verify(prePostUtils).createMemoryEntry(eq(step), contains("not read-only"), eq("refundCallResponseError"), anyString());
    }

    @Test
    @DisplayName("an untainted turn's mcpcalls run")
    void untaintedRuns() throws Exception {
        task.execute(memory, config);

        verify(toolExecutionService, times(1)).executeToolWrapped(eq("refund"), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyInt());
    }
}
