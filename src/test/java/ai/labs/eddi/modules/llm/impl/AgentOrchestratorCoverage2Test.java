/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ConversationRecallTool;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.UserMemoryTool;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Additional branch-coverage tests for {@link AgentOrchestrator}, targeting
 * branches NOT exercised by {@code AgentOrchestratorTest},
 * {@code AgentOrchestratorCoverageTest},
 * {@code AgentOrchestratorToolPauseTest},
 * {@code AgentOrchestratorResumeToolLoopTest}, or the Extended/Branch tests:
 * <ul>
 * <li>{@code buildToolSetup} source-tagging arms for non-builtin tools
 * (usermemory→"memory", conversationRecall→"recall") via
 * {@code sourceForBuiltInTool}</li>
 * <li>LAZY {@code discover_tools} live-loop activation
 * ({@code activateDiscoveredTools} reached through
 * {@code executeSingleToolCallResult})</li>
 * <li>{@code maxPausesPerTurn} clamping (below 1, above 10, exact config,
 * null-config default) via reflection</li>
 * <li>{@code activatedToolNames} EAGER-empty vs LAZY-names via reflection</li>
 * <li>multiple allowed calls in one batch both dispatched (loop body iterated
 * more than once)</li>
 * <li>{@code readToolPauseCount} null-step and null-data guards via the pause
 * path with no pre-seeded count</li>
 * <li>{@code fingerprint} stability via reflection</li>
 * </ul>
 * Wiring copied verbatim from the sibling tests so it compiles + passes by
 * construction.
 */
