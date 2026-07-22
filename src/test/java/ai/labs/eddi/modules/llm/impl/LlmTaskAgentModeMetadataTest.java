/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Agent-mode response metadata surfacing.
 * <p>
 * {@code AgentOrchestrator} sums {@code TokenUsage} across every model call in
 * the tool loop and returns it on {@link AgentOrchestrator.ExecutionResult}.
 * {@code LlmTask} used to read only {@code response()} and {@code trace()}, so
 * all agent-mode token accounting was computed and then dropped — while the
 * legacy chat branches kept theirs. These tests pin the fix.
 * <p>
 * Note the shape of the gap: agent-mode branches were already covered (see
 * {@code LlmTaskCoverage2Test}), but every existing stub built its result with
 * the two-arg {@link AgentOrchestrator.ExecutionResult} convenience
 * constructor, which hardcodes an empty metadata map. Covering the branch was
 * never enough — the metadata dimension had to be exercised explicitly.
 * <p>
 * <strong>Which of these actually discriminate.</strong> Reverting the fix
 * (commit {@code 15b7a08a7}) turns <strong>five of the seven</strong> red —
 * measured, not reasoned. The two that survive are
 * {@code agentMode_emptyMetadata_publishesEmptyMap} and
 * {@code agentReturnsNull_fallsBackToLegacyChatExecutor}; they pin behaviour
 * the fix did not alter and are regression guards, not discriminators. Each
 * says so at its own assertion.
 * <p>
 * The fix has three separately-revertable call sites (the {@code skipCascade}
 * branch, the standard branch, and {@code executeResume}); there is a
 * discriminating test per site, so a partial revert cannot slip through either.
 * <p>
 * If you change any assertion here, <em>re-measure</em> rather than adjusting
 * this count by inspection: an earlier revision of this javadoc claimed "four
 * of six" and was wrong twice over — stale after the defensive copy demoted the
 * empty-metadata test to a guard, and again after the null-trace test added a
 * discriminator back.
 */
@DisplayName("LlmTask agent-mode response metadata")
class LlmTaskAgentModeMetadataTest {

    private static final String METADATA_KEY = "llmMeta";

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
    private ConversationSummarizer conversationSummarizer;
    @Mock
    private CounterweightService counterweightService;
    @Mock
    private IdentityMaskingService identityMaskingService;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private ChatModel chatModel;

    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private LlmTask llmTask;

    /**
     * The live template data map handed to the task — asserting against this
     * instance proves the metadata reached the {@code {{llmMeta}}} namespace, not
     * just the memory entry.
     */
    private Map<String, Object> templateData;

