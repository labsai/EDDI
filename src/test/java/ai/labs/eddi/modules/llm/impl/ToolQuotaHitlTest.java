/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import ai.labs.eddi.modules.llm.tools.quota.InMemoryToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaKey;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaRules;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.config;
import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code toolQuotas} and tool-level human approval, through the real
 * {@link ToolExecutionService}: the pre-check that refuses an exhausted gated
 * call before a human is asked, and the quota applied at execution time to a
 * call a human approved.
 */
@DisplayName("toolQuotas x tool-level HITL")
class ToolQuotaHitlTest {

    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final long DAY_START = ToolQuotaRules.windowStart(NOW, Duration.ofDays(1));

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
    private IWorkflowStore restWorkflowStore;
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

    private InMemoryToolQuotaStore store;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        ToolCacheService cache = new ToolCacheService();
        setField(cache, "cacheFactory", new CacheFactory());
        setField(cache, "meterRegistry", new SimpleMeterRegistry());
        cache.init();
        ToolRateLimiter rateLimiter = new ToolRateLimiter();
        setField(rateLimiter, "meterRegistry", new SimpleMeterRegistry());
        rateLimiter.init();
        ToolCostTracker costTracker = new ToolCostTracker();
        setField(costTracker, "meterRegistry", new SimpleMeterRegistry());
        costTracker.init();

