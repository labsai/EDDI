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
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
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
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Branch-coverage-focused unit tests for {@link AgentOrchestrator}. Exercises
 * error/edge paths not covered by the happy-path integration-style tests:
 * overload delegation, tool-executor-missing, budget/quota denial branches,
 * rate-limit/cache/cost-tracking flags, pause-cap fail-closed,
 * restoreActiveSpecs variants, normalizeToolCallIds, buildPauseReason,
 * buildPendingBatch edges, and resumeToolLoop verdict application. Setup
 * mirrors the sibling tests.
 */
class AgentOrchestratorCoverageTest {

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
    private IHitlToolJournalStore journalStore;

    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;

    private ConversationHistoryBuilder historyBuilder;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        historyBuilder = new ConversationHistoryBuilder();
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
                journalStore, historyBuilder);

        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        lenient().when(memory.getAgentId()).thenReturn(null);
        lenient().when(memory.getAgentVersion()).thenReturn(null);
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(toolResponseTruncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        lenient().when(tenantQuotaService.checkCostBudget(any())).thenReturn(QuotaCheckResult.OK);
        lenient().when(toolExecutionService.executeToolWrapped(anyString(), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(inv -> {
                    Supplier<String> sup = inv.getArgument(4);
                    return sup.get();
                });
    }

    // ─── helpers ───

    private LlmConfiguration.Task twoToolTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    private LlmConfiguration.Task calcOnlyTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-C");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));
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

    private ChatResponse text(String s) {
        return ChatResponse.builder().aiMessage(AiMessage.from(s)).build();
    }

    private PendingToolCallBatch.PendingToolCall gatedCall(String callId, String tool, String args) {
        var c = new PendingToolCallBatch.PendingToolCall();
        c.setCallId(callId);
        c.setToolName(tool);
        c.setSource("builtin");
        c.setArgumentsRaw(args);
        c.setArgsTruncated(false);
        return c;
    }

    private PendingToolCallBatch batchWith(int iterationIndex, List<PendingToolCallBatch.PendingToolCall> gated,
                                           List<ToolExecutionRequest> aiRequests) {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llmTask-A");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(iterationIndex);
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        batch.setAutoApproveCount(0);
        batch.setCalls(gated);
        batch.setTraceSoFar(new ArrayList<>());

        List<ChatMessage> transcript = new ArrayList<>();
        transcript.add(UserMessage.from("do the thing"));
        transcript.add(AiMessage.from(aiRequests));
        var codec = new ChatTranscriptCodec();
        var res = codec.serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        batch.setChatTranscriptJson(res.json());
        batch.setTranscriptOmitted(res.omitted());
        return batch;
    }

    private HitlDecision approveAll() {
        var d = new HitlDecision();
        d.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        d.setDecidedBy("reviewer-1");
        return d;
    }

    @SuppressWarnings("unchecked")
    private ai.labs.eddi.engine.memory.IData<Integer> dataOfInt(int v) {
        var d = mock(ai.labs.eddi.engine.memory.IData.class);
        lenient().when(d.getResult()).thenReturn(v);
        return d;
    }

    // ═══════════════════════════════════════════════════════════════════
    // executeIfToolsEnabled — overload delegation + null return
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void executeIfToolsEnabled_twoArgOverload_noTools_returnsNull() throws Exception {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);

        assertNull(orchestrator.executeIfToolsEnabled(null, "sys", List.of(), task, memory));
    }

    @Test
    void executeIfToolsEnabled_gateOverload_noTools_returnsNull() throws Exception {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);

        assertNull(orchestrator.executeIfToolsEnabled(null, "sys", List.of(), task, memory, gateCalculate(), 3));
    }

    @Test
    void executeIfToolsEnabled_transcriptCapOverload_noTools_returnsNull() throws Exception {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);

        assertNull(orchestrator.executeIfToolsEnabled(null, "sys", List.of(), task, memory, null, -1, 4096));
    }

    @Test
    void executeIfToolsEnabled_gateOverload_delegatesAndRunsLoop() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("hello"));

        // two-arg + gate overload (no explicit transcript cap) delegates through.
        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys",
                List.of(UserMessage.from("hi")), task, memory, gateCalculate(), 0);

        assertNotNull(result);
        assertEquals("hello", result.response());
    }

    @Test
    void executeIfToolsEnabled_twoArgOverload_delegatesAndRunsLoop() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("plain"));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        assertEquals("plain", result.response());
    }

    @Test
    void executeIfToolsEnabled_emptySystemMessage_stillRuns() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("no sys"));

        // empty system message -> skips the SystemMessage.from branch
        var result = orchestrator.executeIfToolsEnabled(chatModel, "", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        assertEquals("no sys", result.response());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tool-call loop error paths (executed via the live loop)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void toolCall_executorMissing_returnsNotFoundError() throws Exception {
        var task = calcOnlyTask();
        ChatModel chatModel = mock(ChatModel.class);
        // Model requests a tool that is NOT registered.
        var missingReq = ToolExecutionRequest.builder().id("c1").name("does_not_exist").arguments("{}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(missingReq))
                .thenReturn(text("recovered"));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        assertEquals("recovered", result.response());
        // Trace carries a tool_result with the not-found error.
        boolean notFound = result.trace().stream()
                .anyMatch(e -> "tool_result".equals(e.get("type"))
                        && String.valueOf(e.get("result")).contains("not found"));
        assertTrue(notFound, "missing executor must yield a 'not found' tool_result");
    }

    @Test
    void toolCall_perConversationBudgetExceeded_returnsBudgetError() throws Exception {
        var task = calcOnlyTask();
        task.setMaxBudgetPerConversation(1.0);
        var costTracker = mock(ai.labs.eddi.modules.llm.tools.ToolCostTracker.class);
        when(toolExecutionService.getCostTracker()).thenReturn(costTracker);
        when(costTracker.isWithinBudget(eq("conv-1"), eq(1.0))).thenReturn(false);

        ChatModel chatModel = mock(ChatModel.class);
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1+1\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(calcReq))
                .thenReturn(text("stopped"));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        assertEquals("stopped", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        boolean budgetErr = result.trace().stream()
                .anyMatch(e -> "tool_error".equals(e.get("type"))
                        && String.valueOf(e.get("error")).contains("Budget exceeded"));
        assertTrue(budgetErr, "budget-exceeded must be recorded as a tool_error");
    }

    @Test
    void toolCall_tenantCostBudgetDenied_returnsQuotaError() throws Exception {
        var task = calcOnlyTask();
        when(tenantQuotaService.checkCostBudget(any()))
                .thenReturn(new QuotaCheckResult(false, "monthly cap reached"));

        ChatModel chatModel = mock(ChatModel.class);
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1+1\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(calcReq))
                .thenReturn(text("done"));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        assertEquals("done", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        boolean quotaErr = result.trace().stream()
                .anyMatch(e -> "tool_error".equals(e.get("type"))
                        && String.valueOf(e.get("error")).contains("monthly cap reached"));
        assertTrue(quotaErr, "tenant quota denial must be recorded as a tool_error");
    }

    @Test
    void toolCall_rateLimitCacheCostDisabled_stillExecutes() throws Exception {
        var task = calcOnlyTask();
        // Flip all three per-request flags to false -> exercises the ternary-false
        // arms.
        task.setEnableRateLimiting(false);
        task.setEnableToolCaching(false);
        task.setEnableCostTracking(false);

        ChatModel chatModel = mock(ChatModel.class);
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"2+2\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(calcReq))
                .thenReturn(text("four"));
        when(calculatorTool.calculate("2+2")).thenReturn("4");

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertEquals("four", result.response());
        // executeToolWrapped invoked with all-false flags.
        verify(toolExecutionService).executeToolWrapped(eq("calculate"), anyString(), nullable(String.class), any(),
                any(Supplier.class), eq(false), eq(false), eq(false), anyInt());
    }

    // ─── D5: cache scope tag handed to ToolExecutionService ───

    /**
     * Runs one tool call and returns the {@code cacheScopeTag} the orchestrator
     * passed to {@link ToolExecutionService#executeToolWrapped}.
     */
    private String capturedCacheScopeTag(LlmConfiguration.Task task) throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"2+2\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(calcReq))
                .thenReturn(text("four"));
        when(calculatorTool.calculate("2+2")).thenReturn("4");

        orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        // nullable(): anyString() would not match a null tag, so a regression that
        // stopped passing one would make this verification match zero invocations.
        ArgumentCaptor<String> scopeTag = ArgumentCaptor.forClass(String.class);
        verify(toolExecutionService).executeToolWrapped(eq("calculate"), anyString(), scopeTag.capture(), any(),
                any(Supplier.class), anyBoolean(), anyBoolean(), anyBoolean(), anyInt());
        return scopeTag.getValue();
    }

    @Test
    void toolCall_defaultScope_passesPerUserCacheTag() throws Exception {
        when(memory.getUserId()).thenReturn("alice");

        String tag = capturedCacheScopeTag(calcOnlyTask());

        assertNotNull(tag, "a user-scoped tool call must carry a cache scope tag");
        assertTrue(tag.startsWith("u:"), "default scope is USER, expected a 'u:' tag but got: " + tag);
        assertFalse(tag.contains("alice"), "the raw userId must not travel into the cache key");
        assertNotEquals(tag, capturedCacheScopeTagForOtherUser(), "two users must not share a cache partition");
    }

    /** Same call as above for a different userId, on a fresh orchestrator state. */
    private String capturedCacheScopeTagForOtherUser() throws Exception {
        reset(toolExecutionService, calculatorTool);
        lenient().when(toolExecutionService.executeToolWrapped(anyString(), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(inv -> {
                    Supplier<String> sup = inv.getArgument(4);
                    return sup.get();
                });
        when(memory.getUserId()).thenReturn("bob");
        return capturedCacheScopeTag(calcOnlyTask());
    }

    @Test
    void toolCall_globalScopeOptIn_passesSharedCacheTag() throws Exception {
        when(memory.getUserId()).thenReturn("alice");
        var task = calcOnlyTask();
        task.setToolCacheScopes(Map.of("calculate", "global"));

        assertEquals("g", capturedCacheScopeTag(task),
                "an explicit per-tool GLOBAL opt-in is the only way back to a shared partition");
    }

    @Test
    void toolCall_noIdentityAtAll_passesNullCacheTagSoCacheIsBypassed() throws Exception {
        when(memory.getUserId()).thenReturn(null);
        when(memory.getConversationId()).thenReturn(null);

        assertNull(capturedCacheScopeTag(calcOnlyTask()),
                "with neither a userId nor a conversationId there is nothing to partition on; "
                        + "the tag must stay null so ToolExecutionService skips the cache entirely");
    }

    @Test
    void toolCall_perToolRateLimitOverride_usesConfiguredLimit() throws Exception {
        var task = calcOnlyTask();
        task.setDefaultRateLimit(50);
        task.setToolRateLimits(Map.of("calculate", 7));

        ChatModel chatModel = mock(ChatModel.class);
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"3+3\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(calcReq))
                .thenReturn(text("six"));
        when(calculatorTool.calculate("3+3")).thenReturn("6");

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertEquals("six", result.response());
        // per-tool override (7) wins over defaultRateLimit (50).
        verify(toolExecutionService).executeToolWrapped(eq("calculate"), anyString(), nullable(String.class), any(),
                any(Supplier.class), anyBoolean(), anyBoolean(), anyBoolean(), eq(7));
    }

    @Test
    void toolCall_maxIterationsReached_returnsSentinelOrLastText() throws Exception {
        var task = calcOnlyTask();
        task.setMaxToolIterations(2);

        ChatModel chatModel = mock(ChatModel.class);
        // Every model turn requests the tool again -> loop never returns text -> budget
        // exhausted.
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1+1\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(calcReq));
        when(calculatorTool.calculate("1+1")).thenReturn("2");

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        // Last message is a tool result (not an AiMessage) -> sentinel string.
        assertEquals("Max tool iterations reached", result.response());
    }

    @Test
    void toolCall_counterweightStrict_capsIterations() throws Exception {
        var task = calcOnlyTask();
        task.setMaxToolIterations(10);
        var cw = new LlmConfiguration.CounterweightConfig();
        cw.setEnabled(true);
        cw.setLevel("strict");
        task.setCounterweight(cw);

        ChatModel chatModel = mock(ChatModel.class);
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1+1\"}").build();
        // Always requests tool -> loop runs to the strict cap (5) then returns
        // sentinel.
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(calcReq));
        when(calculatorTool.calculate("1+1")).thenReturn("2");

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        // strict cap = 5 model turns.
        verify(chatModel, times(5)).chat(any(ChatRequest.class));
    }

    @Test
    void memorySnapshotNull_noCheckpoint_stillExecutes() throws Exception {
        // Orchestrator built with a null MemorySnapshotService -> skips the checkpoint
        // branch.
        var orch = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, tenantQuotaService,
                null,
                null, null, null, null, null,
                journalStore, historyBuilder);

        var task = calcOnlyTask();
        ChatModel chatModel = mock(ChatModel.class);
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"5+5\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(calcReq))
                .thenReturn(text("ten"));
        when(calculatorTool.calculate("5+5")).thenReturn("10");

        var result = orch.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertEquals("ten", result.response());
        verify(memorySnapshotService, never()).createCheckpoint(any(), anyString(), anyString());
    }

    @Test
    void tenantQuotaServiceNull_skipsQuotaCheck() throws Exception {
        var orch = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, null,
                memorySnapshotService,
                null, null, null, null, null,
                journalStore, historyBuilder);

        var task = calcOnlyTask();
        ChatModel chatModel = mock(ChatModel.class);
        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"8+8\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(calcReq))
                .thenReturn(text("sixteen"));
        when(calculatorTool.calculate("8+8")).thenReturn("16");

        var result = orch.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertEquals("sixteen", result.response());
    }

    // ═══════════════════════════════════════════════════════════════════
    // restoreActiveSpecs (private static) — EAGER / LAZY-empty / LAZY-named /
    // LAZY-absent
    // ═══════════════════════════════════════════════════════════════════

    private AgentOrchestrator.ToolSetup setupWith(List<ToolSpecification> all, List<ToolSpecification> builtIn) {
        return new AgentOrchestrator.ToolSetup(all, Map.of(), Map.of(), builtIn);
    }

    private ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description("d").build();
    }

    @SuppressWarnings("unchecked")
    private List<ToolSpecification> restoreActiveSpecs(AgentOrchestrator.ToolSetup setup, boolean isLazy,
                                                       List<String> activated)
            throws Exception {
        Method m = AgentOrchestrator.class.getDeclaredMethod("restoreActiveSpecs",
                AgentOrchestrator.ToolSetup.class, boolean.class, List.class);
        m.setAccessible(true);
        return (List<ToolSpecification>) m.invoke(null, setup, isLazy, activated);
    }

    @Test
    void restoreActiveSpecs_eager_returnsAllSpecs() throws Exception {
        var all = List.of(spec("discover_tools"), spec("calculate"), spec("get_weather"));
        var setup = setupWith(all, List.of(spec("calculate"), spec("get_weather")));

        var result = restoreActiveSpecs(setup, false, List.of("calculate"));

        assertEquals(3, result.size());
    }

    @Test
    void restoreActiveSpecs_lazyEmptyActivated_returnsInitialSurface() throws Exception {
        // discover_tools + external only; built-ins hidden.
        var all = List.of(spec("discover_tools"), spec("calculate"), spec("http_tool"));
        var setup = setupWith(all, List.of(spec("calculate")));

        var result = restoreActiveSpecs(setup, true, List.of());

        // discover_tools + http_tool (external), calculate hidden.
        assertTrue(result.stream().anyMatch(s -> "discover_tools".equals(s.name())));
        assertTrue(result.stream().anyMatch(s -> "http_tool".equals(s.name())));
        assertFalse(result.stream().anyMatch(s -> "calculate".equals(s.name())));
    }

    @Test
    void restoreActiveSpecs_lazyNullActivated_returnsInitialSurface() throws Exception {
        var all = List.of(spec("discover_tools"), spec("calculate"));
        var setup = setupWith(all, List.of(spec("calculate")));

        var result = restoreActiveSpecs(setup, true, null);

        assertTrue(result.stream().anyMatch(s -> "discover_tools".equals(s.name())));
    }

    @Test
    void restoreActiveSpecs_lazyWithNames_restoresMatching() throws Exception {
        var all = List.of(spec("discover_tools"), spec("calculate"), spec("get_weather"));
        var setup = setupWith(all, List.of(spec("calculate"), spec("get_weather")));

        var result = restoreActiveSpecs(setup, true, List.of("calculate", "get_weather"));

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> "calculate".equals(s.name())));
        assertTrue(result.stream().anyMatch(s -> "get_weather".equals(s.name())));
    }

    @Test
    void restoreActiveSpecs_lazyNamesAbsent_fallsBackToInitial() throws Exception {
        var all = List.of(spec("discover_tools"), spec("calculate"), spec("http_tool"));
        var setup = setupWith(all, List.of(spec("calculate")));

        // Recorded names match nothing registered -> fallback to initial surface.
        var result = restoreActiveSpecs(setup, true, List.of("ghost_tool"));

        assertTrue(result.stream().anyMatch(s -> "discover_tools".equals(s.name())));
        assertTrue(result.stream().anyMatch(s -> "http_tool".equals(s.name())));
    }

    // ═══════════════════════════════════════════════════════════════════
    // normalizeToolCallIds (private static)
    // ═══════════════════════════════════════════════════════════════════

    private AiMessage normalizeToolCallIds(AiMessage msg, ToolApprovalsConfig cfg) throws Exception {
        Method m = AgentOrchestrator.class.getDeclaredMethod("normalizeToolCallIds", AiMessage.class, ToolApprovalsConfig.class);
        m.setAccessible(true);
        return (AiMessage) m.invoke(null, msg, cfg);
    }

    @Test
    void normalizeToolCallIds_gateInert_returnsUnchanged() throws Exception {
        var req = ToolExecutionRequest.builder().name("calculate").arguments("{}").build();
        var msg = AiMessage.from(List.of(req));

        // null config -> gate inert -> same instance
        var result = normalizeToolCallIds(msg, null);

        assertSame(msg, result);
    }

    @Test
    void normalizeToolCallIds_gateActiveButNoToolRequests_returnsUnchanged() throws Exception {
        var msg = AiMessage.from("just text");

        var result = normalizeToolCallIds(msg, gateCalculate());

        assertSame(msg, result);
    }

    @Test
    void normalizeToolCallIds_gateActiveAllIdsPresent_returnsUnchanged() throws Exception {
        var req = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build();
        var msg = AiMessage.from(List.of(req));

        var result = normalizeToolCallIds(msg, gateCalculate());

        assertSame(msg, result);
    }

    @Test
    void normalizeToolCallIds_gateActiveNullId_assignsSyntheticId() throws Exception {
        var withId = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build();
        var noId = ToolExecutionRequest.builder().name("calculate").arguments("{}").build();
        var msg = AiMessage.from(List.of(withId, noId));

        var result = normalizeToolCallIds(msg, gateCalculate());

        assertNotSame(msg, result);
        assertTrue(result.toolExecutionRequests().stream().allMatch(r -> r.id() != null));
        assertTrue(result.toolExecutionRequests().stream().anyMatch(r -> r.id().startsWith("gen-")));
    }

    @Test
    void normalizeToolCallIds_gateActiveNullIdWithText_preservesText() throws Exception {
        var noId = ToolExecutionRequest.builder().name("calculate").arguments("{}").build();
        var msg = AiMessage.from("some reasoning", List.of(noId));

        var result = normalizeToolCallIds(msg, gateCalculate());

        assertNotSame(msg, result);
        assertEquals("some reasoning", result.text());
        assertTrue(result.toolExecutionRequests().get(0).id().startsWith("gen-"));
    }

    @Test
    void normalizeToolCallIds_gateConfigEmptyRequireApproval_inert() throws Exception {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of()); // empty -> gate inert
        var noId = ToolExecutionRequest.builder().name("calculate").arguments("{}").build();
        var msg = AiMessage.from(List.of(noId));

        var result = normalizeToolCallIds(msg, cfg);

        assertSame(msg, result);
    }

    // ═══════════════════════════════════════════════════════════════════
    // buildPauseReason (private static)
    // ═══════════════════════════════════════════════════════════════════

    private String buildPauseReason(ToolApprovalsConfig cfg, ToolApprovalGate.GateResult gr) throws Exception {
        Method m = AgentOrchestrator.class.getDeclaredMethod("buildPauseReason",
                ToolApprovalsConfig.class, ToolApprovalGate.GateResult.class);
        m.setAccessible(true);
        return (String) m.invoke(null, cfg, gr);
    }

    private ToolApprovalGate.GateResult gateResult(ToolExecutionRequest... gated) {
        return new ToolApprovalGate.GateResult(List.of(gated), List.of(), Map.of());
    }

    @Test
    void buildPauseReason_nullConfig_usesDefaultTemplate() throws Exception {
        var gr = gateResult(ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build());

        String reason = buildPauseReason(null, gr);

        assertTrue(reason.contains("calculate"));
        assertTrue(reason.startsWith("Tool call requires approval"));
    }

    @Test
    void buildPauseReason_customTemplate_replacesPlaceholder() throws Exception {
        var cfg = new ToolApprovalsConfig();
        cfg.setPauseReason("Please review: {toolNames}");
        var gr = gateResult(ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build());

        String reason = buildPauseReason(cfg, gr);

        assertEquals("Please review: calculate", reason);
    }

    @Test
    void buildPauseReason_blankTemplate_fallsBackToDefault() throws Exception {
        var cfg = new ToolApprovalsConfig();
        cfg.setPauseReason("   ");
        var gr = gateResult(ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build());

        String reason = buildPauseReason(cfg, gr);

        assertTrue(reason.startsWith("Tool call requires approval"));
    }

    @Test
    void buildPauseReason_distinctNamesJoined() throws Exception {
        var gr = gateResult(
                ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("calculate").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c3").name("get_weather").arguments("{}").build());

        String reason = buildPauseReason(null, gr);

        assertTrue(reason.contains("calculate"));
        assertTrue(reason.contains("get_weather"));
        // "calculate" de-duplicated (distinct).
        assertEquals(reason.indexOf("calculate"), reason.lastIndexOf("calculate"));
    }

    @Test
    void buildPauseReason_longTemplate_cappedTo500Chars() throws Exception {
        var cfg = new ToolApprovalsConfig();
        cfg.setPauseReason("x".repeat(2000));
        var gr = gateResult(ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build());

        String reason = buildPauseReason(cfg, gr);

        assertTrue(reason.length() <= 500);
    }

    // ═══════════════════════════════════════════════════════════════════
    // buildPendingBatch — overload delegation + edge cases
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void buildPendingBatch_overloadDelegates_usesDefaultCap() {
        var gated = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"x\":1}").build();
        var gr = new ToolApprovalGate.GateResult(List.of(gated), List.of(), Map.of("c1", "calculate"));
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"), AiMessage.from(List.of(gated)));

        var batch = orchestrator.buildPendingBatch(msgs, gr, twoToolTask(), memory, 2,
                List.of(), new ArrayList<>(), 1, 5, Map.of("calculate", "builtin"), gateCalculate());

        assertNotNull(batch);
        assertEquals(5, batch.getLlmTaskIndex());
        assertEquals(2, batch.getIterationIndex());
        assertEquals(1, batch.getCalls().size());
        assertEquals("calculate", batch.getCalls().get(0).getGateReason());
        assertEquals("builtin", batch.getCalls().get(0).getSource());
        assertFalse(batch.isTranscriptOmitted());
    }

    @Test
    void buildPendingBatch_nullIdGeneratesSyntheticCallId() {
        // Request has null id -> "gen-" callId path.
        var gated = ToolExecutionRequest.builder().name("calculate").arguments("{\"x\":1}").build();
        var gr = new ToolApprovalGate.GateResult(List.of(gated), List.of(), Map.of());
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"), AiMessage.from(List.of(gated)));

        var batch = orchestrator.buildPendingBatch(msgs, gr, twoToolTask(), memory, 0,
                List.of(), new ArrayList<>(), 1, 0, Map.of(), gateCalculate());

        var call = batch.getCalls().get(0);
        assertTrue(call.getCallId().startsWith("gen-"), "null-id request must get a synthetic gen- id");
        // gateReason null because id was null (reason looked up by id).
        assertNull(call.getGateReason());
        // source falls back to "unknown" when toolSources lacks the name.
        assertEquals("unknown", call.getSource());
    }

    @Test
    void buildPendingBatch_argsTruncatedWhenOversized() {
        String big = "{\"e\":\"" + "9".repeat(PendingToolCallBatch.ARGS_RAW_MAX_BYTES + 500) + "\"}";
        var gated = ToolExecutionRequest.builder().id("c1").name("calculate").arguments(big).build();
        var gr = new ToolApprovalGate.GateResult(List.of(gated), List.of(), Map.of("c1", "calculate"));
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"), AiMessage.from(List.of(gated)));

        var batch = orchestrator.buildPendingBatch(msgs, gr, twoToolTask(), memory, 0,
                List.of(), new ArrayList<>(), 1, 0, Map.of("calculate", "builtin"), gateCalculate());

        var call = batch.getCalls().get(0);
        assertTrue(call.isArgsTruncated());
        assertTrue(call.getArgumentsRaw().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PendingToolCallBatch.ARGS_RAW_MAX_BYTES);
    }

    @Test
    void buildPendingBatch_transcriptOmittedWhenCapTiny() {
        var gated = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"x\":1}").build();
        var gr = new ToolApprovalGate.GateResult(List.of(gated), List.of(), Map.of("c1", "calculate"));
        List<ChatMessage> msgs = List.of(UserMessage.from("a long enough message"), AiMessage.from(List.of(gated)));

        var batch = orchestrator.buildPendingBatch(msgs, gr, twoToolTask(), memory, 0,
                List.of(), new ArrayList<>(), 1, 0, Map.of("calculate", "builtin"), gateCalculate(), 8);

        assertTrue(batch.isTranscriptOmitted(), "tiny cap must omit the transcript");
        assertNull(batch.getChatTranscriptJson());
    }

    @Test
    void buildPendingBatch_nullEffectiveToolApprovals_persistedAsNull() {
        var gated = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build();
        var gr = new ToolApprovalGate.GateResult(List.of(gated), List.of(), Map.of("c1", "calculate"));
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"), AiMessage.from(List.of(gated)));

        var batch = orchestrator.buildPendingBatch(msgs, gr, twoToolTask(), memory, 0,
                List.of(), new ArrayList<>(), 1, 0, Map.of("calculate", "builtin"), null);

        assertNull(batch.getEffectiveToolApprovals());
    }

    @Test
    void buildPendingBatch_recordsExecutedUngatedNames() {
        var gated = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build();
        var allowed = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{}").build();
        var gr = new ToolApprovalGate.GateResult(List.of(gated), List.of(allowed), Map.of("c1", "calculate"));
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"), AiMessage.from(List.of(gated, allowed)));

        var batch = orchestrator.buildPendingBatch(msgs, gr, twoToolTask(), memory, 0,
                List.of(), new ArrayList<>(), 1, 0, Map.of("calculate", "builtin"), gateCalculate());

        assertEquals(List.of("getCurrentDateTime"), batch.getExecutedUngatedCallNames());
        assertNotNull(batch.getFingerprint());
    }

    // ═══════════════════════════════════════════════════════════════════
    // resumeToolLoop — verdict application edge branches
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void resumeToolLoop_toolHitlDisabled_nullEffectiveConfig() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");

        ChatModel chatModel = mock(ChatModel.class);
        // Even a new gated call would NOT re-pause because toolHitlEnabled=false.
        var newGated = ToolExecutionRequest.builder().id("c2").name("calculate").arguments("{\"expression\":\"9*9\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(newGated))
                .thenReturn(text("finished"));
        when(calculatorTool.calculate("9*9")).thenReturn("81");

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), Map.of(), false);

        assertNotNull(result);
        assertEquals("finished", result.response());
        // Second gated call executed (no gate active on resume).
        verify(calculatorTool, times(1)).calculate("9*9");
    }

    @Test
    void resumeToolLoop_unknownCallIdInPerCall_usesTopVerdict() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        // per-call map only carries an unrelated id -> c1 inherits the top-level
        // REJECTED.
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.REJECTED);
        decision.setNote("blanket reject");
        decision.setDecidedBy("reviewer-1");
        var stray = new ToolCallDecision();
        stray.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setToolDecisions(Map.of("unrelated-id", stray));

        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(text("no can do"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, decision, Map.of(), true);

        assertEquals("no can do", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        boolean rejected = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .anyMatch(t -> t.contains("REJECTED_BY_REVIEWER") && t.contains("blanket reject"));
        assertTrue(rejected, "c1 must inherit the top-level REJECTED verdict");
    }

    @Test
    void resumeToolLoop_journalMissingEntryOnDuplicateClaim_outcomeUnknown() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        // claim fails AND find returns empty -> outcome-unknown (else branch of
        // prior.isPresent).
        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(false);
        when(journalStore.find("conv-1", "epoch-1", "c1")).thenReturn(Optional.empty());

        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(text("unsure"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), Map.of(), true);

        assertEquals("unsure", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        boolean unknown = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .anyMatch(t -> t.contains("EXECUTION_OUTCOME_UNKNOWN"));
        assertTrue(unknown);
    }

    @Test
    void resumeToolLoop_transcriptCodecFailure_fallsBackToRebuild() throws Exception {
        var task = twoToolTask();
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llmTask-A");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(0);
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        batch.setCalls(List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")));
        batch.setTraceSoFar(new ArrayList<>());
        // Not omitted, but the JSON is corrupt -> deserialize throws -> fallback.
        batch.setTranscriptOmitted(false);
        batch.setChatTranscriptJson("this is not valid transcript json");

        when(memory.getConversationOutputs()).thenReturn(new ArrayList<>());
        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("rebuilt"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), Map.of(), true);

        assertEquals("rebuilt", result.response());
        assertTrue(result.trace().stream()
                .anyMatch(e -> "hitl_resume".equals(e.get("type")) && Boolean.FALSE.equals(e.get("transcriptRestored"))),
                "codec failure must fall back to rebuild (transcriptRestored=false)");
    }

    @Test
    void resumeToolLoop_mergesPriorTraceSoFar() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));
        var prior = new ArrayList<Map<String, Object>>();
        prior.add(Map.of("type", "pre_pause_marker"));
        batch.setTraceSoFar(prior);

        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("merged"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), Map.of(), true);

        assertEquals("merged", result.response());
        assertTrue(result.trace().stream().anyMatch(e -> "pre_pause_marker".equals(e.get("type"))),
                "prior traceSoFar must be prepended into the merged trace");
    }

    @Test
    void resumeToolLoop_lazyStrategy_restoresActivatedNames() throws Exception {
        var task = twoToolTask();
        task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.LAZY);
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));
        // Record calculate as previously activated so LAZY restore reactivates it.
        batch.setActivatedToolNames(List.of("calculate"));

        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("lazy done"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), Map.of(), true);

        assertEquals("lazy done", result.response());
        verify(calculatorTool, times(1)).calculate("6*7");
    }

    // ═══════════════════════════════════════════════════════════════════
    // auditOutcomeUnknown — direct invocation (guard the WARN path)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void auditOutcomeUnknown_doesNotThrow() {
        var call = gatedCall("c9", "calculate", "{}");
        assertDoesNotThrow(() -> orchestrator.auditOutcomeUnknown(memory, call));
    }

    // ═══════════════════════════════════════════════════════════════════
    // buildToolSetup — external tool discovery disabled flags
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void buildToolSetup_httpAndMcpDisabled_onlyBuiltIns() {
        var task = twoToolTask();

        var setup = orchestrator.buildToolSetup(task, memory);

        assertNotNull(setup);
        // calculate + getCurrentDateTime specs present; no http/mcp discovery invoked.
        assertTrue(setup.toolSpecs().stream().anyMatch(s -> "calculate".equals(s.name())));
        verify(mcpToolProviderManager, never()).discoverTools(any());
    }

    @Test
    void buildToolSetup_noToolsEnabled_emptySpecs() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);

        var setup = orchestrator.buildToolSetup(task, memory);

        assertTrue(setup.toolSpecs().isEmpty());
        assertTrue(setup.toolExecutors().isEmpty());
    }
}
