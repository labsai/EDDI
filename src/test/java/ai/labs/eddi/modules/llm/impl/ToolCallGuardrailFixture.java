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
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.guardrails.GuardrailUndecidedException;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.modules.llm.guardrails.PaidCallGate;
import ai.labs.eddi.modules.llm.guardrails.impl.DisclosureGuardrail;
import ai.labs.eddi.modules.llm.guardrails.impl.RegexFilterGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolCallGuardrailConfig;
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
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A directly constructed orchestrator with the real execution pipeline and a
 * real {@link ToolCallGuardrails}, for the live and the resume tests. The tool
 * is the built-in calculator ({@code builtin:calculate}, argument
 * {@code /expression}).
 */
final class ToolCallGuardrailFixture {

    static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";

    final CalculatorTool calculatorTool = mock(CalculatorTool.class);
    final IHitlToolJournalStore journalStore = mock(IHitlToolJournalStore.class);
    final TenantQuotaService tenantQuotaService = mock(TenantQuotaService.class);
    final IConversationMemory memory = mock(IConversationMemory.class);
    final IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
    final ConversationEventSink sink = mock(ConversationEventSink.class);
    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final ModelBacked modelBacked = new ModelBacked();
    final ToolCostTracker costTracker = new ToolCostTracker();
    final AgentOrchestrator orchestrator;

    /**
     * A model-backed guardrail (it does not re-screen history): asks the spend
     * gate, reports {@link #COST} spent, and flags text containing "claim".
     */
    static final class ModelBacked implements ContentGuardrail {
        static final double COST = 0.25;
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "fake-judge";
        }

        @Override
        public Set<GuardrailPhase> phases() {
            return Set.of(GuardrailPhase.TOOL_CALL);
        }

        @Override
        public boolean screensHistory() {
            return false;
        }

        @Override
        public boolean callsModel() {
            return true;
        }