        store = new InMemoryToolQuotaStore();
        when(memoryItemConverter.convert(any())).thenReturn(Map.of());
        var quotaService = new ToolQuotaService(store, mock(ITemplatingEngine.class), memoryItemConverter, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ToolExecutionService toolExecutionService = new ToolExecutionService();
        setField(toolExecutionService, "cacheService", cache);
        setField(toolExecutionService, "rateLimiter", rateLimiter);
        setField(toolExecutionService, "costTracker", costTracker);
        setField(toolExecutionService, "meterRegistry", new SimpleMeterRegistry());
        setField(toolExecutionService, "quotaService", quotaService);
        toolExecutionService.init();

        orchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool, textSummarizerTool,
                pdfReaderTool, weatherTool, fetchToolResponsePageTool, toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restWorkflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization, memoryItemConverter, userMemoryStore,
                toolResponseTruncator, tenantQuotaService, memorySnapshotService, null, null, null, null, null, journalStore,
                new ConversationHistoryBuilder(), new TokenCounterFactory());

        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getUserId()).thenReturn("user-1");
        when(memory.getAgentVersion()).thenReturn(null);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(toolResponseTruncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any())).thenReturn(QuotaCheckResult.OK);
        when(journalStore.tryClaim(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ToolQuotaKey calculateKey() {
        return new ToolQuotaKey(AGENT, "tool:calculate", "user-1", DAY_START);
    }

    private LlmConfiguration.Task gatedCalculatorTask(int quota) {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        task.setToolQuotas(config("calculate", rule(quota, "P1D", null)));
        return task;
    }

    private ToolApprovalsConfig gateCalculate() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("calculate"));
        return cfg;
    }

    private static ToolExecutionRequest calc(String id, String expression) {
        return ToolExecutionRequest.builder().id(id).name("calculate").arguments("{\"expression\":\"" + expression + "\"}").build();
    }

    private static ChatResponse text(String s) {
        return ChatResponse.builder().aiMessage(AiMessage.from(s)).build();
    }

    private static ChatResponse toolBatch(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    private PendingToolCallBatch pending(List<ToolExecutionRequest> calls) {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llmTask-A");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(0);
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        batch.setAutoApproveCount(0);
        List<PendingToolCallBatch.PendingToolCall> gated = new ArrayList<>();
        for (ToolExecutionRequest call : calls) {
            var c = new PendingToolCallBatch.PendingToolCall();
            c.setCallId(call.id());
            c.setToolName(call.name());
            c.setSource("builtin");
            c.setArgumentsRaw(call.arguments());
            c.setArgsTruncated(false);
            gated.add(c);
        }
        batch.setCalls(gated);
        batch.setTraceSoFar(new ArrayList<>());
        List<ChatMessage> transcript = new ArrayList<>();
        transcript.add(UserMessage.from("improve it"));
        transcript.add(AiMessage.from(calls));
        var res = new ChatTranscriptCodec().serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        batch.setChatTranscriptJson(res.json());
        batch.setTranscriptOmitted(res.omitted());
        return batch;
    }

    private static HitlDecision approveAll() {
        var d = new HitlDecision();
        d.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        d.setDecidedBy("reviewer-1");
        return d;
    }

    /** Every tool result the model was shown across all of the loop's requests. */
    private static List<String> toolResultsSeen(ChatModel chatModel) {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, atLeastOnce()).chat(captor.capture());
        List<String> seen = new ArrayList<>();
        for (ChatMessage message : captor.getValue().messages()) {
            if (message instanceof ToolExecutionResultMessage result) {
                seen.add(result.text());
            }
        }
        return seen;
    }

    // ─── pre-check: the human is not asked to approve what will be refused ───

    @Test
    @DisplayName("a gated call whose quota is already spent is refused at once — no pause, no approval asked")
    void exhaustedGatedCallIsRefusedInsteadOfPausing() throws Exception {
        store.set(calculateKey(), 3);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(calc("c1", "6*7")), text("I could not do that."));

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), gatedCalculatorTask(3), memory,
                gateCalculate(), 0);

        assertEquals("I could not do that.", result.response(), "the loop carried on instead of pausing");
        verify(memory, never()).setHitlPendingToolCalls(any());
        verify(calculatorTool, never()).calculate(anyString());
        var seen = toolResultsSeen(chatModel);
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).contains("Not run: the daily limit for this tool (3) is reached"), seen.get(0));
        assertEquals(3, store.peek(calculateKey()), "the refusal counted nothing");
    }

    @Test
    @DisplayName("with a slot left the call still goes to a human, and pausing counts nothing")
    void gatedCallWithQuotaLeftStillPauses() {
        store.set(calculateKey(), 2);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(calc("c1", "6*7")));

        assertThrows(ToolApprovalRequiredException.class, () -> orchestrator.executeIfToolsEnabled(chatModel, "sys",
                List.of(UserMessage.from("hi")), gatedCalculatorTask(3), memory, gateCalculate(), 0));

        verify(calculatorTool, never()).calculate(anyString());
        assertEquals(2, store.peek(calculateKey()), "a call waiting for approval has not run and is not counted");
    }

    @Test
    @DisplayName("the pre-check fails closed: a counter store that cannot answer refuses the gated call too")
    void precheckFailsClosed() throws Exception {
        store.failing = true;
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(calc("c1", "6*7")), text("sorry"));

        orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), gatedCalculatorTask(3), memory, gateCalculate(), 0);

        verify(calculatorTool, never()).calculate(anyString());
        assertTrue(toolResultsSeen(chatModel).get(0).contains("could not be checked"));
    }

    // ─── resume: the quota applies to an approved call at execution time ───

    @Test
    @DisplayName("an approved call that runs on resume is counted")
    void approvedCallIsCounted() throws Exception {
        when(calculatorTool.calculate("6*7")).thenReturn("42");
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("42"));
        var call = calc("c1", "6*7");

        orchestrator.resumeToolLoop(chatModel, gatedCalculatorTask(3), memory, pending(List.of(call)), approveAll(), true);

        verify(calculatorTool, times(1)).calculate("6*7");
        assertEquals(1, store.peek(calculateKey()));
    }

    @Test
    @DisplayName("an approved call is refused on resume when the quota ran out while it waited")
    void approvedCallIsRefusedWhenQuotaRanOutMeanwhile() throws Exception {
        // the human took their time; the allowance was spent in another conversation
        store.set(calculateKey(), 3);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("I could not run it."));
        var call = calc("c1", "6*7");

        orchestrator.resumeToolLoop(chatModel, gatedCalculatorTask(3), memory, pending(List.of(call)), approveAll(), true);

        verify(calculatorTool, never()).calculate(anyString());
        var seen = toolResultsSeen(chatModel);
        assertTrue(seen.stream().anyMatch(r -> r.contains("Not run: the daily limit for this tool (3) is reached")), seen.toString());
        assertEquals(3, store.peek(calculateKey()));
    }

    @Test
    @DisplayName("two approved calls with one slot left: exactly one runs, the other is refused")
    void twoApprovedCallsOneSlot() throws Exception {
        store.set(calculateKey(), 2);
        when(calculatorTool.calculate(anyString())).thenReturn("42");
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("done"));

        orchestrator.resumeToolLoop(chatModel, gatedCalculatorTask(3), memory, pending(List.of(calc("c1", "6*7"), calc("c2", "2+2"))),
                approveAll(), true);

        verify(calculatorTool, times(1)).calculate(anyString());
        assertEquals(3, store.peek(calculateKey()));
        var seen = toolResultsSeen(chatModel);
        assertEquals(1, seen.stream().filter(r -> r.contains("Not run: the daily limit")).count(), seen.toString());
    }

    @Test
    @DisplayName("a rejected call is never counted")
    void rejectedCallIsNotCounted() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("ok, not doing it"));
        var reject = new HitlDecision();
        reject.setVerdict(HitlDecision.HitlVerdict.REJECTED);
        reject.setDecidedBy("reviewer-1");

        orchestrator.resumeToolLoop(chatModel, gatedCalculatorTask(3), memory, pending(List.of(calc("c1", "6*7"))), reject, true);

        verify(calculatorTool, never()).calculate(anyString());
        assertEquals(0, store.peek(calculateKey()));
        verify(journalStore, never()).markExecuted(anyString(), anyString(), eq("c1"), anyString());
    }
}
