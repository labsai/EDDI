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
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D6b — the in-turn tool-call context must stay inside an aggregate token
 * ceiling.
 *
 * <p>
 * Before {@code maxToolContextTokens}, {@code runToolCallLoop} grew one message
 * list by an {@code AiMessage} plus one {@code ToolExecutionResultMessage} per
 * tool call per iteration, and nothing between the loop head and
 * {@code chatModel.chat} ever looked at its size, character count or token
 * count. A tool-heavy turn therefore ran until the provider rejected the
 * request with a context-window 400 — mid-loop, after the side effects had
 * already happened. Per-result truncation could not save it:
 * {@code toolResponseLimits} has no default, so on an ordinary agent
 * {@code ToolResponseTruncator} returns every result unchanged.
 * </p>
 *
 * <p>
 * The load-bearing assertions here are (a) the request that actually reaches
 * the model is inside the budget, (b) eviction never separates an
 * {@code AiMessage} from its {@code ToolExecutionResultMessage}s — an eviction
 * that did would <em>cause</em> the 400 it exists to prevent — and (c) the
 * default budget changes nothing for an ordinary turn.
 * </p>
 */
@DisplayName("AgentOrchestrator — in-turn tool-context budget")
class AgentOrchestratorToolContextBudgetTest {

    private static final String CONVERSATION_ID = "conv-ctx";

    /** chars/4 estimator: 800 chars of payload ≈ 200 tokens per tool result. */
    private static final String BIG_PAYLOAD = "x".repeat(800);
    /** Admits two {@link #BIG_PAYLOAD} exchanges (424 tokens) but not three. */
    private static final int TIGHT_BUDGET = 500;
    /** Iterations the model is allowed before the loop gives up. */
    private static final int MAX_ITERATIONS = 5;

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
    private ToolExecutionService toolExecutionService;
    @Mock
    private IHitlToolJournalStore journalStore;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        orchestrator = newOrchestrator();

        lenient().when(memory.getConversationId()).thenReturn(CONVERSATION_ID);
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        // Truncation stays OFF (no toolResponseLimits) on purpose: per-result
        // truncation is opt-in, so the aggregate guard has to hold without it.
        lenient().when(toolResponseTruncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    private AgentOrchestrator newOrchestrator() {
        return new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, null,
                null,
                null, null, null, null, null,
                journalStore, new ConversationHistoryBuilder(), new TokenCounterFactory());
    }

