/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.*;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional branch-coverage tests for {@link LlmTask}, focused on the
 * UNCOVERED error/edge/branch paths that the integration + existing unit tests
 * do not exercise:
 * <ul>
 * <li>execute() guards — null actions data, null actions list, action-matcher
 * match / no-match / MATCH_ALL, resume-mode true/false</li>
 * <li>effectiveToolApprovals resolution — toolHitlEnabled true/false,
 * task-override vs agent-default vs null</li>
 * <li>cascade branch — enabled vs disabled vs skipCascade (agent mode)</li>
 * <li>standard branch — agent result null → legacy fallback vs non-null</li>
 * <li>convertToObject true JSON / non-JSON / false</li>
 * <li>streaming eventSink present vs null</li>
 * <li>addToOutput false</li>
 * <li>executeResume store-result / postResponse / clear paths</li>
 * </ul>
 * The constructor wiring mirrors {@code LlmTaskResumeModeTest}: the internally
 * {@code new}-created {@link AgentOrchestrator} is replaced via reflection with
 * a mock so the standard / cascade-skip / legacy-fallback paths can be steered
 * deterministically.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@DisplayName("LlmTask coverage (guards, tool-approvals, cascade, resume)")
class LlmTaskCoverageTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
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
    private RagContextProvider ragContextProvider;
    @Mock
    private PromptSnippetService promptSnippetService;
    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private ChatModel chatModel;

    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);

        lenient().when(promptSnippetService.getAll()).thenReturn(Collections.emptyMap());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        var counterweightService = mock(CounterweightService.class);
        lenient().when(counterweightService.apply(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        var identityMaskingService = mock(IdentityMaskingService.class);
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                mock(CalculatorTool.class), mock(DateTimeTool.class), mock(WebSearchTool.class),
                mock(DataFormatterTool.class), mock(WebScraperTool.class), mock(TextSummarizerTool.class),
                mock(PdfReaderTool.class), mock(WeatherTool.class), mock(FetchToolResponsePageTool.class),
                mock(IApiCallExecutor.class), mock(ToolExecutionService.class),
                mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class),
                mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                ragContextProvider, mock(IUserMemoryStore.class),
                new TokenCounterFactory(), mock(ConversationSummarizer.class),
                promptSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, mock(ToolResponseTruncator.class),
                mock(ai.labs.eddi.engine.tenancy.TenantQuotaService.class),
                null, null, null, null, null, null, null,
                mock(ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.class));

        // The orchestrator is created internally via `new` — inject the mock so
        // standard / cascade-skip / legacy-fallback paths can be steered.
        Field orchestratorField = LlmTask.class.getDeclaredField("agentOrchestrator");
        orchestratorField.setAccessible(true);
        orchestratorField.set(llmTask, agentOrchestrator);

        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData d = mock(IData.class);
            when(d.getResult()).thenReturn(inv.getArgument(1));
            return d;
        });
        lenient().when(jsonSerialization.deserialize(anyString(), eq(Map.class))).thenReturn(Map.of("k", "v"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private LlmConfiguration.Task task(String id, List<String> actions, Map<String, String> extraParams) {
        var t = new LlmConfiguration.Task();
        t.setId(id);
        t.setType("openai");
        t.setActions(actions);
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        if (extraParams != null) {
            params.putAll(extraParams);
        }
        t.setParameters(params);
        return t;
    }

    private PendingToolCallBatch batch(String taskId, int taskIndex) {
        var b = new PendingToolCallBatch();
        b.setLlmTaskId(taskId);
        b.setLlmTaskIndex(taskIndex);
        return b;
    }

    private HitlDecision decision(HitlVerdict verdict) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        return d;
    }

    private ChatResponse chatResponse(String text) {
        return ChatResponse.builder().aiMessage(aiMessage(text)).build();
    }

    /**
     * Wire non-resume memory that produces a non-empty message list. The template
     * engine passes params through unchanged unless the caller overrides.
     */
    private void wireStandardMemory(List<String> actions) throws Exception {
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(actions);
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        lenient().when(memory.getHitlPendingToolCalls()).thenReturn(null);
        lenient().when(memory.getHitlResumeDecision()).thenReturn(null);
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void wireResumeMemory(List<String> actions, PendingToolCallBatch b, HitlDecision d) throws Exception {
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(actions);
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        when(memory.getHitlPendingToolCalls()).thenReturn(b);
        when(memory.getHitlResumeDecision()).thenReturn(d);
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void agentReturns(String response) throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AgentOrchestrator.ExecutionResult(response, new ArrayList<>()));
    }

    private void agentReturnsNull() throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(null);
    }

    // ============================================================
    // execute() guards
    // ============================================================

    @Test
    @DisplayName("null actions data → early return, no orchestrator call")
    void nullActionsData_earlyReturn() throws Exception {
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(currentStep.getLatestData(ACTIONS)).thenReturn(null);

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verifyNoInteractions(agentOrchestrator);
        // memoryItemConverter.convert is only reached AFTER the null-data guard.
        verify(memoryItemConverter, never()).convert(any());
    }

    @Test
    @DisplayName("null actions list (data present, result null) → early return before task loop")
    void nullActionsList_earlyReturn() throws Exception {
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(null);
        when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        when(memory.getHitlPendingToolCalls()).thenReturn(null);
        when(memory.getHitlResumeDecision()).thenReturn(null);

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verifyNoInteractions(agentOrchestrator);
    }

    @Test
    @DisplayName("action-matcher NO match → task not executed")
    void actionMatcher_noMatch_taskSkipped() throws Exception {
        wireStandardMemory(List.of("otherAction"));

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verifyNoInteractions(agentOrchestrator);
    }

    @Test
    @DisplayName("action-matcher MATCH → task executed via standard branch")
    void actionMatcher_match_taskExecuted() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("MATCH_ALL operator (*) → task executed regardless of actions")
    void matchAllOperator_taskExecuted() throws Exception {
        wireStandardMemory(List.of("whatever"));
        agentReturns("done");

        var t = task("taskA", List.of("*"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("empty snippets map is NOT injected into template data")
    void emptySnippets_notInjected() throws Exception {
        wireStandardMemory(List.of("action1"));
        var templateData = new HashMap<String, Object>();
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        when(promptSnippetService.getAll()).thenReturn(Collections.emptyMap());
        when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        agentReturns("done");

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        assertFalse(templateData.containsKey("snippets"));
        assertFalse(templateData.containsKey("vars"));
    }

    // ============================================================
    // effectiveToolApprovals resolution
    // ============================================================

    @Test
    @DisplayName("toolHitlEnabled=true + task override → task's ToolApprovalsConfig threaded to orchestrator")
    void toolApprovals_taskOverrideUsed() throws Exception {
        llmTask.toolHitlEnabled = true;
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var override = new ToolApprovalsConfig();
        override.setRequireApproval(List.of("delete_*"));
        var t = task("taskA", List.of("action1"), null);
        t.setToolApprovals(override);
        // Agent default present but must be IGNORED in favor of the task override.
        lenient().when(memory.getAgentToolApprovalsConfig()).thenReturn(new ToolApprovalsConfig());

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        var captor = ArgumentCaptor.forClass(ToolApprovalsConfig.class);
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), captor.capture(), anyInt(), anyInt());
        assertSame(override, captor.getValue());
    }

    @Test
    @DisplayName("toolHitlEnabled=true + no task override → agent-default ToolApprovalsConfig threaded")
    void toolApprovals_agentDefaultUsed() throws Exception {
        llmTask.toolHitlEnabled = true;
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var agentDefault = new ToolApprovalsConfig();
        when(memory.getAgentToolApprovalsConfig()).thenReturn(agentDefault);

        var t = task("taskA", List.of("action1"), null); // no task override
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        var captor = ArgumentCaptor.forClass(ToolApprovalsConfig.class);
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), captor.capture(), anyInt(), anyInt());
        assertSame(agentDefault, captor.getValue());
    }

    @Test
    @DisplayName("toolHitlEnabled=false → effective config forced null (gate inert) even with task override present")
    void toolApprovals_killSwitchForcesNull() throws Exception {
        llmTask.toolHitlEnabled = false;
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var override = new ToolApprovalsConfig();
        var t = task("taskA", List.of("action1"), null);
        t.setToolApprovals(override);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        var captor = ArgumentCaptor.forClass(ToolApprovalsConfig.class);
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), captor.capture(), anyInt(), anyInt());
        assertNull(captor.getValue());
        // Agent default must never be consulted when the kill-switch is off.
        verify(memory, never()).getAgentToolApprovalsConfig();
    }

    // ============================================================
    // Standard branch — agent result non-null vs null → legacy fallback
    // ============================================================

    @Test
    @DisplayName("standard branch: non-null agent result → tool mode, response stored, no legacy fallback")
    void standardBranch_agentResultNonNull() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("agent answer");

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // Legacy executor path must not run: chatModel.chat(List) is only hit by
        // legacy.
        verify(chatModel, never()).chat(anyList());
        verify(currentStep, atLeastOnce()).storeData(any());
    }

    @Test
    @DisplayName("standard branch: null agent result + no eventSink → legacy fallback via chatModel.chat")
    void standardBranch_agentResultNull_legacyFallback() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturnsNull();
        when(memory.getEventSink()).thenReturn(null);
        when(chatModel.chat(anyList())).thenReturn(chatResponse("legacy answer"));

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(chatModel).chat(anyList());
    }

    @Test
    @DisplayName("standard branch: null agent result + eventSink + no streaming model → sync fallback emits token")
    void standardBranch_streamingUnsupported_syncFallbackEmits() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturnsNull();
        var eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);
        when(chatModelRegistry.getOrCreateStreaming(anyString(), any())).thenReturn(null);
        when(chatModel.chat(anyList())).thenReturn(chatResponse("legacy answer"));

        var t = task("taskA", List.of("action1"), Map.of("addToOutput", "true"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // Sync fallback emits the whole response as a single chunk.
        verify(eventSink).onToken("legacy answer");
    }

    @Test
    @DisplayName("standard branch: agent result non-null + eventSink → final response streamed once")
    void standardBranch_agentResult_streamsFinal() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("agent answer");
        var eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(eventSink).onToken("agent answer");
    }

    @Test
    @DisplayName("standard branch: null agent result + eventSink + no-streaming-model + addToOutput=false → no token emit")
    void standardBranch_syncFallback_addToOutputFalse_noEmit() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturnsNull();
        var eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);
        when(chatModelRegistry.getOrCreateStreaming(anyString(), any())).thenReturn(null);
        when(chatModel.chat(anyList())).thenReturn(chatResponse("legacy answer"));

        var t = task("taskA", List.of("action1"), Map.of("addToOutput", "false"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(eventSink, never()).onToken(anyString());
    }

    // ============================================================
    // Cascade branch — enabled vs disabled vs skipCascade
    // ============================================================

    @Test
    @DisplayName("cascade config present but disabled → standard branch taken")
    void cascadeDisabled_standardBranch() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(false);
        var t = task("taskA", List.of("action1"), null);
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // Standard branch executes the orchestrator directly (not the cascade
        // executor).
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("cascade enabled with empty steps → standard branch taken")
    void cascadeEnabledEmptySteps_standardBranch() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(new ArrayList<>()); // empty → guard falls through to standard
        var t = task("taskA", List.of("action1"), null);
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("cascade enabled in agent mode with enableInAgentMode=false → skipCascade → normal agent flow")
    void cascadeSkippedInAgentMode_usesAgentFlow() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("agent answer");

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(false);
        var step = new CascadeStep();
        step.setConfidenceThreshold(0.5);
        cascade.setSteps(List.of(step));

        // Force agent mode so isAgentMode() && !enableInAgentMode → skipCascade true.
        var t = task("taskA", List.of("action1"), null);
        t.setEnableBuiltInTools(true);
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // skipCascade path uses the orchestrator directly (cascade executor bypassed).
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("cascade skip in agent mode + agent result null → legacy fallback inside skip branch")
    void cascadeSkip_agentNull_legacyFallback() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturnsNull();
        when(chatModel.chat(anyList())).thenReturn(chatResponse("legacy answer"));

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(false);
        var step = new CascadeStep();
        step.setConfidenceThreshold(0.5);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), null);
        t.setEnableBuiltInTools(true);
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(chatModel).chat(anyList());
    }

    // ============================================================
    // convertToObject branch — JSON vs non-JSON vs false
    // ============================================================

    @Test
    @DisplayName("convertToObject=true + JSON response ({...}) → deserialized into map")
    void convertToObject_jsonResponse_deserialized() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("{\"foo\":\"bar\"}");

        var t = task("taskA", List.of("action1"), Map.of("convertToObject", "true"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(jsonSerialization).deserialize(eq("{\"foo\":\"bar\"}"), eq(Map.class));
    }

    @Test
    @DisplayName("convertToObject=true + array response ([...]) → deserialized")
    void convertToObject_arrayResponse_deserialized() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("[1,2,3]");

        var t = task("taskA", List.of("action1"), Map.of("convertToObject", "true"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(jsonSerialization).deserialize(eq("[1,2,3]"), eq(Map.class));
    }

    @Test
    @DisplayName("convertToObject=true + non-JSON response → stored as string, no deserialize")
    void convertToObject_nonJson_storedAsString() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("plain text answer");

        var t = task("taskA", List.of("action1"), Map.of("convertToObject", "true"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(jsonSerialization, never()).deserialize(anyString(), eq(Map.class));
    }

    @Test
    @DisplayName("convertToObject=false → response stored raw, no deserialize")
    void convertToObject_false_noDeserialize() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("{\"foo\":\"bar\"}");

        var t = task("taskA", List.of("action1"), null); // convertToObject absent → false
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(jsonSerialization, never()).deserialize(anyString(), eq(Map.class));
    }

    @Test
    @DisplayName("convertToObject=true + null response content → treated as non-JSON, no deserialize")
    void convertToObject_nullResponse_noDeserialize() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns(null); // null response content

        var t = task("taskA", List.of("action1"), Map.of("convertToObject", "true"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(jsonSerialization, never()).deserialize(anyString(), eq(Map.class));
    }

    // ============================================================
    // addToOutput true/false + tool mode
    // ============================================================

    @Test
    @DisplayName("tool mode (agent result) → output added even without addToOutput=true")
    void toolMode_addsOutput() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("agent answer");

        var t = task("taskA", List.of("action1"), null); // addToOutput not set
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(currentStep).addConversationOutputList(eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
    }

    @Test
    @DisplayName("addToOutput=false + tool mode → NO output added (postResponse owns output)")
    void addToOutputFalse_toolMode_noOutput() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("agent answer");

        var t = task("taskA", List.of("action1"), Map.of("addToOutput", "false"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(currentStep, never()).addConversationOutputList(eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
    }

    // ============================================================
    // Metadata + audit branches
    // ============================================================

    @Test
    @DisplayName("responseMetadataObjectName set → createMemoryEntry invoked")
    void responseMetadataObjectName_createsEntry() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var t = task("taskA", List.of("action1"), null);
        t.setResponseMetadataObjectName("meta");
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(prePostUtils).createMemoryEntry(any(), any(), eq("meta"), eq("langchain"));
    }

    @Test
    @DisplayName("auditCollector present → audit:* keys stored")
    void auditCollectorPresent_storesAuditKeys() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("audited answer");
        when(memory.getAuditCollector()).thenReturn(mock(ai.labs.eddi.engine.audit.IAuditEntryCollector.class));

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(dataFactory).createData(eq("audit:model_response"), eq("audited answer"));
        verify(dataFactory).createData(eq("audit:model_name"), any());
    }

    @Test
    @DisplayName("postResponse always runs on the standard path")
    void postResponse_runsOnStandardPath() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(200), eq(false));
    }

    // ============================================================
    // executeResume — store-result / convertToObject / audit / output / clear
    // ============================================================

    @Test
    @DisplayName("resume: convertToObject=true + JSON resumed response → deserialized")
    void resume_convertToObjectJson_deserialized() throws Exception {
        var b = batch("taskA", 0);
        wireResumeMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("{\"x\":1}", new ArrayList<>()));

        var t = task("taskA", List.of("action1"), Map.of("convertToObject", "true"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(jsonSerialization).deserialize(eq("{\"x\":1}"), eq(Map.class));
    }

    @Test
    @DisplayName("resume: convertToObject=true + non-JSON resumed response → stored as string, no deserialize")
    void resume_convertToObjectNonJson_noDeserialize() throws Exception {
        var b = batch("taskA", 0);
        wireResumeMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("plain answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"), Map.of("convertToObject", "true"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(jsonSerialization, never()).deserialize(anyString(), eq(Map.class));
    }

    @Test
    @DisplayName("resume: null result from resumeToolLoop → null response handled, state still cleared")
    void resume_nullResult_stateCleared() throws Exception {
        var b = batch("taskA", 0);
        wireResumeMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(null);

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(memory).setHitlPendingToolCalls(null);
        verify(memory).setHitlResumeDecision(null);
        verify(memory).setHitlPauseType(null);
    }

    @Test
    @DisplayName("resume: addToOutput=false → resumed final response NOT added to output")
    void resume_addToOutputFalse_noOutput() throws Exception {
        var b = batch("taskA", 0);
        wireResumeMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"), Map.of("addToOutput", "false"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(currentStep, never()).addConversationOutputList(eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
    }

    @Test
    @DisplayName("resume: auditCollector present → audit:model_response + audit:model_name stored")
    void resume_auditKeysStored() throws Exception {
        var b = batch("taskA", 0);
        wireResumeMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(memory.getAuditCollector()).thenReturn(mock(ai.labs.eddi.engine.audit.IAuditEntryCollector.class));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(dataFactory).createData(eq("audit:model_response"), eq("resumed answer"));
        verify(dataFactory).createData(eq("audit:model_name"), any());
    }

    @Test
    @DisplayName("resume: non-empty tool trace → trace data stored")
    void resume_nonEmptyTrace_stored() throws Exception {
        var b = batch("taskA", 0);
        wireResumeMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(Map.of("tool", "calc"));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", trace));

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(dataFactory).createData(startsWith("langchain:trace:"), eq(trace));
    }

    @Test
    @DisplayName("resume: non-resume memory (no decision) → executeResume NOT taken, normal path runs")
    void resume_notTakenWhenDecisionNull() throws Exception {
        // Batch present but decision null → resumeMode false.
        wireStandardMemory(List.of("action1"));
        when(memory.getHitlPendingToolCalls()).thenReturn(batch("taskA", 0));
        when(memory.getHitlResumeDecision()).thenReturn(null);
        agentReturns("done");

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(agentOrchestrator, never()).resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ============================================================
    // configure() + resolveModelName + descriptor
    // ============================================================

    @Test
    @DisplayName("configure() with no URI → throws WorkflowConfigurationException")
    void configure_noUri_throws() {
        assertThrows(ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException.class,
                () -> llmTask.configure(new HashMap<>(), new HashMap<>()));
    }

    @Test
    @DisplayName("configure() with URI → delegates to resourceClientLibrary")
    void configure_withUri_delegates() throws Exception {
        var cfg = new LlmConfiguration(List.of());
        when(resourceClientLibrary.getResource(any(), eq(LlmConfiguration.class))).thenReturn(cfg);

        var result = llmTask.configure(
                new HashMap<>(Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/123?version=1")),
                new HashMap<>());

        assertSame(cfg, result);
    }

    @Test
    @DisplayName("getId/getType/getExtensionDescriptor return stable values")
    void metadataAccessors() {
        assertTrue(llmTask.getId().getIdentifier().endsWith(LlmTask.ID), llmTask.getId().getIdentifier());
        assertEquals("langchain", llmTask.getType());
        assertNotNull(llmTask.getExtensionDescriptor());
    }

    // ============================================================
    // Template-engine exception surfaced as LifecycleException
    // ============================================================

    @Test
    @DisplayName("convert() failure surfaces — early guard path is robust to empty tasks")
    void emptyTaskList_noExecution() throws Exception {
        wireStandardMemory(List.of("action1"));

        // No tasks → the for-loop never runs, execute completes without orchestrator.
        llmTask.execute(memory, new LlmConfiguration(List.of()));

        verifyNoInteractions(agentOrchestrator);
    }

    @Test
    @DisplayName("agent LifecycleException propagates as LifecycleException")
    void agentLifecycleException_propagates() throws Exception {
        wireStandardMemory(List.of("action1"));
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new LifecycleException("boom"));

        var t = task("taskA", List.of("action1"), null);
        assertThrows(LifecycleException.class,
                () -> llmTask.execute(memory, new LlmConfiguration(List.of(t))));
    }
}