        @Override
        public GuardrailVerdict evaluate(String text, GuardrailContext context) {
            calls.incrementAndGet();
            try (PaidCallGate.PaidCall paid = context.spend().begin(COST)) {
                if (!paid.admitted()) {
                    throw new GuardrailUndecidedException(PaidCallGate.REASON_SPEND_REFUSED, 0.0);
                }
                paid.spent(COST);
            }
            return text.contains("claim") ? GuardrailVerdict.violation("unapproved_claim") : GuardrailVerdict.allow();
        }
    }

    ToolCallGuardrailFixture() throws Exception {
        ToolCacheService cache = new ToolCacheService();
        setField(cache, "cacheFactory", new CacheFactory());
        setField(cache, "meterRegistry", new SimpleMeterRegistry());
        cache.init();
        ToolRateLimiter rateLimiter = new ToolRateLimiter();
        setField(rateLimiter, "meterRegistry", new SimpleMeterRegistry());
        rateLimiter.init();
        setField(costTracker, "meterRegistry", new SimpleMeterRegistry());
        costTracker.init();
        ToolExecutionService toolExecutionService = new ToolExecutionService();
        setField(toolExecutionService, "cacheService", cache);
        setField(toolExecutionService, "rateLimiter", rateLimiter);
        setField(toolExecutionService, "costTracker", costTracker);
        setField(toolExecutionService, "meterRegistry", new SimpleMeterRegistry());
        toolExecutionService.init();

        IMemoryItemConverter converter = mock(IMemoryItemConverter.class);
        lenient().when(converter.convert(any())).thenReturn(Map.of());
        ToolResponseTruncator truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));

        orchestrator = new AgentOrchestrator(calculatorTool, mock(DateTimeTool.class), mock(WebSearchTool.class), mock(DataFormatterTool.class),
                mock(WebScraperTool.class), mock(TextSummarizerTool.class), mock(PdfReaderTool.class), mock(WeatherTool.class),
                mock(FetchToolResponsePageTool.class), toolExecutionService, mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class),
                mock(IWorkflowStore.class), mock(IResourceClientLibrary.class), mock(IApiCallExecutor.class), mock(IJsonSerialization.class),
                converter, mock(IUserMemoryStore.class), truncator, tenantQuotaService, mock(MemorySnapshotService.class), null, null, null, null,
                null, journalStore, new ConversationHistoryBuilder(), new TokenCounterFactory());
        var guardrailService = new GuardrailService(List.of(new RegexFilterGuardrail(), new DisclosureGuardrail(), modelBacked), meterRegistry);
        orchestrator.setToolCallGuardrails(new ToolCallGuardrails(guardrailService, meterRegistry));

        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        lenient().when(memory.getAgentId()).thenReturn(AGENT);
        lenient().when(memory.getUserId()).thenReturn("user-1");
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(memory.getEventSink()).thenReturn(sink);
        lenient().when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        lenient().when(tenantQuotaService.checkCostBudget(any(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        lenient().when(journalStore.tryClaim(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        lenient().when(calculatorTool.calculate(anyString())).thenReturn("42");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static LlmConfiguration.Task task(ToolCallGuardrailConfig... entries) {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        task.setToolCallGuardrails(entries.length == 0 ? null : List.of(entries));
        return task;
    }

    static ToolCallGuardrailConfig entry(String type, String action, String... parameters) {
        var entry = new ToolCallGuardrailConfig();
        entry.setType(type);
        entry.setAction(action);
        entry.setTools(List.of("builtin:calculate"));
        entry.setFields(List.of("/expression"));
        if (parameters.length % 2 != 0) {
            throw new IllegalArgumentException("entry expects key/value parameter pairs, got " + parameters.length + " arguments");
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < parameters.length; i += 2) {
            params.put(parameters[i], parameters[i + 1]);
        }
        entry.setParameters(params);
        return entry;
    }

    static ToolApprovalsConfig gateCalculate() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("calculate"));
        return cfg;
    }

    static ToolExecutionRequest calc(String id, String expression) {
        return ToolExecutionRequest.builder().id(id).name("calculate").arguments("{\"expression\":\"" + expression + "\"}").build();
    }

    static ChatResponse text(String s) {
        return ChatResponse.builder().aiMessage(AiMessage.from(s)).build();
    }

    static ChatResponse toolBatch(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    AgentOrchestrator.ExecutionResult run(ChatModel chatModel, LlmConfiguration.Task task, ToolApprovalsConfig approvals) throws Exception {
        return orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, approvals, 0);
    }

    /** Every request the model received, in order. */
    static List<ChatRequest> requests(ChatModel chatModel) {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, atLeastOnce()).chat(captor.capture());
        return captor.getAllValues();
    }

    /** The tool results the model saw in its last request. */
    static List<String> toolResultsSeen(ChatModel chatModel) {
        List<ChatRequest> all = requests(chatModel);
        List<String> seen = new ArrayList<>();
        for (ChatMessage message : all.get(all.size() - 1).messages()) {
            if (message instanceof ToolExecutionResultMessage result) {
                seen.add(result.text());
            }
        }
        return seen;
    }

    /** The tool-call arguments in the model's own messages of its last request. */
    static List<String> callArgumentsSeen(ChatModel chatModel) {
        List<ChatRequest> all = requests(chatModel);
        List<String> seen = new ArrayList<>();
        for (ChatMessage message : all.get(all.size() - 1).messages()) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                ai.toolExecutionRequests().forEach(r -> seen.add(r.arguments()));
            }
        }
        return seen;
    }

    /** Everything the step was given to store, as text. */
    String storedText() {
        ArgumentCaptor<IData<?>> captor = ArgumentCaptor.forClass(IData.class);
        try {
            verify(currentStep, atLeastOnce()).storeData(captor.capture());
        } catch (AssertionError none) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        captor.getAllValues().forEach(d -> sb.append(d.getKey()).append('=').append(d.getResult()).append('\n'));
        return sb.toString();
    }

    PendingToolCallBatch pending(List<ToolExecutionRequest> calls, List<Map<String, Object>> traceSoFar) {
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
        batch.setTraceSoFar(new ArrayList<>(traceSoFar));
        List<ChatMessage> transcript = new ArrayList<>();
        transcript.add(UserMessage.from("improve it"));
        transcript.add(AiMessage.from(calls));
        var res = new ChatTranscriptCodec().serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        batch.setChatTranscriptJson(res.json());
        batch.setTranscriptOmitted(res.omitted());
        return batch;
    }

    double counted(String stage, String outcome) {
        var counter = meterRegistry.find(ToolCallGuardrails.METRIC_DECISIONS).tag("stage", stage).tag("outcome", outcome).counter();
        return counter != null ? counter.count() : 0.0;
    }

    static ChatModel model(ChatResponse first, ChatResponse... rest) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(first, rest);
        return chatModel;
    }
}
