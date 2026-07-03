/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests the tool-approval gate hook inside {@link AgentOrchestrator}'s
 * tool-calling loop (Task 5, Step 3): a gated tool call pauses the loop by
 * throwing {@link ToolApprovalRequiredException}, while ungated calls in the
 * same batch still execute.
 */
class AgentOrchestratorToolPauseTest {

    @Mock
    private CalculatorTool calculatorTool;
    @Mock
    private DateTimeTool dateTimeTool;
    @Mock
    private WebSearchTool webSearchTool;
    @Mock
    private DataFormatterTool dataFormatterTool;
    @Mock
    private WebScraperTool webScraperTool;
    @Mock
    private TextSummarizerTool textSummarizerTool;
    @Mock
    private PdfReaderTool pdfReaderTool;
    @Mock
    private WeatherTool weatherTool;
    @Mock
    private FetchToolResponsePageTool fetchToolResponsePageTool;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private McpToolProviderManager mcpToolProviderManager;
    @Mock
    private A2AToolProviderManager a2aToolProviderManager;
    @Mock
    private IRestAgentStore restAgentStore;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IApiCallExecutor apiCallExecutor;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private ToolResponseTruncator toolResponseTruncator;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private MemorySnapshotService memorySnapshotService;

    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;
    @Mock
    private ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore journalStore;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, tenantQuotaService,
                memorySnapshotService,
                null, null, null, null, null,
                journalStore, new ConversationHistoryBuilder());

        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getAgentId()).thenReturn(null);
        when(memory.getAgentVersion()).thenReturn(null);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        // toolResponseTruncator is transparent by default
        when(toolResponseTruncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        // tenant quota always allows
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any()))
                .thenReturn(QuotaCheckResult.OK);
        // executeToolWrapped actually runs the supplied executor so we can verify
        // which tool method was invoked.
        when(toolExecutionService.executeToolWrapped(anyString(), anyString(), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(inv -> {
                    Supplier<String> sup = inv.getArgument(3);
                    return sup.get();
                });
    }

    /** Task with two built-in tools enabled (calculate + getCurrentDateTime). */
    private LlmConfiguration.Task twoToolTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    private ToolApprovalsConfig gateCalculate() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("calculate"));
        return cfg;
    }

    private ChatResponse toolBatch(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    @Test
    @DisplayName("gated call never executes; ungated call in same batch executes exactly once")
    void gatedNotExecuted_ungatedExecutes() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var ungatedReq = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq, ungatedReq));

        assertThrows(ToolApprovalRequiredException.class, () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                task, memory, gateCalculate(), 0));

        // The ungated datetime tool ran once; the gated calculator never ran.
        verify(dateTimeTool, times(1)).getCurrentDateTime(anyString());
        verify(calculatorTool, never()).calculate(anyString());
    }

    @Test
    @DisplayName("thrown exception carries a batch with gated call metadata + transcript + ungated names + llmTaskId")
    void batchCarriesMetadata() {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var ungatedReq = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq, ungatedReq));

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                        task, memory, gateCalculate(), 7));

        PendingToolCallBatch batch = ex.getBatch();
        assertNotNull(batch, "exception must carry a pending batch");
        assertEquals("llmTask-A", batch.getLlmTaskId());
        assertEquals(7, batch.getLlmTaskIndex());
        assertNotNull(batch.getPauseEpoch());

        // one gated call, correct id/name/source/gateReason
        assertEquals(1, batch.getCalls().size());
        var call = batch.getCalls().get(0);
        assertEquals("c1", call.getCallId());
        assertEquals("calculate", call.getToolName());
        assertEquals("builtin", call.getSource());
        assertEquals("calculate", call.getGateReason());
        assertNotNull(call.getArgumentsRedacted());

        // ungated names recorded
        assertEquals(List.of("getCurrentDateTime"), batch.getExecutedUngatedCallNames());

        // transcript serialized and contains the AiMessage's tool request
        assertNotNull(batch.getChatTranscriptJson());
        assertFalse(batch.isTranscriptOmitted());
        assertTrue(batch.getChatTranscriptJson().contains("calculate"));

        assertEquals(1, batch.getPauseCountThisTurn());
    }

    @Test
    @DisplayName("oversized raw args set argsTruncated; redacted args never exceed cap")
    void argsTruncation() {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);

        StringBuilder big = new StringBuilder("{\"expression\":\"");
        big.append("9".repeat(PendingToolCallBatch.ARGS_RAW_MAX_BYTES + 1000));
        big.append("\"}");
        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments(big.toString()).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq));

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                        task, memory, gateCalculate(), 0));

        var call = ex.getBatch().getCalls().get(0);
        assertTrue(call.isArgsTruncated(), "argsTruncated must be set when raw args exceed the cap");
        assertTrue(call.getArgumentsRaw().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PendingToolCallBatch.ARGS_RAW_MAX_BYTES);
        assertTrue(
                call.getArgumentsRedacted().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PendingToolCallBatch.ARGS_REDACTED_MAX_BYTES);
    }

    @Test
    @DisplayName("pause-cap reached: no throw, gated calls answered with DENIED envelope, loop continues")
    void pauseCapReached() throws Exception {
        var task = twoToolTask();
        var cfg = gateCalculate();
        cfg.setMaxPausesPerTurn(1);
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        // First model turn: gated tool call. Second turn: plain text (loop ends).
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(gatedReq))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());

        // Pre-seed the pause count at the cap so the gate fails closed.
        doReturn(dataOfInt(1)).when(currentStep).getLatestData("hitl:tool_pause_count");

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                task, memory, cfg, 0);

        assertNotNull(result);
        assertEquals("done", result.response());
        // Gated tool still never actually executed
        verify(calculatorTool, never()).calculate(anyString());
    }

    @Test
    @DisplayName("null effective config: zero behavior change — gate short-circuits, model text returned")
    void nullConfig_noGate() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("plain answer")).build());

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                task, memory, null, 0);

        assertNotNull(result);
        assertEquals("plain answer", result.response());
    }

    @Test
    @DisplayName("AgentExecutionHelper.executeWithRetry rethrows the SAME ToolApprovalRequiredException, no retry, no wrap")
    void executeWithRetryRethrowsSameInstance() {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        var tare = new ToolApprovalRequiredException("needs approval", batch);

        var task = new LlmConfiguration.Task();
        // A retry config that WOULD retry (3 attempts, long backoff) — the guard
        // must bypass it so no retry sleep occurs.
        var retry = new LlmConfiguration.RetryConfiguration();
        retry.setMaxAttempts(3);
        retry.setBackoffDelayMs(60_000L);
        task.setRetry(retry);

        long start = System.currentTimeMillis();
        var thrown = assertThrows(ToolApprovalRequiredException.class,
                () -> AgentExecutionHelper.executeWithRetry(() -> {
                    throw tare;
                }, task, "x"));
        long elapsed = System.currentTimeMillis() - start;

        assertSame(tare, thrown, "must rethrow the exact same instance, not a wrapped copy");
        assertTrue(elapsed < 5_000, "must not sleep/retry before rethrowing (elapsed=" + elapsed + "ms)");
    }

    @SuppressWarnings("unchecked")
    private ai.labs.eddi.engine.memory.IData<Integer> dataOfInt(int v) {
        var d = mock(ai.labs.eddi.engine.memory.IData.class);
        when(d.getResult()).thenReturn(v);
        return d;
    }
}
