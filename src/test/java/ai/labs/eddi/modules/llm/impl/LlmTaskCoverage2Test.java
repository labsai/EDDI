/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ConversationSummaryConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CounterweightConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.IdentityMaskingConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.*;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Batch-2 branch-coverage tests for {@link LlmTask} and
 * {@link CascadingModelExecutor}, targeting UNCOVERED branches NOT exercised by
 * {@code LlmTaskCoverageTest} / {@code CascadingModelExecutorCoverageTest} /
 * {@code LlmTaskResumeModeTest} / {@code CascadingModelExecutorTest}:
 * <ul>
 * <li>LlmTask: RAG context injection present/throws, prompt-snippet +
 * global-var injection (non-empty), preRequest instructions on the live path,
 * structured-output responseSchema present/absent reinforcement,
 * responseObjectName custom vs default, real cascade-branch execution with
 * agentResult propagation + cascade trace + audit metadata, cascade
 * transcript-cap threading, conversationSummary enabled (prefix / no-prefix /
 * excludeProperties), maxContextTokens token-aware branch, identity-masking +
 * counterweight config application, httpCallRag branch.</li>
 * <li>CascadingModelExecutor: per-step param override affecting trace
 * modelName, three-step double-escalation, structured_output augmentation with
 * an existing system message via {@code execute}.</li>
 * </ul>
 * Constructor + orchestrator-reflection wiring mirrors
 * {@code LlmTaskCoverageTest}. Strictly additive.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@DisplayName("LlmTask coverage 2 (RAG, snippets, schema, cascade, summary)")
class LlmTaskCoverage2Test {

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
    private CounterweightService counterweightService;
    @Mock
    private IdentityMaskingService identityMaskingService;
    @Mock
    private ConversationSummarizer conversationSummarizer;
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

        // Identity/counterweight pass through unchanged by default; individual tests
        // can verify they were invoked with a specific config.
        lenient().when(counterweightService.apply(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                mock(IApiCallExecutor.class), mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), conversationSummarizer,
                promptSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(),
                new SimpleMeterRegistry());

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