    @BeforeEach
    void setUp() {
        openMocks(this);

        templateData = new HashMap<>();

        lenient().when(promptSnippetService.getAll()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
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
            IData<?> d = mock(IData.class);
            lenient().when(d.getResult()).thenAnswer(x -> inv.getArgument(1));
            return d;
        });
    }

    // ============================================================
    // Helpers
    // ============================================================

    private LlmConfiguration.Task task(String id) {
        var t = new LlmConfiguration.Task();
        t.setId(id);
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        t.setParameters(params);
        t.setResponseMetadataObjectName(METADATA_KEY);
        // Without this, isAgentMode() is false and the real orchestrator would always
        // return null — a non-null agent result would be a shape production could
        // never produce. Keeps the fixtures honest even though the mock ignores it.
        t.setEnableBuiltInTools(true);
        return t;
    }

    private void wireStandardMemory() throws Exception {
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        lenient().when(memory.getHitlPendingToolCalls()).thenReturn(null);
        lenient().when(memory.getHitlResumeDecision()).thenReturn(null);
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        var inputData = mock(IData.class);
        lenient().when(currentStep.getLatestData("input")).thenReturn(inputData);
        lenient().when(inputData.getResult()).thenReturn("user input");
    }

    /** Token usage as the orchestrator actually reports it (shared helper). */
    private Map<String, Object> agentTokenUsage() {
        return AgentOrchestrator.tokenUsageMap(new TokenUsage(120, 30));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedMemoryEntry() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(prePostUtils).createMemoryEntry(eq(currentStep), captor.capture(), eq(METADATA_KEY), eq("langchain"));
        return captor.getValue();
    }

    // ============================================================
    // 1. Standard (non-cascade) agent branch
    // ============================================================

    @Test
    @DisplayName("agent mode surfaces the orchestrator's responseMetadata, including tokenUsage")
    void agentMode_surfacesResponseMetadataWithTokenUsage() throws Exception {
        wireStandardMemory();
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("agent answer", new ArrayList<>(),
                        Map.of("tokenUsage", agentTokenUsage())));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        // Reached the memory entry under the configured responseMetadataObjectName...
        Map<String, Object> stored = capturedMemoryEntry();
        assertEquals(agentTokenUsage(), stored.get("tokenUsage"),
                "agent-mode tokenUsage must survive into responseMetadata");

        // ...and the template namespace, so {{llmMeta.tokenUsage}} resolves.
        assertSame(stored, templateData.get(METADATA_KEY),
                "the same metadata map must be published to the template data");
    }

    @Test
    @DisplayName("agent mode with no metadata still publishes an (empty) metadata map — no NPE")
    void agentMode_emptyMetadata_publishesEmptyMap() throws Exception {
        wireStandardMemory();
        // Two-arg convenience constructor → Map.of(), the shape every pre-existing
        // stub used and the reason the dropped-metadata bug stayed invisible.
        var emptyMetaResult = new AgentOrchestrator.ExecutionResult("agent answer", new ArrayList<>());
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyMetaResult);

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        Map<String, Object> stored = capturedMemoryEntry();
        assertTrue(stored.isEmpty(), "empty orchestrator metadata must surface as an empty map");
        // A copy, never the orchestrator's own instance: the two-arg ExecutionResult
        // constructor yields an immutable Map.of(), and this map is published into
        // conversation memory and the template namespace where later code may write
        // to it. Aliasing it would defer an UnsupportedOperationException to the
        // agent path in production only.
        assertNotSame(emptyMetaResult.responseMetadata(), stored,
                "published metadata must be a defensive copy, not the orchestrator's map");
        // NOTE: unlike the other five, this test passes with and without the fix —
        // pre-fix the published map was LlmTask's own empty HashMap, equally empty and
        // equally a non-alias. It is a guard against NPE and against re-aliasing, not
        // a discriminator.
    }

    // ============================================================
    // 1b. skipCascade agent branch — a SECOND, separately-revertable call site
    // ============================================================

    @Test
    @DisplayName("skipCascade agent branch surfaces responseMetadata too (distinct call site from the standard branch)")
    void skipCascadeAgentMode_surfacesResponseMetadata() throws Exception {
        wireStandardMemory();
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("agent answer", new ArrayList<>(),
                        Map.of("tokenUsage", agentTokenUsage())));

        // A cascade that is configured but disabled for agent mode routes execution
        // down the `else if (skipCascade)` branch, NOT the standard `else` branch —
        // a separate `responseMetadata = agentResult.responseMetadata()` assignment
        // that the standard-branch test above cannot reach.
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(false);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var t = task("taskA"); // the helper already sets enableBuiltInTools → isAgentMode()
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        assertEquals(agentTokenUsage(), capturedMemoryEntry().get("tokenUsage"),
                "skipCascade agent branch must surface tokenUsage as well");
    }

    // ============================================================
    // 1c. Null trace — ExecutionResult does not enforce non-null
    // ============================================================

    @Test
    @DisplayName("agent result with a null trace does not NPE (executeTask matches executeResume's guard)")
    void agentMode_nullTrace_doesNotThrow() throws Exception {
        wireStandardMemory();
        // No production path yields this today — both ExecutionResult construction
        // sites pass a fresh list — but the record permits it and AgentOrchestratorTest
        // already constructs `new ExecutionResult(null, null)` as a legal shape.
        // Unguarded, the `!toolTrace.isEmpty()` check downstream throws.
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("agent answer", null,
                        Map.of("tokenUsage", agentTokenUsage())));

        assertDoesNotThrow(() -> llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA")))));

        // The turn still completes normally — metadata is surfaced as usual.
        assertEquals(agentTokenUsage(), capturedMemoryEntry().get("tokenUsage"),
                "a null trace must not cost the turn its metadata");
    }

    // ============================================================
    // 2. Legacy fallback — agent mode declined (null result)
    // ============================================================

    @Test
    @DisplayName("agent mode returns null → legacy chat executor runs and its metadata is surfaced")
    void agentReturnsNull_fallsBackToLegacyChatExecutor() throws Exception {
        wireStandardMemory();
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(null);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(aiMessage("legacy answer"))
                .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(7, 3)).build())
                .build());

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        // The orchestrator was consulted first, then declined.
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        // The real LegacyChatExecutor ran against the model (it is constructed
        // internally, so this is asserted behaviourally rather than by verify()).
        verify(chatModel).chat(anyList());

        // The surfaced metadata is the legacy executor's, not the agent's.
        Map<String, Object> stored = capturedMemoryEntry();
        assertEquals(Map.of("inputTokens", 7, "outputTokens", 3, "totalTokens", 10), stored.get("tokenUsage"),
                "legacy fallback must surface the ChatModel's own token usage");
    }

    // ============================================================
    // 3. HITL resume continuation
    // ============================================================

    @Test
    @DisplayName("executeResume surfaces the continuation's metadata via responseMetadataObjectName")
    void resumeMode_surfacesContinuationMetadata() throws Exception {
        var batch = new PendingToolCallBatch();
        batch.setLlmTaskId("taskA");
        batch.setLlmTaskIndex(0);
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);

        wireStandardMemory();
        when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        when(memory.getHitlResumeDecision()).thenReturn(decision);
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>(),
                        Map.of("tokenUsage", agentTokenUsage())));

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        Map<String, Object> stored = capturedMemoryEntry();
        assertEquals(agentTokenUsage(), stored.get("tokenUsage"),
                "the continuation's tokenUsage must be surfaced on the resume path too");
        assertSame(stored, templateData.get(METADATA_KEY));
        // The live path must not run — this turn resumed a frozen tool loop.
        verify(agentOrchestrator, never()).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("resume continuation returns null → an empty metadata map is published, not an NPE")
    void resumeMode_nullResult_publishesEmptyMetadata() throws Exception {
        var batch = new PendingToolCallBatch();
        batch.setLlmTaskId("taskA");
        batch.setLlmTaskIndex(0);
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);

        wireStandardMemory();
        when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        when(memory.getHitlResumeDecision()).thenReturn(decision);
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(null);

        llmTask.execute(memory, new LlmConfiguration(List.of(task("taskA"))));

        assertTrue(capturedMemoryEntry().isEmpty(), "a null continuation must still publish an empty metadata map");
    }
}
