/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.shared.RetryConfiguration;
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
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
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
                journalStore, new ConversationHistoryBuilder(), new TokenCounterFactory());

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
     * With real prices flowing, the ceiling finally does something.
     * {@code isWithinBudget} uses {@code <=} and is checked BEFORE the call, so the
     * crossing call is allowed and the next one is refused: $0.0015 admits two
     * $0.001 searches and blocks the third.
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
     * D4: the same ceiling with no {@code enforceBudget} refuses nothing, against
     * real accumulated cost.
     * <p>
     * Enforcement is opt-in because built-in tools priced at $0.00 until this
     * release — the sibling test above proves the ceiling genuinely binds once the
     * flag is set, so what this pins is that an upgrade does not start refusing
     * calls on its own. The operator whose ceiling <em>was</em> live (tools
     * dispatching under a priced name) is told so by the WARN, not by a surprise
     * refusal; see
     * {@code AgentOrchestratorCoverageTest.toolCall_unenforcedCeilingIsWarnedAbout}.
     */
    @Test
    @DisplayName("a ceiling with no enforceBudget flag refuses nothing")
    void ceilingWithoutFlagIsNotEnforced() throws Exception {
        var task = webSearchTask();
        task.setMaxBudgetPerConversation(0.0015);
        task.setMaxToolIterations(5);
        // deliberately no setEnforceBudget(...)

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch());

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        verify(webSearchTool, times(5)).searchWeb("eddi", 3);
        assertTrue(result.trace().stream()
                .noneMatch(e -> String.valueOf(e.get("error")).contains("Budget exceeded")),
                "an unflagged ceiling must refuse nothing — enforcement is opt-in");
    }

    /**
     * Same ceiling, same spend, {@code enforceBudget: false} — every call runs.
     * Explicitly opting out must behave identically to leaving the flag unset.
     */
    @Test
    @DisplayName("enforceBudget:false makes the same ceiling refuse nothing")
    void explicitlyUnenforcedBudgetRefusesNothing() throws Exception {
        var task = webSearchTask();
        task.setMaxBudgetPerConversation(0.0015);
        task.setEnforceBudget(false);
        task.setMaxToolIterations(5);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch());

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertNotNull(result);
        verify(webSearchTool, times(5)).searchWeb("eddi", 3);
        assertTrue(result.trace().stream()
                .noneMatch(e -> String.valueOf(e.get("error")).contains("Budget exceeded")),
                "an explicitly unenforced ceiling must never refuse a call, however far the real cost exceeds it");
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
     * The audit ledger's dollar figure is assembled from this metadata key. It has
     * to be the cost THIS model call added, not the conversation running total —
     * otherwise a turn with several LLM tasks bills the earlier tasks' tools again
     * on every later one.
     */
    @Test
    @DisplayName("responseMetadata carries the tool-cost delta of this call, not the conversation total")
    void responseMetadataCarriesToolCostDelta() throws Exception {
        ChatModel firstCall = mock(ChatModel.class);
        when(firstCall.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch())
                .thenReturn(toolBatch())
                .thenReturn(text("done"));

        var first = orchestrator.executeIfToolsEnabled(firstCall, "sys", List.of(UserMessage.from("hi")),
                webSearchTask(), memory);
        assertEquals(2 * SEARCH_PRICE, (Double) first.responseMetadata().get("toolCostUsd"), 1e-9);

        // Second call on the SAME conversation: the tracker total is now 3 searches,
        // but this call only added one.
        ChatModel secondCall = mock(ChatModel.class);
        when(secondCall.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch())
                .thenReturn(text("done"));

        var second = orchestrator.executeIfToolsEnabled(secondCall, "sys", List.of(UserMessage.from("hi")),
                webSearchTask(), memory);
        assertEquals(3 * SEARCH_PRICE, costTracker.getConversationCosts(CONVERSATION_ID).getTotalCost(), 1e-9);
        assertEquals(SEARCH_PRICE, (Double) second.responseMetadata().get("toolCostUsd"), 1e-9);
    }

    @Test
    @DisplayName("toolCostUsd is 0.0 when the conversation was never cost-tracked")
    void toolCostDeltaIsZeroWhenNothingTracked() throws Exception {
        // Tools are offered but the model never calls one, so ToolCostTracker has no
        // entry for this conversation and getConversationCosts returns null.
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("done"));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                webSearchTask(), memory);

        assertNotNull(result);
        assertEquals(0.0, (Double) result.responseMetadata().get("toolCostUsd"), 1e-9);
    }

    /**
     * {@code RetryConfiguration.executeWithRetry} replays the whole tool-call loop
     * lambda, and the token accumulator lives outside it. Without an explicit reset
     * the discarded attempt's tokens stay on the total, so a retried turn reports
     * (and, once prices are configured, bills) roughly double what it used.
     */
    @Test
    @DisplayName("a retried tool loop reports one attempt's tokens, not both")
    void retriedLoopDoesNotDoubleCountTokens() throws Exception {
        var task = webSearchTask();
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(2);
        retry.setBackoffDelayMs(1L);
        task.setRetry(retry);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                // attempt 1: one accounted model call, then a retryable failure
                .thenReturn(usage(toolBatch(), 10, 20, 30))
                .thenThrow(new RuntimeException("rate limit exceeded"))
                // attempt 2: replayed from scratch
                .thenReturn(usage(toolBatch(), 10, 20, 30))
                .thenReturn(usage(text("done"), 1, 2, 3));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);

        assertEquals("done", result.response());
        @SuppressWarnings("unchecked")
        var tokenUsage = (Map<String, Object>) result.responseMetadata().get("tokenUsage");
        assertNotNull(tokenUsage);
        assertEquals(11, tokenUsage.get("inputTokens"), "the abandoned attempt's 10 input tokens must not be counted");
        assertEquals(22, tokenUsage.get("outputTokens"));
        assertEquals(33, tokenUsage.get("totalTokens"));
    }

    private static ChatResponse usage(ChatResponse response, int in, int out, int total) {
        return ChatResponse.builder().aiMessage(response.aiMessage())
                .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(in, out, total)).build())
                .build();
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
