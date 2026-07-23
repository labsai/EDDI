/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Proves that {@link LlmTask} writes the evidence the audit ledger claims to
 * record — real token counts, tool calls and dollar cost — rather than the
 * zeros and nulls {@code LifecycleManager.buildAuditEntry} hard-coded while the
 * keys it reads had no writer at all.
 * <p>
 * Unlike the other {@code LlmTask*Test} classes this one backs the conversation
 * step with a real map, because every assertion here is about
 * <em>accumulation</em>: {@code getLatestData} is last-write-wins, so a mock
 * step that always answers {@code null} would make a broken accumulator look
 * correct.
 */
@DisplayName("LlmTask — audit ledger evidence")
class LlmTaskAuditLedgerTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private PrePostUtils prePostUtils;
    @Mock
    private ChatModelRegistry chatModelRegistry;
    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private CounterweightService counterweightService;
    @Mock
    private IdentityMaskingService identityMaskingService;
    @Mock
    private PromptSnippetService promptSnippetService;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private ChatModel chatModel;
    @Mock
    private IConversationMemory memory;

    /** Every {@code storeData} call, replayed by {@code getLatestData}. */
    private final Map<String, IData<?>> stored = new LinkedHashMap<>();

    private IWritableConversationStep currentStep;
    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);

        IDataFactory dataFactory = new IDataFactory() {
            @Override
            public <T> IData<T> createData(String key, T value) {
                return new Data<>(key, value);
            }

            @Override
            public <T> IData<T> createData(String key, T value, boolean isPublic) {
                return new Data<>(key, value, null, null, isPublic);
            }

            @Override
            public <T> IData<T> createData(String key, T value, List<T> possibleValues) {
                return new Data<>(key, value, possibleValues);
            }
        };

        currentStep = mock(IWritableConversationStep.class);
        lenient().doAnswer(inv -> {
            IData<?> data = inv.getArgument(0);
            stored.put(data.getKey(), data);
            return null;
        }).when(currentStep).storeData(any());
        lenient().doAnswer(inv -> stored.get(inv.<String>getArgument(0))).when(currentStep).getLatestData(anyString());

        lenient().when(promptSnippetService.getAll()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(counterweightService.apply(anyString(), any(), any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(templatingEngine.processTemplate(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0));
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);

        // PR #604 made AgentOrchestrator an injected collaborator, so it is passed
        // straight to the constructor — the reflective field write this test used
        // before is no longer needed.
        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                mock(IApiCallExecutor.class), mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                mock(RagContextProvider.class), new TokenCounterFactory(),
                mock(ConversationSummarizer.class), promptSnippetService, globalVariableResolver,
                counterweightService, identityMaskingService,
                agentOrchestrator, new ConversationHistoryBuilder(), new SimpleMeterRegistry());

        wireMemory();
    }

    private void wireMemory() {
        when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(memory.getAuditCollector()).thenReturn(mock(IAuditEntryCollector.class));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        lenient().when(memory.getHitlPendingToolCalls()).thenReturn(null);
        lenient().when(memory.getHitlResumeDecision()).thenReturn(null);
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        stored.put(ACTIONS.key(), new Data<>(ACTIONS.key(), List.of("action1")));
        lenient().when(currentStep.getLatestData(ACTIONS)).thenAnswer(inv -> stored.get(ACTIONS.key()));
    }

    // ==================== helpers ====================

    private LlmConfiguration.Task task(String id) {
        var t = new LlmConfiguration.Task();
        t.setId(id);
        t.setType("openai");
        t.setActions(List.of("action1"));
        t.setParameters(Map.of("apiKey", "key"));
        return t;
    }

    private static ChatResponse response(String text, Integer in, Integer out, Integer total) {
        return ChatResponse.builder().aiMessage(aiMessage(text))
                .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(in, out, total)).build()).build();
    }

    private void agentReturns(String response, List<Map<String, Object>> trace, Map<String, Object> metadata) throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult(response, trace, metadata));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditMap(String key) {
        IData<?> data = stored.get(key);
        return data != null ? (Map<String, Object>) data.getResult() : null;
    }

    private Double auditCost() {
        IData<?> data = stored.get(MemoryKeys.AUDIT_COST);
        return data != null ? (Double) data.getResult() : null;
    }

    // ==================== token usage ====================

    @Test
    @DisplayName("an agent-mode turn records the real token counts, not zero")
    void agentModeSurfacesTokenUsage() throws Exception {
        agentReturns("done", new ArrayList<>(), Map.of("tokenUsage", Map.of("inputTokens", 120, "outputTokens", 30, "totalTokens", 150)));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        var tokenUsage = auditMap(MemoryKeys.AUDIT_TOKEN_USAGE);
        assertNotNull(tokenUsage, "agent-mode token usage must reach the audit ledger key");
        assertEquals(120L, tokenUsage.get("inputTokens"));
        assertEquals(30L, tokenUsage.get("outputTokens"));
        assertEquals(150L, tokenUsage.get("totalTokens"));
    }

    @Test
    @DisplayName("two LLM calls in one turn sum rather than overwrite")
    void tokenUsageAccumulatedAcrossSubTasks() throws Exception {
        // Legacy (no-tools) path: each config sub-task drives one model call.
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any())).thenReturn(null);
        when(chatModel.chat(anyList()))
                .thenReturn(response("first", 10, 20, 30))
                .thenReturn(response("second", 1, 2, 3));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"), task("taskB"))));

        var tokenUsage = auditMap(MemoryKeys.AUDIT_TOKEN_USAGE);
        assertNotNull(tokenUsage);
        assertEquals(11L, tokenUsage.get("inputTokens"), "the second sub-task must add to the first, not replace it");
        assertEquals(22L, tokenUsage.get("outputTokens"));
        assertEquals(33L, tokenUsage.get("totalTokens"));
    }

    @Test
    @DisplayName("a provider that omits a count does not zero what earlier calls contributed")
    void tokenUsageAccumulationHandlesNullCounts() throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any())).thenReturn(null);
        when(chatModel.chat(anyList()))
                .thenReturn(response("first", 10, 20, 30))
                .thenReturn(response("second", null, null, null));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"), task("taskB"))));

        var tokenUsage = auditMap(MemoryKeys.AUDIT_TOKEN_USAGE);
        assertNotNull(tokenUsage);
        assertEquals(10L, tokenUsage.get("inputTokens"));
        assertEquals(30L, tokenUsage.get("totalTokens"));
    }

    // ==================== tool calls ====================

    @Test
    @DisplayName("tool calls are recorded under a 'calls' list, tagged with the sub-task that made them")
    void toolCallsAccumulatedAcrossSubTasksWithTaskId() throws Exception {
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(Map.of("type", "tool_call", "tool", "calculator"));
        agentReturns("done", trace, Map.of());

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"), task("taskB"))));

        var toolCalls = auditMap(MemoryKeys.AUDIT_TOOL_CALLS);
        assertNotNull(toolCalls, "toolCalls used to be hard-coded null on every audit entry");
        assertInstanceOf(List.class, toolCalls.get("calls"));
        var calls = (List<?>) toolCalls.get("calls");
        assertEquals(2, calls.size(), "both sub-tasks' traces must be merged, not overwritten");
        assertEquals("taskA", ((Map<?, ?>) calls.get(0)).get("llmTaskId"));
        assertEquals("taskB", ((Map<?, ?>) calls.get(1)).get("llmTaskId"));
    }

    @Test
    @DisplayName("a turn that called no tool leaves the toolCalls key absent")
    void noToolCallsLeavesKeyAbsent() throws Exception {
        agentReturns("done", new ArrayList<>(), Map.of());

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        assertNull(stored.get(MemoryKeys.AUDIT_TOOL_CALLS));
    }

    // ==================== cost ====================

    @Test
    @DisplayName("tool cost reported by the orchestrator accumulates into the audit cost")
    void toolCostAccumulatedIntoAuditCost() throws Exception {
        agentReturns("done", new ArrayList<>(), Map.of("toolCostUsd", 0.002));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"), task("taskB"))));

        assertEquals(0.004, auditCost(), 1e-9, "cost must total both sub-tasks, not report the last");
    }

    @Test
    @DisplayName("a free turn leaves the cost key absent, which the ledger reads as 0.0")
    void freeTurnWritesNoCost() throws Exception {
        agentReturns("done", new ArrayList<>(), Map.of("toolCostUsd", 0.0));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        assertNull(stored.get(MemoryKeys.AUDIT_COST));
    }

    // ==================== cascade ====================

    @Test
    @DisplayName("cascade run cost and a Double confidence reach the ledger keys that are actually read")
    void cascadeRunCostAndConfidenceRecorded() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        cascade.setInputPricePer1M(1.0);
        cascade.setOutputPricePer1M(2.0);
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        when(chatModel.chat(anyList())).thenReturn(response("cascade answer", 1000, 500, 1500));

        var t = task("taskA");
        t.setModelCascade(cascade);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // 1000/1M * $1.00 + 500/1M * $2.00 = $0.001 + $0.001
        assertEquals(0.002, auditCost(), 1e-9, "configured cascade pricing is the only real LLM dollar signal there is");

        IData<?> confidence = stored.get(MemoryKeys.AUDIT_CONFIDENCE);
        assertNotNull(confidence, "LifecycleManager reads audit:confidence — the old audit:cascade_confidence had no reader");
        assertInstanceOf(Double.class, confidence.getResult(), "the reader declares IData<Double>; a String would blow up downstream");
        assertNull(stored.get("audit:cascade_confidence"));
        assertNull(stored.get("audit:cascade_cost"));
        assertNull(stored.get("audit:cascade_token_usage"));

        var tokenUsage = auditMap(MemoryKeys.AUDIT_TOKEN_USAGE);
        assertNotNull(tokenUsage);
        assertEquals(1000L, tokenUsage.get("inputTokens"));
    }

    /**
     * The cascade branch builds a FRESH response-metadata map and hand-copies
     * selected keys out of the winning step's metadata. It copied the validation
     * signals but not {@code toolCostUsd}, so on the agent-mode cascade path — the
     * DEFAULT, since {@code enableInAgentMode} defaults to true — the entire tool
     * spend of the turn never reached the ledger.
     */
    @Test
    @DisplayName("an agent-mode cascade adds the tool spend to the audit cost, not just the token cost")
    void cascadeAgentModeCarriesToolCost() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        cascade.setInputPricePer1M(1.0);
        cascade.setOutputPricePer1M(2.0);
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        agentReturns("cascade answer", new ArrayList<>(),
                Map.of("tokenUsage", Map.of("inputTokens", 1000, "outputTokens", 500, "totalTokens", 1500),
                        "toolCostUsd", 0.0075));

        var t = task("taskA");
        t.setEnableBuiltInTools(true); // → isAgentMode()
        t.setModelCascade(cascade);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // 1000/1M * $1.00 + 500/1M * $2.00 = $0.002 of token cost, plus $0.0075 of
        // tracked tool spend the cascade branch used to drop on the floor.
        assertEquals(0.0095, auditCost(), 1e-9, "the cascade's tool spend must reach the ledger");
    }

    @Test
    @DisplayName("an escalating cascade sums the tool spend of every step it tried")
    void cascadeSumsToolCostAcrossEscalatedSteps() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        var cheap = new CascadeStep();
        cheap.setType("openai");
        cheap.setTimeoutMs(5000L);
        // Unreachable threshold → step 0 runs its tools and then escalates.
        cheap.setConfidenceThreshold(1.1);
        var strong = new CascadeStep();
        strong.setType("openai");
        strong.setTimeoutMs(5000L);
        cascade.setSteps(List.of(cheap, strong));

        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("weak", new ArrayList<>(), Map.of("toolCostUsd", 0.003)))
                .thenReturn(new AgentOrchestrator.ExecutionResult("strong answer", new ArrayList<>(), Map.of("toolCostUsd", 0.004)));

        var t = task("taskA");
        t.setEnableBuiltInTools(true);
        t.setModelCascade(cascade);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        assertEquals(0.007, auditCost(), 1e-9,
                "the escalated step's tools really ran and were charged — reporting only the winning step under-bills the ledger");
    }

    // ==================== HITL resume ====================

    @Test
    @DisplayName("a HITL-resumed turn writes the compiled prompt, so its llmDetail block is not dropped")
    void executeResumeWritesCompiledPromptAndEvidence() throws Exception {
        var batch = new PendingToolCallBatch();
        batch.setLlmTaskIndex(0);
        batch.setLlmTaskId("taskA");
        batch.setIterationIndex(0);
        batch.setCalls(new ArrayList<>());
        when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        when(memory.getHitlResumeDecision()).thenReturn(decision);

        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(Map.of("type", "tool_call", "tool", "calculator"));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed", trace,
                        Map.of("tokenUsage", Map.of("inputTokens", 7, "outputTokens", 3, "totalTokens", 10),
                                "toolCostUsd", 0.005)));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        // LifecycleManager gates the ENTIRE llmDetail block on this key.
        assertNotNull(stored.get(MemoryKeys.AUDIT_COMPILED_PROMPT),
                "without the compiled prompt every HITL-resumed turn audits with no LLM detail at all");
        assertNotNull(stored.get(MemoryKeys.AUDIT_MODEL_RESPONSE));

        var tokenUsage = auditMap(MemoryKeys.AUDIT_TOKEN_USAGE);
        assertNotNull(tokenUsage);
        assertEquals(7L, tokenUsage.get("inputTokens"));
        assertEquals(0.005, auditCost(), 1e-9);

        var toolCalls = auditMap(MemoryKeys.AUDIT_TOOL_CALLS);
        assertNotNull(toolCalls);
        assertTrue(((List<?>) toolCalls.get("calls")).size() == 1);
    }

    // ==================== audit disabled ====================

    @Test
    @DisplayName("no audit collector → not a single audit key is written")
    void noAuditCollectorWritesNothing() throws Exception {
        when(memory.getAuditCollector()).thenReturn(null);
        agentReturns("done", List.of(Map.of("type", "tool_call")), Map.of("toolCostUsd", 0.01));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        assertTrue(stored.keySet().stream().noneMatch(k -> k.startsWith("audit:")),
                "audit evidence must stay behind the collector gate: " + stored.keySet());
    }
}