class AgentOrchestratorCoverage2Test {

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
    @Mock
    private IConversationMemory.IConversationProperties conversationProperties;

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
                journalStore, historyBuilder, new TokenCounterFactory());

        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        lenient().when(memory.getAgentId()).thenReturn(null);
        lenient().when(memory.getAgentVersion()).thenReturn(null);
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(toolResponseTruncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        lenient().when(tenantQuotaService.checkCostBudget(any())).thenReturn(QuotaCheckResult.OK);
        lenient()
                .when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(),
                        any(Supplier.class),
                        anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(inv -> {
                    Supplier<String> sup = inv.getArgument(4);
                    return sup.get();
                });
    }

    // ─── helpers (mirroring the sibling coverage test) ───

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

    private ChatResponse text(String s) {
        return ChatResponse.builder().aiMessage(AiMessage.from(s)).build();
    }

    private ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description("d").build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // buildToolSetup — sourceForBuiltInTool arms for non-"builtin" tools
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void buildToolSetup_userMemoryTool_taggedAsMemorySource() {
        // usermemory whitelisted + UserMemoryConfig present -> UserMemoryTool is
        // registered; its @Tool methods must be tagged with the "memory" source.
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("usermemory"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);

        var config = new AgentConfiguration.UserMemoryConfig();
        when(memory.getUserMemoryConfig()).thenReturn(config);
        when(memory.getConversationProperties()).thenReturn(conversationProperties);
        when(memory.getUserId()).thenReturn("user-1");
        when(conversationProperties.get("groupId")).thenReturn(null);

        var setup = orchestrator.buildToolSetup(task, memory);

        assertFalse(setup.toolSources().isEmpty(), "UserMemoryTool must register at least one @Tool");
        assertTrue(setup.toolSources().values().stream().anyMatch("memory"::equals),
                "UserMemoryTool @Tool methods must be tagged with the 'memory' source");
    }

    @Test
    void buildToolSetup_conversationRecallTool_taggedAsRecallSource() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("conversationRecall"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);

        var summaryConfig = new LlmConfiguration.ConversationSummaryConfig();
        summaryConfig.setEnabled(true);
        task.setConversationSummary(summaryConfig);

        when(memory.getConversationProperties()).thenReturn(conversationProperties);
        var summaryProp = new Property("conversation:running_summary", "prev", Property.Scope.conversation);
        when(conversationProperties.get("conversation:running_summary")).thenReturn(summaryProp);
        var throughStepProp = new Property("conversation:summary_through_step", 3, Property.Scope.conversation);
        when(conversationProperties.get("conversation:summary_through_step")).thenReturn(throughStepProp);
        when(memory.getConversationOutputs()).thenReturn(List.of(new ConversationOutput()));

        var setup = orchestrator.buildToolSetup(task, memory);

        assertTrue(setup.toolSources().values().stream().anyMatch("recall"::equals),
                "ConversationRecallTool @Tool methods must be tagged with the 'recall' source");
    }

    @Test
    void buildToolSetup_builtInTools_taggedAsBuiltinSource() {
        // Default switch arm: plain built-ins map to "builtin".
        var setup = orchestrator.buildToolSetup(twoToolTask(), memory);

        assertTrue(setup.toolSources().containsKey("calculate"));
        assertEquals("builtin", setup.toolSources().get("calculate"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // LAZY discover_tools live-loop activation
    // (executeSingleToolCallResult -> activateDiscoveredTools branch)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void lazyLiveLoop_discoverToolsActivatesBuiltInSpec() throws Exception {
        var task = twoToolTask();
        task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.LAZY);

        ChatModel chatModel = mock(ChatModel.class);
        // Turn 1: model calls the discover_tools meta-tool (the only initially-active
        // built-in spec in LAZY mode). The real DiscoverToolsTool returns JSON listing
        // "calculate" -> activateDiscoveredTools promotes the calculate spec.
        var discoverReq = ToolExecutionRequest.builder().id("d1").name("discover_tools")
                .arguments("{\"category\":\"\",\"keywords\":\"calc\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(discoverReq))
                .thenReturn(text("discovered"));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys",
                List.of(UserMessage.from("what can you do")), task, memory);

        assertNotNull(result);
        assertEquals("discovered", result.response());
        // The trace records the discover_tools call+result (activation path executed).
        boolean discovered = result.trace().stream()
                .anyMatch(e -> "tool_result".equals(e.get("type")) && "discover_tools".equals(e.get("tool")));
        assertTrue(discovered, "discover_tools must run and drive LAZY activation");
    }

    // ═══════════════════════════════════════════════════════════════════
    // maxPausesPerTurn — clamping arms (private static, reflection)
    // ═══════════════════════════════════════════════════════════════════

    private int maxPausesPerTurn(ToolApprovalsConfig cfg) throws Exception {
        Method m = AgentOrchestrator.class.getDeclaredMethod("maxPausesPerTurn", ToolApprovalsConfig.class);
        m.setAccessible(true);
        return (int) m.invoke(null, cfg);
    }

    @Test
    void maxPausesPerTurn_nullConfig_returnsDefault() throws Exception {
        assertEquals(3, maxPausesPerTurn(null));
    }

    @Test
    void maxPausesPerTurn_nullValue_returnsDefault() throws Exception {
        var cfg = new ToolApprovalsConfig();
        cfg.setMaxPausesPerTurn(null);
        assertEquals(3, maxPausesPerTurn(cfg));
    }

    @Test
    void maxPausesPerTurn_belowOne_clampedToOne() throws Exception {
        var cfg = new ToolApprovalsConfig();
        cfg.setMaxPausesPerTurn(0);
        assertEquals(1, maxPausesPerTurn(cfg));
    }

    @Test
    void maxPausesPerTurn_aboveTen_clampedToTen() throws Exception {
        var cfg = new ToolApprovalsConfig();
        cfg.setMaxPausesPerTurn(999);
        assertEquals(10, maxPausesPerTurn(cfg));
    }

    @Test
    void maxPausesPerTurn_withinRange_usedVerbatim() throws Exception {
        var cfg = new ToolApprovalsConfig();
        cfg.setMaxPausesPerTurn(5);
        assertEquals(5, maxPausesPerTurn(cfg));
    }

    // ═══════════════════════════════════════════════════════════════════
    // activatedToolNames — EAGER-empty vs LAZY-names (private static)
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<String> activatedToolNames(boolean isLazy, List<ToolSpecification> activeSpecs) throws Exception {
        Method m = AgentOrchestrator.class.getDeclaredMethod("activatedToolNames", boolean.class, List.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, isLazy, activeSpecs);
    }

    @Test
    void activatedToolNames_eager_returnsEmpty() throws Exception {
        var result = activatedToolNames(false, List.of(spec("calculate"), spec("get_weather")));
        assertTrue(result.isEmpty(), "EAGER records no activated names");
    }

    @Test
    void activatedToolNames_lazy_returnsSpecNames() throws Exception {
        var result = activatedToolNames(true, List.of(spec("discover_tools"), spec("calculate")));
        assertEquals(List.of("discover_tools", "calculate"), result);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Multi-tool per turn: two ALLOWED calls both dispatch (gate inert)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void multiTool_bothAllowedCalls_executeInOneBatch() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);

        var calcReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1+1\"}").build();
        var dtReq = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        // Gate inert (no config) -> both calls are "allowed" and both dispatch.
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(calcReq, dtReq))
                .thenReturn(text("both ran"));
        when(calculatorTool.calculate("1+1")).thenReturn("2");
        when(dateTimeTool.getCurrentDateTime("UTC")).thenReturn("now");

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertEquals("both ran", result.response());
        verify(calculatorTool, times(1)).calculate("1+1");
        verify(dateTimeTool, times(1)).getCurrentDateTime("UTC");
    }

    // ═══════════════════════════════════════════════════════════════════
    // readToolPauseCount guards: null-step / null-data (no pre-seeded count)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void toolPause_noPreSeededCount_nullData_treatedAsZero_pauses() throws Exception {
        // getLatestData(hitl:tool_pause_count) returns null -> readToolPauseCount = 0
        // -> 0 < default cap (3) -> the gate PAUSES (throws) instead of failing closed.
        var task = twoToolTask();
        when(currentStep.getLatestData("hitl:tool_pause_count")).thenReturn(null);

        ChatModel chatModel = mock(ChatModel.class);
        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq));

        assertThrows(ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                        task, memory, gateCalculate(), 0));

        // Pause count is written (incremented from 0).
        verify(currentStep).storeData(any());
        verify(calculatorTool, never()).calculate(anyString());
    }

    @Test
    void toolPause_pauseCountDataResultNull_treatedAsZero_pauses() throws Exception {
        // Data present but getResult() null -> the (data==null || result==null) guard
        // takes the null-result arm -> count 0 -> pauses.
        var task = twoToolTask();
        @SuppressWarnings("unchecked")
        ai.labs.eddi.engine.memory.IData<Integer> data = mock(ai.labs.eddi.engine.memory.IData.class);
        when(data.getResult()).thenReturn(null);
        doReturn(data).when(currentStep).getLatestData("hitl:tool_pause_count");

        ChatModel chatModel = mock(ChatModel.class);
        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq));

        assertThrows(ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                        task, memory, gateCalculate(), 0));

        verify(calculatorTool, never()).calculate(anyString());
    }

    // ═══════════════════════════════════════════════════════════════════
    // fingerprint (private static) — deterministic over sorted gated calls
    // ═══════════════════════════════════════════════════════════════════

    private String fingerprint(List<ToolExecutionRequest> gated) throws Exception {
        Method m = AgentOrchestrator.class.getDeclaredMethod("fingerprint", List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, gated);
    }

    @Test
    void fingerprint_orderIndependent_andHex64() throws Exception {
        var a = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"x\":1}").build();
        var b = ToolExecutionRequest.builder().id("c2").name("get_weather").arguments("{\"city\":\"V\"}").build();

        String fp1 = fingerprint(List.of(a, b));
        String fp2 = fingerprint(List.of(b, a));

        assertEquals(fp1, fp2, "fingerprint sorts parts -> order independent");
        assertEquals(64, fp1.length(), "SHA-256 hex is 64 chars");
        assertTrue(fp1.matches("[0-9a-f]{64}"));
    }

    @Test
    void fingerprint_nullArguments_handledAsEmpty() throws Exception {
        var a = ToolExecutionRequest.builder().id("c1").name("calculate").build();
        // Must not throw on a null-arguments request (the ?: empty-string arm).
        String fp = fingerprint(List.of(a));
        assertEquals(64, fp.length());
    }

    // ═══════════════════════════════════════════════════════════════════
    // buildToolSetup — mcp enabled default but discovery finds nothing
    // (enableMcpCallTools == null -> defaults true -> discoverMcpCallTools runs)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void buildToolSetup_mcpDefaultEnabled_discoveryEmpty_noMcpSpecsMerged() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(null); // default true -> discoverMcpCallTools invoked

        var setup = orchestrator.buildToolSetup(task, memory);

        // Only the calculate built-in; MCP discovery via WorkflowTraversal finds no
        // config (restAgentStore returns null) -> nothing merged.
        assertTrue(setup.toolSources().values().stream().allMatch("builtin"::equals));
        assertTrue(setup.toolSpecs().stream().anyMatch(s -> "calculate".equals(s.name())));
    }

    @Test
    void buildToolSetup_a2aAgentsPresent_discoveryInvoked() {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        var a2a = new LlmConfiguration.A2AAgentConfig();
        a2a.setUrl("http://peer.example/agent");
        task.setA2aAgents(List.of(a2a));

        // Discovery returns an empty result -> the a2aTools != null but empty-specs
        // arm (merge skipped) executes.
        when(a2aToolProviderManager.discoverTools(any()))
                .thenReturn(new A2AToolProviderManager.A2AToolsResult(List.of(), Map.of()));

        var setup = orchestrator.buildToolSetup(task, memory);

        verify(a2aToolProviderManager, times(1)).discoverTools(any());
        assertTrue(setup.toolSpecs().stream().anyMatch(s -> "calculate".equals(s.name())));
    }
}