    /** Every dispatched tool call returns the same fixed-size payload. */
    private void stubToolResult(String payload) {
        lenient().when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), nullable(String.class),
                nullable(String.class), nullable(String.class), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(payload);
    }

    private LlmConfiguration.Task webSearchTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-ctx");
        // A provider without a native tokenizer → the deterministic chars/4 estimator.
        task.setType("anthropic");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("websearch"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        task.setMaxToolIterations(MAX_ITERATIONS);
        return task;
    }

    /** Snapshot of every message list handed to the model, plus the loop result. */
    private record Run(List<List<ChatMessage>> requests, AgentOrchestrator.ExecutionResult result) {
    }

    /**
     * Drives a full turn in which the model asks for a tool on every iteration.
     * Messages are snapshotted inside the stub rather than through an
     * ArgumentCaptor — the request holds the loop's live list, so a captured
     * reference would show the final state for every iteration.
     */
    private Run runToolHeavyTurn(LlmConfiguration.Task task) throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        AtomicInteger callIds = new AtomicInteger();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            requests.add(List.copyOf(request.messages()));
            return toolBatch("c" + callIds.incrementAndGet());
        });

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory);
        return new Run(requests, result);
    }

    private static ChatResponse toolBatch(String callId) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id(callId).name("searchWeb").arguments("{\"query\":\"eddi\",\"maxResults\":3}").build())).build();
    }

    /** Tokens of the tool half of a message list, by the rule the guard uses. */
    private static int toolContextTokens(List<ChatMessage> messages) {
        var estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();
        int total = 0;
        for (ChatMessage message : messages) {
            boolean isToolTraffic = message instanceof ToolExecutionResultMessage
                    || (message instanceof AiMessage ai && ai.hasToolExecutionRequests());
            if (isToolTraffic) {
                total += estimator.estimateTokenCountInMessage(message);
            }
        }
        return total;
    }

    private static int countExchanges(List<ChatMessage> messages) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                count++;
            }
        }
        return count;
    }

    /**
     * The pairing invariant. Every {@code ToolExecutionResultMessage} must answer
     * an id opened by the immediately preceding {@code AiMessage}, and no
     * {@code AiMessage} may leave an id unanswered.
     */
    private static void assertToolPairing(List<ChatMessage> messages) {
        Set<String> open = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                assertTrue(open.isEmpty(),
                        "an AiMessage announced tool calls " + open + " whose results were evicted, at index " + i);
                ai.toolExecutionRequests().forEach(request -> open.add(request.id()));
            } else if (message instanceof ToolExecutionResultMessage result) {
                assertTrue(open.remove(result.id()),
                        "orphan ToolExecutionResultMessage id=" + result.id() + " at index " + i
                                + " — its requesting AiMessage was evicted without it");
            }
        }
        assertTrue(open.isEmpty(), "tool requests left without results: " + open);
    }

    // ─── End-to-end through the real loop ───

    /**
     * The core regression. Unguarded, the fourth and fifth requests carry three and
     * four whole tool exchanges and the list only ever grows; guarded, no request
     * ever leaves the ceiling.
     */
    @Test
    @DisplayName("an over-budget tool-heavy turn still sends requests inside the ceiling")
    void overBudgetTurnStaysWithinCeiling() throws Exception {
        stubToolResult(BIG_PAYLOAD);
        var task = webSearchTask();
        task.setMaxToolContextTokens(TIGHT_BUDGET);

        var run = runToolHeavyTurn(task);

        assertEquals(MAX_ITERATIONS, run.requests().size(), "the loop must still run its full iteration budget");
        for (int i = 0; i < run.requests().size(); i++) {
            int tokens = toolContextTokens(run.requests().get(i));
            assertTrue(tokens <= TIGHT_BUDGET,
                    "request " + i + " carried " + tokens + " tokens of tool context against a ceiling of "
                            + TIGHT_BUDGET + " — the in-turn context is unbounded again");
        }
    }

    /**
     * The invariant that makes eviction subtle: an {@code AiMessage} carrying tool
     * requests may only be dropped together with its results. Half an exchange is a
     * provider 400 in its own right, so a careless guard would cause the failure it
     * was written to prevent.
     */
    @Test
    @DisplayName("eviction never separates an AiMessage from its tool results")
    void evictionPreservesRequestResultPairing() throws Exception {
        stubToolResult(BIG_PAYLOAD);
        var task = webSearchTask();
        task.setMaxToolContextTokens(TIGHT_BUDGET);

        var run = runToolHeavyTurn(task);

        // Not vacuous: this scenario really must have evicted something.
        assertTrue(run.result().trace().stream().anyMatch(e -> "tool_context_evicted".equals(e.get("type"))),
                "the scenario must actually evict, otherwise the invariant holds trivially");
        assertTrue(countExchanges(run.requests().getLast()) < MAX_ITERATIONS - 1,
                "unguarded, the last request would carry one exchange per prior iteration");

        for (List<ChatMessage> messages : run.requests()) {
            assertToolPairing(messages);
        }
    }

    /**
     * The guard against regressing agents that work today: with the shipped default
     * budget an ordinary tool-using turn must produce exactly the message lists it
     * produced with no guard at all — {@code -1}, which IS the pre-6.1 behaviour.
     */
    @Test
    @DisplayName("a normal turn under the default budget is identical to no guard at all")
    void normalTurnUnderDefaultBudgetIsUnchanged() throws Exception {
        stubToolResult("a modest tool result".repeat(20));

        var defaulted = webSearchTask();
        assertEquals(AgentOrchestrator.DEFAULT_MAX_TOOL_CONTEXT_TOKENS, defaulted.getMaxToolContextTokens().intValue(),
                "an unset config must get the documented default");
        var withDefaultBudget = runToolHeavyTurn(defaulted);

        orchestrator = newOrchestrator();
        var disabled = webSearchTask();
        disabled.setMaxToolContextTokens(-1);
        var withGuardDisabled = runToolHeavyTurn(disabled);

        assertEquals(withGuardDisabled.requests(), withDefaultBudget.requests(),
                "the default budget must not alter a turn that works today");
        assertTrue(withDefaultBudget.result().trace().stream()
                .noneMatch(e -> "tool_context_evicted".equals(e.get("type"))),
                "nothing may be evicted from a turn that fits comfortably inside the default budget");
    }

    @Test
    @DisplayName("eviction is reported in the execution trace")
    void evictionIsObservableInTrace() throws Exception {
        stubToolResult(BIG_PAYLOAD);
        var task = webSearchTask();
        task.setMaxToolContextTokens(TIGHT_BUDGET);

        var run = runToolHeavyTurn(task);

        var eviction = run.result().trace().stream()
                .filter(e -> "tool_context_evicted".equals(e.get("type"))).findFirst();
        assertTrue(eviction.isPresent(), "silent data loss is not acceptable — the trace must record the eviction");
        assertEquals(TIGHT_BUDGET, (int) eviction.get().get("budgetTokens"));
        assertEquals(Boolean.TRUE, eviction.get().get("withinBudget"));
        assertTrue((int) eviction.get().get("evictedExchanges") >= 1);
        assertTrue((int) eviction.get().get("evictedMessages") >= 2);
        assertTrue((int) eviction.get().get("tokensBefore") > (int) eviction.get().get("tokensAfter"));
    }

    // ─── enforceToolContextBudget in isolation ───

    private static final TokenCountEstimator ESTIMATOR = new TokenCounterFactory.ApproximateTokenCountEstimator();

    private static ToolExecutionRequest request(String id) {
        return ToolExecutionRequest.builder().id(id).name("t").arguments("{}").build();
    }

    /** Appends {@code [AiMessage(id), ToolExecutionResultMessage(id, payload)]}. */
    private static void appendExchange(List<ChatMessage> messages, String id, String payload) {
        messages.add(AiMessage.from(request(id)));
        messages.add(ToolExecutionResultMessage.from(request(id), payload));
    }

    /** Runs the guard and returns how many messages it removed. */
    private static int evict(List<ChatMessage> messages, int budget, List<Map<String, Object>> trace) {
        int before = messages.size();
        AgentOrchestrator.enforceToolContextBudget(messages, budget, ESTIMATOR, new IdentityHashMap<>(), trace,
                CONVERSATION_ID);
        return before - messages.size();
    }

    @Test
    @DisplayName("a single oversized exchange is never evicted — nothing else could answer the model")
    void singleExchangeIsNeverEvicted() {
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("sys"), UserMessage.from("hi")));
        appendExchange(messages, "c1", BIG_PAYLOAD);
        List<Map<String, Object>> trace = new ArrayList<>();

        assertEquals(0, evict(messages, 1, trace));
        assertTrue(trace.isEmpty());
    }

    @Test
    @DisplayName("system, user and assistant-prose messages are never candidates")
    void conversationHistoryIsNeverEvicted() {
        List<ChatMessage> history = List.of(SystemMessage.from("sys"), UserMessage.from("hi"),
                AiMessage.from("hello"), UserMessage.from("go"));
        List<ChatMessage> messages = new ArrayList<>(history);
        appendExchange(messages, "c1", BIG_PAYLOAD);
        appendExchange(messages, "c2", BIG_PAYLOAD);
        appendExchange(messages, "c3", BIG_PAYLOAD);

        assertEquals(2, evict(messages, TIGHT_BUDGET, new ArrayList<>()), "one whole exchange, two messages");
        assertEquals(history, messages.subList(0, 4), "history must survive byte-for-byte");
        assertToolPairing(messages);
    }

    @Test
    @DisplayName("the oldest exchanges go first and the newest one survives")
    void evictsOldestFirst() {
        List<ChatMessage> messages = new ArrayList<>();
        appendExchange(messages, "c1", BIG_PAYLOAD);
        appendExchange(messages, "c2", BIG_PAYLOAD);
        appendExchange(messages, "c3", BIG_PAYLOAD);

        assertEquals(4, evict(messages, 250, new ArrayList<>()));
        assertEquals(2, messages.size());
        assertEquals("c3", ((ToolExecutionResultMessage) messages.get(1)).id(), "the newest exchange must survive");
        assertToolPairing(messages);
    }

    @Test
    @DisplayName("an unfittable newest exchange is reported rather than dropped")
    void unfittableNewestExchangeIsReported() {
        List<ChatMessage> messages = new ArrayList<>();
        appendExchange(messages, "c1", BIG_PAYLOAD);
        appendExchange(messages, "c2", BIG_PAYLOAD);
        List<Map<String, Object>> trace = new ArrayList<>();

        assertEquals(2, evict(messages, 10, trace));
        assertEquals(2, messages.size(), "the newest exchange stays even though it alone busts the budget");
        assertEquals(Boolean.FALSE, trace.getFirst().get("withinBudget"));
        assertToolPairing(messages);
    }

    @Test
    @DisplayName("a multi-call exchange is evicted whole, all of its results with it")
    void multiCallExchangeIsEvictedWhole() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(AiMessage.from(List.of(request("a1"), request("a2"), request("a3"))));
        messages.add(ToolExecutionResultMessage.from(request("a1"), BIG_PAYLOAD));
        messages.add(ToolExecutionResultMessage.from(request("a2"), BIG_PAYLOAD));
        messages.add(ToolExecutionResultMessage.from(request("a3"), BIG_PAYLOAD));
        appendExchange(messages, "b1", "small");

        assertEquals(4, evict(messages, TIGHT_BUDGET, new ArrayList<>()),
                "the AiMessage and all three of its results leave together");
        assertToolPairing(messages);
    }

    @Test
    @DisplayName("tool traffic already inside the budget is left untouched")
    void withinBudgetIsUntouched() {
        List<ChatMessage> messages = new ArrayList<>();
        appendExchange(messages, "c1", "small");
        appendExchange(messages, "c2", "small");
        List<Map<String, Object>> trace = new ArrayList<>();

        assertEquals(0, evict(messages, TIGHT_BUDGET, trace));
        assertTrue(trace.isEmpty());
    }
}
