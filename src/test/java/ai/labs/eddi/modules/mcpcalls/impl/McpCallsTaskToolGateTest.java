/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Finding F14 — {@code McpCallsTask} is a SECOND door onto the same external
 * MCP tools the LLM loop reaches, but it invoked the {@code ToolExecutor}
 * directly: no {@code ToolExecutionService} (no rate limiting, caching or cost
 * tracking) and no {@code ToolApprovalGate}. {@code hitlConfig.toolApprovals}
 * therefore gated LLM-initiated calls and silently let rule-initiated ones
 * through.
 */
@DisplayName("McpCallsTask — metering and approval gate (F14)")
class McpCallsTaskToolGateTest {

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
    private ToolExecutionService toolExecutionService;

    private McpCallsTask task;
    private AutoCloseable mocks;

    /** Number of times the raw MCP executor actually ran. */
    private final AtomicInteger rawExecutions = new AtomicInteger();
    /** Number of times the metering pipeline was entered. */
    private final AtomicInteger meteredExecutions = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = openMocks(this);
        lenient().when(toolExecutionService.executeToolWrapped(anyString(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(inv -> {
                    meteredExecutions.incrementAndGet();
                    return ((Supplier<String>) inv.getArgument(4)).get();
                });
        task = new McpCallsTask(resourceClientLibrary, memoryItemConverter, jsonSerialization,
                mcpToolProviderManager, prePostUtils, toolExecutionService);
        task.toolHitlEnabled = true;
        task.defaultRateLimit = 100;
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private IConversationMemory memoryWith(ToolApprovalsConfig approvals) {
        IConversationMemory memory = mock(IConversationMemory.class);
        IWritableConversationStep step = mock(IWritableConversationStep.class);
        IData<List<String>> actions = new Data<>("actions", List.of("do_transfer"));
        when(memory.getCurrentStep()).thenReturn(step);
        lenient().when(step.<List<String>>getLatestData("actions")).thenReturn(actions);
        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        lenient().when(memory.getAgentToolApprovalsConfig()).thenReturn(approvals);
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        return memory;
    }

    private McpCallsConfiguration configWithOneCall() {
        var call = new McpCall();
        call.setName("transfer");
        call.setToolName("wire_transfer");
        call.setActions(List.of("do_transfer"));

        var config = new McpCallsConfiguration();
        config.setMcpServerUrl("http://mcp.example.com/mcp");
        config.setMcpCalls(List.of(call));
        return config;
    }

    private void stubDiscovery() {
        ToolExecutor executor = (ToolExecutionRequest request, Object memoryId) -> {
            rawExecutions.incrementAndGet();
            return "{\"status\":\"sent\"}";
        };
        Map<String, ToolExecutor> executors = Map.of("wire_transfer", executor);
        List<ToolSpecification> specs = List.of(ToolSpecification.builder().name("wire_transfer").build());
        when(mcpToolProviderManager.discoverTools(any()))
                .thenReturn(new McpToolProviderManager.McpToolsResult(specs, executors));
    }

    private static ToolApprovalsConfig requiring(String... patterns) {
        var approvals = new ToolApprovalsConfig();
        approvals.setRequireApproval(List.of(patterns));
        return approvals;
    }

    @Test
    @DisplayName("a rule-triggered MCP call runs through ToolExecutionService (rate limiting + cost tracking)")
    void routesThroughToolExecutionService() throws Exception {
        stubDiscovery();

        task.execute(memoryWith(null), configWithOneCall());

        assertEquals(1, meteredExecutions.get(), "the call must not bypass the metering pipeline");
        assertEquals(1, rawExecutions.get(), "the tool itself must still run");
    }

    @Test
    @DisplayName("a rule-triggered MCP call matching requireApproval is NOT executed")
    void approvalGatedCallIsRefused() throws Exception {
        stubDiscovery();

        task.execute(memoryWith(requiring("mcp:wire_transfer")), configWithOneCall());

        assertEquals(0, rawExecutions.get(),
                "an approval-gated tool must not be invocable from a behavior rule without human approval");
        assertEquals(0, meteredExecutions.get());
    }

    @Test
    @DisplayName("a bare-name requireApproval pattern also gates the rule-triggered door")
    void bareNamePatternGates() throws Exception {
        stubDiscovery();

        task.execute(memoryWith(requiring("wire_*")), configWithOneCall());

        assertEquals(0, rawExecutions.get());
    }

    @Test
    @DisplayName("a tool outside requireApproval still runs")
    void ungatedToolStillRuns() throws Exception {
        stubDiscovery();

        task.execute(memoryWith(requiring("mcp:delete_*")), configWithOneCall());

        assertEquals(1, rawExecutions.get());
    }

    @Test
    @DisplayName("the cluster-wide HITL kill switch makes the gate inert")
    void killSwitchDisablesGate() throws Exception {
        stubDiscovery();
        task.toolHitlEnabled = false;

        task.execute(memoryWith(requiring("mcp:wire_transfer")), configWithOneCall());

        assertEquals(1, rawExecutions.get(), "with eddi.hitl.tool.enabled=false the pre-HITL path is restored");
    }

    @Test
    @DisplayName("isApprovalGated: exempt beats requireApproval, matching the LLM-loop gate")
    void exemptWins() {
        var approvals = requiring("mcp:*");
        approvals.setExempt(List.of("mcp:wire_transfer"));

        boolean gated = task.isApprovalGated(memoryWith(approvals),
                ToolExecutionRequest.builder().name("wire_transfer").arguments("{}").build());

        assertFalse(gated);
    }

    @Test
    @DisplayName("isApprovalGated: no approvals config means the gate is inert")
    void inertWithoutConfig() {
        assertFalse(task.isApprovalGated(memoryWith(null),
                ToolExecutionRequest.builder().name("wire_transfer").arguments("{}").build()));
    }

    @Test
    @DisplayName("isApprovalGated: a matching pattern gates the call")
    void gatesMatchingPattern() {
        assertTrue(task.isApprovalGated(memoryWith(requiring("mcp:wire_transfer")),
                ToolExecutionRequest.builder().name("wire_transfer").arguments("{}").build()));
    }
}