    private ChatResponse chatResponse(String text) {
        return ChatResponse.builder().aiMessage(aiMessage(text)).build();
    }

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
        // "input" data drives extractUserInput() → RAG query.
        var inputData = mock(IData.class);
        lenient().when(currentStep.getLatestData("input")).thenReturn(inputData);
        lenient().when(inputData.getResult()).thenReturn("user input");
    }

    private void agentReturns(String response) throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult(response, new ArrayList<>()));
    }

    private void agentReturnsNull() throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(null);
    }

    // ============================================================
    // RAG context injection — present / throws
    // ============================================================

    @Test
    @DisplayName("vector RAG returns non-null context → retrieveContext invoked (system message augmented)")
    void vectorRag_contextPresent_injected() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");
        when(ragContextProvider.retrieveContext(eq(memory), any(), eq("user input"))).thenReturn("some retrieved knowledge");

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(ragContextProvider).retrieveContext(eq(memory), any(), eq("user input"));
        // System message with the appended "## Relevant Context" reaches the
        // orchestrator.
        var sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentOrchestrator).executeIfToolsEnabled(any(), sysCaptor.capture(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        assertTrue(sysCaptor.getValue().contains("some retrieved knowledge"));
    }

    @Test
    @DisplayName("vector RAG retrieval throws → caught, execution continues, orchestrator still called")
    void vectorRag_throws_swallowed() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");
        when(ragContextProvider.retrieveContext(any(), any(), anyString()))
                .thenThrow(new RuntimeException("vector store unavailable"));

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // The catch block keeps the turn alive.
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    /**
     * The branch under test is {@code if (userInput != null)}. The matcher for the
     * user input MUST be {@code nullable(String.class)}: the only invocation a
     * missing guard could produce is {@code retrieveContext(memory, task, null)},
     * and {@code anyString()} does not match null — with it this verification
     * matches zero invocations whether the guard exists or not.
     */
    @Test
    @DisplayName("null user input (no 'input' data) → vector RAG skipped entirely")
    void nullUserInput_ragSkipped() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");
        // Override: no input data → extractUserInput returns null.
        when(currentStep.getLatestData("input")).thenReturn(null);

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(ragContextProvider, never()).retrieveContext(any(), any(), nullable(String.class));
    }

    @Test
    @DisplayName("httpCallRag set → the httpCall RAG discovery branch executes (no match → graceful no-op)")
    void httpCallRag_branchExecutes() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var t = task("taskA", List.of("action1"), null);
        t.setHttpCallRag("searchDocs"); // discovery yields nothing with unstubbed stores → null context

        // Must not throw — the httpCall RAG block is guarded by try/catch.
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    // ============================================================
    // Prompt-snippet + global-var injection (non-empty)
    // ============================================================

    @Test
    @DisplayName("non-empty snippets + vars → both injected into template data map")
    void nonEmptySnippetsAndVars_injected() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");
        var templateData = new HashMap<String, Object>();
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        when(promptSnippetService.getAll()).thenReturn(Map.of("cautious_mode", "be careful"));
        when(globalVariableResolver.getTemplateData()).thenReturn(Map.of("default-model", "gpt-4"));

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        assertEquals(Map.of("cautious_mode", "be careful"), templateData.get("snippets"));
        assertEquals(Map.of("default-model", "gpt-4"), templateData.get("vars"));
    }

    // ============================================================
    // preRequest property instructions on the live path
    // ============================================================

    @Test
    @DisplayName("live path runs executePreRequestPropertyInstructions before the model call")
    void livePath_runsPreRequest() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(prePostUtils).executePreRequestPropertyInstructions(eq(memory), anyMap(), any());
    }

    // ============================================================
    // structured-output reinforcement — responseSchema present / absent
    // ============================================================

    @Test
    @DisplayName("convertToObject=true + responseSchema set → schema-including format instruction reaches the model")
    void convertToObject_withSchema_reinforced() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("{\"a\":1}");

        var t = task("taskA", List.of("action1"),
                Map.of("convertToObject", "true", "responseSchema", "{\"type\":\"object\"}"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        var sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentOrchestrator).executeIfToolsEnabled(any(), sysCaptor.capture(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        assertTrue(sysCaptor.getValue().contains("RESPONSE FORMAT (MANDATORY)"));
        assertTrue(sysCaptor.getValue().contains("{\"type\":\"object\"}"),
                "the explicit schema must be embedded in the reinforced system message");
    }

    @Test
    @DisplayName("convertToObject=true + no responseSchema → generic format instruction (no schema block)")
    void convertToObject_noSchema_genericReinforced() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("{\"a\":1}");

        var t = task("taskA", List.of("action1"), Map.of("convertToObject", "true"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        var sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentOrchestrator).executeIfToolsEnabled(any(), sysCaptor.capture(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        assertTrue(sysCaptor.getValue().contains("starting with '{'"),
                "the generic (no-schema) reinforcement variant must be used");
    }

    // ============================================================
    // responseObjectName — custom vs default (task id)
    // ============================================================

    @Test
    @DisplayName("responseObjectName set → response stored under that key in template data")
    void responseObjectName_customUsed() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("the answer");
        var templateData = new HashMap<String, Object>();
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);

        var t = task("taskA", List.of("action1"), null);
        t.setResponseObjectName("myResult");
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        assertEquals("the answer", templateData.get("myResult"));
    }

    @Test
    @DisplayName("responseObjectName empty → defaults to task id key in template data")
    void responseObjectName_defaultsToTaskId() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("the answer");
        var templateData = new HashMap<String, Object>();
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);

        var t = task("taskA", List.of("action1"), null); // no responseObjectName
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        assertEquals("the answer", templateData.get("taskA"));
    }

    // ============================================================
    // Identity masking + counterweight config application
    // ============================================================

    @Test
    @DisplayName("identityMasking + counterweight configs → both services applied with their configs")
    void identityAndCounterweight_applied() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        var masking = new IdentityMaskingConfig();
        var counterweight = new CounterweightConfig();
        var t = task("taskA", List.of("action1"), null);
        t.setIdentityMasking(masking);
        t.setCounterweight(counterweight);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(identityMaskingService).apply(anyString(), same(masking));
        verify(counterweightService).apply(anyString(), same(counterweight), any());
    }

    @Test
    @DisplayName("channel:tag present → counterweight applied with the resolved channel tag")
    void channelTag_threadedToCounterweight() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");
        var channelData = mock(IData.class);
        when(currentStep.getLatestData("channel:tag")).thenReturn(channelData);
        when(channelData.getResult()).thenReturn("scheduled");

        var t = task("taskA", List.of("action1"), null);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(counterweightService).apply(anyString(), any(), eq("scheduled"));
    }

    // ============================================================
    // maxContextTokens token-aware branch
    // ============================================================

    @Test
    @DisplayName("maxContextTokens > 0 → token-aware windowing branch (still reaches orchestrator)")
    void maxContextTokens_tokenAwareBranch() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");

        // Use a provider type that maps to the approximate estimator (no external
        // tokenizer load) so the token-aware branch is exercised deterministically.
        var t = task("taskA", List.of("action1"), Map.of("model", "llama3"));
        t.setType("ollama");
        t.setMaxContextTokens(4096);
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    // ============================================================
    // conversationSummary enabled — no existing summary / with prefix / exclude
    // props
    // ============================================================

    @Test
    @DisplayName("summary enabled + no existing summary → no prefix, updateIfNeeded still called")
    void summaryEnabled_noExisting_updateCalled() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");
        var props = mock(IConversationMemory.IConversationProperties.class);
        lenient().when(props.get(anyString())).thenReturn(null);
        lenient().when(memory.getConversationProperties()).thenReturn(props);

        var summaryConfig = new ConversationSummaryConfig();
        summaryConfig.setEnabled(true);
        var t = task("taskA", List.of("action1"), null);
        t.setConversationSummary(summaryConfig);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(conversationSummarizer).updateIfNeeded(eq(memory), same(summaryConfig), any());
    }

    @Test
    @DisplayName("summary enabled + existing summary property → summary prefix built, summarizer updated")
    void summaryEnabled_existingSummary_prefixBuilt() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");
        var props = mock(IConversationMemory.IConversationProperties.class);
        when(props.get("conversation:running_summary"))
                .thenReturn(new Property("conversation:running_summary", "prior turns condensed", Scope.conversation));
        when(props.get("conversation:summary_through_step"))
                .thenReturn(new Property("conversation:summary_through_step", 3, Scope.conversation));
        when(memory.getConversationProperties()).thenReturn(props);

        var summaryConfig = new ConversationSummaryConfig();
        summaryConfig.setEnabled(true);
        var t = task("taskA", List.of("action1"), null);
        t.setConversationSummary(summaryConfig);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // The summary-enabled path ran end-to-end (summarizer consulted).
        verify(conversationSummarizer).updateIfNeeded(eq(memory), same(summaryConfig), any());
    }

    @Test
    @DisplayName("summary update throws → caught (non-fatal), turn completes")
    void summaryUpdate_throws_swallowed() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("done");
        var props = mock(IConversationMemory.IConversationProperties.class);
        lenient().when(props.get(anyString())).thenReturn(null);
        lenient().when(props.isEmpty()).thenReturn(true);
        lenient().when(memory.getConversationProperties()).thenReturn(props);
        doThrow(new RuntimeException("summarizer LLM down"))
                .when(conversationSummarizer).updateIfNeeded(any(), any(), any());

        var summaryConfig = new ConversationSummaryConfig();
        summaryConfig.setEnabled(true);
        summaryConfig.setExcludePropertiesFromSummary(true);
        var t = task("taskA", List.of("action1"), null);
        t.setConversationSummary(summaryConfig);

        // The catch block must swallow the summarizer failure.
        assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(t))));
    }

    // ============================================================
    // Real cascade branch — legacy result, agent result, audit, trace, cap
    // threading
    // ============================================================

    @Test
    @DisplayName("cascade enabled (legacy) → CascadingModelExecutor runs the real model, response stored, cascade trace stored")
    void cascadeEnabled_legacy_realExecution() throws Exception {
        wireStandardMemory(List.of("action1"));
        when(chatModel.chat(anyList())).thenReturn(chatResponse("cascade answer"));

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), Map.of("addToOutput", "true"));
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // Legacy cascade drives the ChatModel directly (no agent orchestrator).
        verify(chatModel).chat(anyList());
        // Cascade trace ("langchain:cascade:trace:...") is persisted.
        verify(dataFactory).createData(startsWith("langchain:cascade:trace:"), any());
    }

    @Test
    @DisplayName("cascade + auditCollector → cascade model and a Double confidence under the key LifecycleManager reads")
    void cascadeEnabled_auditMetadataStored() throws Exception {
        wireStandardMemory(List.of("action1"));
        when(chatModel.chat(anyList())).thenReturn(chatResponse("cascade answer"));
        when(memory.getAuditCollector()).thenReturn(mock(ai.labs.eddi.engine.audit.IAuditEntryCollector.class));

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), null);
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(dataFactory).createData(eq(MemoryKeys.AUDIT_CASCADE_MODEL), any());
        // Must be the key LifecycleManager reads, and a Double — its IData<Double> slot
        // used to receive nothing at all while a String went to a key nobody read.
        verify(dataFactory).createData(eq(MemoryKeys.AUDIT_CONFIDENCE), argThat(v -> v instanceof Double));
        verify(dataFactory, never()).createData(eq("audit:cascade_confidence"), any());
        verify(dataFactory, never()).createData(eq("audit:cascade_cost"), any());
    }

    @Test
    @DisplayName("cascade in agent mode (enableInAgentMode) → agentResult propagated, tool mode output added")
    void cascadeEnabled_agentMode_agentResultPropagated() throws Exception {
        wireStandardMemory(List.of("action1"));
        // Agent mode cascade calls the orchestrator (8-arg overload) inside the cascade
        // step.
        when(agentOrchestrator.executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("agent cascade answer", new ArrayList<>()));

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), null);
        t.setEnableBuiltInTools(true); // isAgentMode() true
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // usedToolMode true → the final response is added to output.
        verify(currentStep).addConversationOutputList(eq(LlmTask.MEMORY_OUTPUT_IDENTIFIER), anyList());
    }

    @Test
    @DisplayName("cascade agent mode threads the injected transcript-max-bytes cap into the cascade step")
    void cascadeEnabled_agentMode_threadsTranscriptCap() throws Exception {
        llmTask.toolTranscriptMaxBytes = 54321;
        wireStandardMemory(List.of("action1"));
        when(agentOrchestrator.executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("agent cascade answer", new ArrayList<>()));

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), null);
        t.setEnableBuiltInTools(true);
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        var capCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(agentOrchestrator).executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(),
                capCaptor.capture(), any());
        assertEquals(54321, capCaptor.getValue());
    }

    @Test
    @DisplayName("cascade legacy + eventSink + addToOutput not-false → final cascade response streamed once")
    void cascadeEnabled_legacy_streamsFinal() throws Exception {
        wireStandardMemory(List.of("action1"));
        when(chatModel.chat(anyList())).thenReturn(chatResponse("cascade answer"));
        var eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), Map.of("addToOutput", "true"));
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(eventSink).onToken("cascade answer");
    }

    // ============================================================
    // skipCascade branch — agent mode with cascade disabled in agent mode
    // (cascadeConfigured && isAgentMode && !enableInAgentMode): both the
    // agent-result
    // emit and the buffered legacy-fallback emit forward to the event sink
    // ============================================================

    @Test
    @DisplayName("skipCascade + agent returns null → buffered legacy response streamed to the event sink once")
    void skipCascade_legacyFallback_streamsBufferedResponse() throws Exception {
        wireStandardMemory(List.of("action1"));
        // Agent orchestrator yields no tools → legacy fallback runs the ChatModel.
        agentReturnsNull();
        when(chatModel.chat(anyList())).thenReturn(chatResponse("legacy answer"));
        var eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(false); // agent mode + this → skipCascade == true
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), null);
        t.setEnableBuiltInTools(true); // isAgentMode() true
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // Legacy fallback executed and its buffered response was forwarded once so an
        // SSE client is not left empty.
        verify(chatModel).chat(anyList());
        verify(eventSink).onToken("legacy answer");
    }

    @Test
    @DisplayName("skipCascade + agent result non-null → agent response streamed to the event sink once")
    void skipCascade_agentResult_streamsResponse() throws Exception {
        wireStandardMemory(List.of("action1"));
        agentReturns("agent answer");
        var eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(false); // agent mode + this → skipCascade == true
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), null);
        t.setEnableBuiltInTools(true); // isAgentMode() true
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(eventSink).onToken("agent answer");
    }

    // ============================================================
    // Cascade token-usage surfacing — non-empty tokenUsage reaches
    // responseMetadata (responseMetadataObjectName) AND the audit ledger
    // ============================================================

    @Test
    @DisplayName("cascade legacy step with non-empty tokenUsage → surfaced in responseMetadata + audit:token_usage stored")
    void cascadeEnabled_tokenUsageSurfaced() throws Exception {
        wireStandardMemory(List.of("action1"));
        // The step's ChatResponse carries real token usage → LegacyChatExecutor emits a
        // non-empty tokenUsage map, which the cascade result then surfaces.
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(aiMessage("cascade answer"))
                .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(120, 30)).build()).build());
        when(memory.getAuditCollector()).thenReturn(mock(ai.labs.eddi.engine.audit.IAuditEntryCollector.class));
        var templateData = new HashMap<String, Object>();
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA", List.of("action1"), null);
        t.setResponseMetadataObjectName("meta");
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // (a) tokenUsage surfaced under the configured response-metadata object name.
        var meta = (Map<String, Object>) templateData.get("meta");
        assertNotNull(meta, "response metadata should be stored under the configured object name");
        assertTrue(meta.containsKey("tokenUsage"), "non-empty cascade tokenUsage must be surfaced in responseMetadata");
        // (b) the real counts land under the key LifecycleManager reads into
        // llmDetail.tokenUsage — "audit:cascade_token_usage" had no reader anywhere.
        verify(dataFactory).createData(eq(MemoryKeys.AUDIT_TOKEN_USAGE), argThat(v -> v instanceof Map<?, ?> m
                && Long.valueOf(120L).equals(m.get("inputTokens"))
                && Long.valueOf(30L).equals(m.get("outputTokens"))));
        verify(dataFactory, never()).createData(eq("audit:cascade_token_usage"), any());
    }

    // ============================================================
    // Resume mode — config drift with a NULL decision guard sibling + drift audit
    // warn
    // (drift already covered; here: resume with responseObjectName default + trace
    // empty)
    // ============================================================

    @Test
    @DisplayName("resume: responseObjectName default (task id) used to store the resumed response")
    void resume_responseObjectNameDefaults() throws Exception {
        var b = new PendingToolCallBatch();
        b.setLlmTaskId("taskA");
        b.setLlmTaskIndex(0);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        var templateData = new HashMap<String, Object>();
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        when(memory.getHitlPendingToolCalls()).thenReturn(b);
        var d = new HitlDecision();
        d.setVerdict(HitlVerdict.APPROVED);
        when(memory.getHitlResumeDecision()).thenReturn(d);
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"), null); // no responseObjectName → defaults to id
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        assertEquals("resumed answer", templateData.get("taskA"));
    }

    // ============================================================
    // CascadingModelExecutor — DIFFERENT branches (per-step params, 3-step,
    // structured w/ system)
    // ============================================================

    private static ChatModelRegistry cascadeRegistry(Map<String, ChatModel> byType) {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        try {
            lenient().doAnswer(inv -> byType.get((String) inv.getArgument(0)))
                    .when(registry).getOrCreate(anyString(), anyMap());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    private static IConversationMemory cascadeMemory() {
        IConversationMemory m = mock(IConversationMemory.class);
        lenient().doReturn(null).when(m).getEventSink();
        return m;
    }

    private static AgentOrchestrator cascadeOrchestrator() {
        AgentOrchestrator o = mock(AgentOrchestrator.class);
        try {
            lenient().doReturn(null).when(o)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt(), any());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    private static LlmConfiguration.Task cascadeTask() {
        var task = new LlmConfiguration.Task();
        task.setId("test-task");
        task.setType("openai");
        task.setParameters(Map.of("apiKey", "test-key"));
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        retry.setBackoffDelayMs(10L);
        task.setRetry(retry);
        return task;
    }

    private static List<ChatMessage> cascadeMessages() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(dev.langchain4j.data.message.SystemMessage.from("You are helpful"));
        messages.add(dev.langchain4j.data.message.UserMessage.from("Hello"));
        return messages;
    }

    private static ChatResponse cascadeResponse(String text) {
        return ChatResponse.builder().aiMessage(aiMessage(text)).build();
    }

    private static CascadeStep cascadeStep(String type, Double threshold, Long timeoutMs) {
        var s = new CascadeStep();
        s.setType(type);
        if (threshold != null) {
            s.setConfidenceThreshold(threshold);
        }
        if (timeoutMs != null) {
            s.setTimeoutMs(timeoutMs);
        }
        return s;
    }

    private CascadingModelExecutor cascadeExecutor(ChatModelRegistry registry) {
        return new CascadingModelExecutor(registry, globalVariableResolver, null, new LegacyChatExecutor(),
                new StreamingLegacyChatExecutor(), null);
    }

    @Test
    @DisplayName("cascade step-level parameters override base → trace records the step's model name")
    void cascade_stepParamsOverride_traceModelName() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = cascadeStep("openai", null, 5000L);
        step.setParameters(Map.of("model", "gpt-4o-mini")); // overrides base
        cascade.setSteps(List.of(step));

        ChatModel model = mock(ChatModel.class);
        doReturn(cascadeResponse("answer")).when(model).chat(anyList());

        var result = cascadeExecutor(cascadeRegistry(Map.of("openai", model))).execute(
                cascade, cascadeMessages(), "system",
                Map.of("apiKey", "key", "model", "base-model"), cascadeTask(), cascadeMemory(), cascadeOrchestrator(),
                Map.of(), false, false, false);

        assertEquals("gpt-4o-mini", result.trace().get(0).get("model"),
                "the merged step parameter model must win over the base model in the trace");
    }

    @Test
    @DisplayName("three-step cascade → double escalation then acceptance on the last step")
    void cascade_threeSteps_doubleEscalation() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setSteps(List.of(
                cascadeStep("openai", 0.95, 5000L), // hedge → escalate
                cascadeStep("anthropic", 0.95, 5000L), // hedge → escalate
                cascadeStep("mistral", null, 5000L))); // last → accept

        ChatModel hedge1 = mock(ChatModel.class);
        doReturn(cascadeResponse("I'm not sure, I don't know")).when(hedge1).chat(anyList());
        ChatModel hedge2 = mock(ChatModel.class);
        doReturn(cascadeResponse("I really can't say, unsure")).when(hedge2).chat(anyList());
        ChatModel finalModel = mock(ChatModel.class);
        doReturn(cascadeResponse("Here is a full and detailed final answer covering everything asked.")).when(finalModel).chat(anyList());

        var registry = cascadeRegistry(Map.of("openai", hedge1, "anthropic", hedge2, "mistral", finalModel));

        var result = cascadeExecutor(registry).execute(
                cascade, cascadeMessages(), "system",
                Map.of("apiKey", "key"), cascadeTask(), cascadeMemory(), cascadeOrchestrator(),
                Map.of(), false, false, false);

        assertEquals(2, result.stepUsed(), "should land on the third (last) step");
        assertEquals("mistral", result.modelType());
        assertEquals(3, result.trace().size());
        assertEquals("escalated", result.trace().get(0).get("status"));
        assertEquals("escalated", result.trace().get(1).get("status"));
        assertEquals("accepted", result.trace().get(2).get("status"));
    }

    @Test
    @DisplayName("structured_output augmentation WITH an existing system message (execute-level path)")
    void cascade_structuredOutput_existingSystemMessage() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("structured_output");
        cascade.setSteps(List.of(cascadeStep("openai", null, 5000L)));

        ChatModel model = mock(ChatModel.class);
        // Capture the messages so we can assert the system message got the confidence
        // instruction.
        var msgCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(cascadeResponse("{\"response\": \"final\", \"confidence\": 0.66}")).when(model).chat(anyList());

        var result = cascadeExecutor(cascadeRegistry(Map.of("openai", model))).execute(
                cascade, cascadeMessages(), "You are helpful",
                Map.of("apiKey", "key"), cascadeTask(), cascadeMemory(), cascadeOrchestrator(),
                Map.of(), false, false, false);

        assertEquals("final", result.response());
        assertEquals(0.66, result.confidence(), 0.01);

        verify(model).chat(msgCaptor.capture());
        var sent = (List<ChatMessage>) msgCaptor.getValue();
        var sysText = ((dev.langchain4j.data.message.SystemMessage) sent.get(0)).text();
        assertTrue(sysText.startsWith("You are helpful"), "existing system message preserved");
        assertTrue(sysText.contains("confidence"), "confidence instruction appended to existing system message");
    }

    @Test
    @DisplayName("cascade with a null-type step falls back to the task type for model creation")
    void cascade_nullStepType_fallsBackToTaskType() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        cascade.setSteps(List.of(cascadeStep(null, null, 5000L))); // null type → task.getType() ("openai")

        ChatModel model = mock(ChatModel.class);
        doReturn(cascadeResponse("answer")).when(model).chat(anyList());

        var result = cascadeExecutor(cascadeRegistry(Map.of("openai", model))).execute(
                cascade, cascadeMessages(), "system",
                Map.of("apiKey", "key"), cascadeTask(), cascadeMemory(), cascadeOrchestrator(),
                Map.of(), false, false, false);

        assertEquals("openai", result.modelType(), "null step type resolves to the task type");
    }
}
