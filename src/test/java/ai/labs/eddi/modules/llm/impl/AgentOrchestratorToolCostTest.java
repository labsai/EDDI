/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end cost accounting through the real dispatch loop, with a REAL
 * {@link ToolCostTracker} and a REAL {@link ToolExecutionService} rather than
 * mocks.
 *
 * <p>
 * This is the only test in the suite that can prove
 * {@code maxBudgetPerConversation} is capable of binding at all. Every existing
 * budget test stubs {@code isWithinBudget} to return {@code false} on a mock,
 * so the refusal it observes comes from the stub — it would pass just as
 * happily against the shipped behaviour, in which every built-in priced at
 * $0.00 and no amount of tool calling could ever move the conversation total
 * off zero.
 * </p>
 */
@DisplayName("AgentOrchestrator — real tool cost accounting")
class AgentOrchestratorToolCostTest {

    private static final String CONVERSATION_ID = "conv-cost";

    /** WebSearchTool#searchWeb costs $0.001 per call under the websearch slug. */
    private static final double SEARCH_PRICE = 0.001;

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
    private IHitlToolJournalStore journalStore;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;

    private ToolCostTracker costTracker;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Real cost/rate-limit machinery; only the cache is stubbed out (it needs an
        // ICacheFactory and contributes nothing to pricing).
        costTracker = new ToolCostTracker();
        setField(costTracker, "meterRegistry", new SimpleMeterRegistry());
        costTracker.init();

        ToolRateLimiter rateLimiter = new ToolRateLimiter();
        setField(rateLimiter, "meterRegistry", new SimpleMeterRegistry());
        rateLimiter.init();

        ToolCacheService cacheService = mock(ToolCacheService.class);
        lenient().when(cacheService.get(anyString(), anyString(), anyString())).thenReturn(null);

        ToolExecutionService toolExecutionService = new ToolExecutionService();
        setField(toolExecutionService, "cacheService", cacheService);
        setField(toolExecutionService, "rateLimiter", rateLimiter);
        setField(toolExecutionService, "costTracker", costTracker);
        setField(toolExecutionService, "meterRegistry", new SimpleMeterRegistry());
        toolExecutionService.init();

        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, tenantQuotaService,
                null,
                null, null, null, null, null,
                journalStore, new ConversationHistoryBuilder());

        lenient().when(memory.getConversationId()).thenReturn(CONVERSATION_ID);
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(toolResponseTruncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        lenient().when(tenantQuotaService.checkCostBudget(any()))
                .thenReturn(QuotaCheckResult.OK);
        lenient().when(webSearchTool.searchWeb("eddi", 3)).thenReturn("hits");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private LlmConfiguration.Task webSearchTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-cost");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("websearch"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    private static ToolExecutionRequest searchRequest() {
        return ToolExecutionRequest.builder().id("c1").name("searchWeb")
                .arguments("{\"query\":\"eddi\",\"maxResults\":3}").build();
    }

    private static ChatResponse toolBatch() {
        return ChatResponse.builder().aiMessage(AiMessage.from(searchRequest())).build();
    }

    private static ChatResponse text(String t) {
        return ChatResponse.builder().aiMessage(AiMessage.from(t)).build();
    }

    /**
     * The core regression. Before the canonical-name fix this assertion read
     * {@code 0.0} no matter how many searches ran, because the price table is keyed
     * on {@code websearch} while the live path only ever passed {@code searchWeb}.
     */
    @Test
    @DisplayName("priced built-in calls accumulate a non-zero conversation cost")
    void pricedToolCallsAccumulateCost() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch())
                .thenReturn(toolBatch())
                .thenReturn(toolBatch())
                .thenReturn(text("done"));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                webSearchTask(), memory);

        assertEquals("done", result.response());
        verify(webSearchTool, times(3)).searchWeb("eddi", 3);

        var conversationCosts = costTracker.getConversationCosts(CONVERSATION_ID);
        assertNotNull(conversationCosts, "three priced tool calls must have produced a conversation cost entry");
        assertTrue(conversationCosts.getTotalCost() > 0.0,
                "a conversation total of exactly 0.0 means every built-in still prices as an unknown tool, "
                        + "and maxBudgetPerConversation can never bind");
        assertEquals(3 * SEARCH_PRICE, conversationCosts.getTotalCost(), 1e-9);
    }

    /**
     * With real prices flowing, the ceiling finally does something — but only once
     * an operator opts in. {@code isWithinBudget} uses {@code <=} and is checked
     * BEFORE the call, so the crossing call is allowed and the next one is refused:
     * $0.0015 admits two $0.001 searches and blocks the third.
     */
    @Test
    @DisplayName("enforceBudget stops tool calls once the accumulated real cost passes the ceiling")
    void enforcedBudgetBlocksOnRealAccumulatedCost() throws Exception {
        var task = webSearchTask();
        task.setMaxBudgetPerConversation(0.0015);
        task.setEnforceBudget(true);
        task.setMaxToolIterations(5);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch());

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        verify(webSearchTool, times(2)).searchWeb("eddi", 3);
        assertEquals(2 * SEARCH_PRICE, costTracker.getConversationCosts(CONVERSATION_ID).getTotalCost(), 1e-9);
        assertTrue(result.trace().stream()
                .anyMatch(e -> "tool_error".equals(e.get("type"))
                        && String.valueOf(e.get("error")).contains("Budget exceeded")),
                "the third call must be refused by the accumulated cost, not by a stub");
    }

    /**
     * Same ceiling, same spend, no {@code enforceBudget} — every call runs. This is
     * what protects agents whose stored config has carried an inert
     * {@code maxBudgetPerConversation} since before prices resolved.
     */
    @Test
    @DisplayName("without enforceBudget the same ceiling refuses nothing")
    void unenforcedBudgetRefusesNothing() throws Exception {
        var task = webSearchTask();
        task.setMaxBudgetPerConversation(0.0015);
        task.setMaxToolIterations(5);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch());

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        verify(webSearchTool, times(5)).searchWeb("eddi", 3);
        assertTrue(result.trace().stream()
                .noneMatch(e -> String.valueOf(e.get("error")).contains("Budget exceeded")),
                "an unenforced ceiling must never refuse a call, however far the real cost exceeds it");
    }

    /**
     * {@code toolPricing} is keyed on the same slugs as
     * {@code builtInToolsWhitelist}, and it reaches the tracker through the same
     * canonical resolution as the default table.
     */
    @Test
    @DisplayName("a toolPricing override changes the accumulated cost")
    void toolPricingOverrideChangesAccumulatedCost() throws Exception {
        var task = webSearchTask();
        task.setToolPricing(Map.of("websearch", 0.05));

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch())
                .thenReturn(text("done"));

        orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertEquals(0.05, costTracker.getConversationCosts(CONVERSATION_ID).getTotalCost(), 1e-9);
    }

    /**
     * An operator-supplied negative price must not credit the conversation — that
     * would make any budget ceiling unreachable by design.
     */
    @Test
    @DisplayName("a negative toolPricing value is clamped rather than credited")
    void negativeToolPricingIsClamped() throws Exception {
        var task = webSearchTask();
        task.setToolPricing(Map.of("websearch", -1.0));

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch())
                .thenReturn(text("done"));

        orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertEquals(0.0, costTracker.getConversationCosts(CONVERSATION_ID).getTotalCost(), 1e-9);
    }
}
