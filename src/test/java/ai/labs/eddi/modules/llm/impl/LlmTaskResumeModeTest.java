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
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.*;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Task 8: LlmTask resume mode for TOOL_CALL pauses.
 * <p>
 * When {@code memory.getHitlPendingToolCalls()} and
 * {@code memory.getHitlResumeDecision()} are both non-null, {@code execute}
 * dispatches to {@code executeResume}: it rebuilds the chat model for the
 * paused task, hands the batch to {@code AgentOrchestrator.resumeToolLoop}
 * (mocked here — the real body is Task 9), mirrors the normal store-result
 * path, runs postResponse, and clears the tool-pause state — while BYPASSING
 * every pre-LLM side-effecting step (RAG, preRequest property mutations) that
 * already ran before the pause.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@DisplayName("LlmTask resume mode (tool-call pause)")
class LlmTaskResumeModeTest {

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

        when(promptSnippetService.getAll()).thenReturn(Collections.emptyMap());
        when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        var counterweightService = mock(CounterweightService.class);
        when(counterweightService.apply(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        var identityMaskingService = mock(IdentityMaskingService.class);
        when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

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
                null, null, null, null, null, null, null, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.class));

        // The orchestrator is created internally via `new` — inject the mock so the
        // resume path can be verified without executing the real (Task 9) loop.
        Field orchestratorField = LlmTask.class.getDeclaredField("agentOrchestrator");
        orchestratorField.setAccessible(true);
        orchestratorField.set(llmTask, agentOrchestrator);

        when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData d = mock(IData.class);
            when(d.getResult()).thenReturn(inv.getArgument(1));
            return d;
        });
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

    private LlmConfiguration.Task task(String id, List<String> actions) {
        var t = new LlmConfiguration.Task();
        t.setId(id);
        t.setType("openai");
        t.setActions(actions);
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("addToOutput", "true");
        t.setParameters(params);
        return t;
    }

    private void wireBaseMemory(List<String> actions, PendingToolCallBatch batch, HitlDecision resumeDecision) throws Exception {
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(actions);
        when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        when(memory.getHitlResumeDecision()).thenReturn(resumeDecision);
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
    }

    // ============================================================
    // Resume mode dispatch + store-result mirroring
    // ============================================================

    @Test
    @DisplayName("resume mode consumes the batch via resumeToolLoop and stores the result")
    void resumeConsumesBatchAndStoresResult() throws Exception {
        var b = batch("taskA", 0);
        var d = decision(HitlVerdict.APPROVED);
        wireBaseMemory(List.of("action1"), b, d);
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // The batch is handed to the (Task 9) resume loop exactly once.
        verify(agentOrchestrator).resumeToolLoop(eq(chatModel), eq(t), eq(memory), eq(b), eq(d), anyMap(), anyBoolean(), any());
        // Raw response is stored in step data (mirrors the normal path).
        verify(currentStep, atLeastOnce()).storeData(any());
    }

    @Test
    @DisplayName("resume mode runs postResponse (the final response only exists now)")
    void resumeRunsPostResponse() throws Exception {
        var b = batch("taskA", 0);
        wireBaseMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(prePostUtils).runPostResponse(eq(memory), any(), anyMap(), eq(200), eq(false));
    }

    @Test
    @DisplayName("resume mode clears the tool-pause state after consuming the batch")
    void resumeClearsToolPauseState() throws Exception {
        var b = batch("taskA", 0);
        wireBaseMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        // clearToolPauseState() nulls all three transient fields.
        verify(memory).setHitlPendingToolCalls(null);
        verify(memory).setHitlResumeDecision(null);
        verify(memory).setHitlPauseType(null);
    }

    // ============================================================
    // Pre-LLM bypass checklist — each of these MUST be skipped on resume
    // ============================================================

    @Test
    @DisplayName("resume mode does NOT run vector RAG retrieval")
    void resumeSkipsVectorRag() throws Exception {
        var b = batch("taskA", 0);
        wireBaseMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verifyNoInteractions(ragContextProvider);
    }

    @Test
    @DisplayName("resume mode does NOT run preRequest property instructions")
    void resumeSkipsPreRequest() throws Exception {
        var b = batch("taskA", 0);
        wireBaseMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t = task("taskA", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verify(prePostUtils, never()).executePreRequestPropertyInstructions(any(), any(), any());
    }

    // ============================================================
    // Config drift — task index/id mismatch → fail-safe degradation
    // ============================================================

    @Test
    @DisplayName("index out of bounds → config-drift degradation: public output stored, batch cleared, orchestrator NEVER called")
    void configDriftIndexOutOfBounds() throws Exception {
        // Batch points at index 3, but the config only has one task (index 0).
        var b = batch("taskA", 3);
        wireBaseMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));

        var t = task("taskA", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verifyNoInteractions(agentOrchestrator);
        // Fail-safe: batch cleared so the pipeline continues cleanly.
        verify(memory).setHitlPendingToolCalls(null);
        verify(memory).setHitlResumeDecision(null);
        verify(memory).setHitlPauseType(null);
        // A public output explaining the drift is surfaced to the user.
        verify(currentStep).addConversationOutputList(eq("output"),
                argThat(list -> list.stream().anyMatch(o -> o.toString().contains("configuration changed"))));
    }

    @Test
    @DisplayName("task-id mismatch at the recorded index → config-drift degradation (orchestrator NEVER called)")
    void configDriftIdMismatch() throws Exception {
        // Index 0 is in bounds, but the recorded id no longer matches the task there.
        var b = batch("renamedTask", 0);
        wireBaseMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));

        var t = task("taskA", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        verifyNoInteractions(agentOrchestrator);
        verify(memory).setHitlPendingToolCalls(null);
        verify(currentStep).addConversationOutputList(eq("output"),
                argThat(list -> list.stream().anyMatch(o -> o.toString().contains("configuration changed"))));
    }

    // ============================================================
    // Multi-task ordering — tasks after the resumed index run normally
    // ============================================================

    @Test
    @DisplayName("tasks BEFORE the resumed index are skipped; the resumed index uses resumeToolLoop")
    void tasksBeforeResumedIndexSkipped() throws Exception {
        // Batch points at task index 1; task 0 already ran pre-pause.
        var b = batch("taskB", 1);
        wireBaseMemory(List.of("action1"), b, decision(HitlVerdict.APPROVED));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("resumed answer", new ArrayList<>()));

        var t0 = task("taskA", List.of("action1"));
        var t1 = task("taskB", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t0, t1)));

        // Only the resumed task (index 1) goes through resumeToolLoop; task 0 is not
        // re-run through it.
        verify(agentOrchestrator).resumeToolLoop(any(), eq(t1), any(), eq(b), any(), anyMap(), anyBoolean(), any());
        verify(agentOrchestrator, never()).resumeToolLoop(any(), eq(t0), any(), any(), any(), anyMap(), anyBoolean(), any());
    }

    @Test
    @DisplayName("live path threads the injected eddi.hitl.tool.transcript-max-bytes cap to the orchestrator")
    void livePath_threadsConfiguredTranscriptCap() throws Exception {
        // Regression guard for fix #5 (9d07c525d): the standard live path must pass
        // LlmTask's injected toolTranscriptMaxBytes to executeIfToolsEnabled — not the
        // 2MB default. A revert to a default-constant overload at LlmTask:505 would
        // silently drop the configured cap, and no test previously covered the
        // LlmTask->orchestrator threading (only the orchestrator param directly).
        llmTask.toolTranscriptMaxBytes = 12345;

        // Non-resume memory (no pending batch / decision) so execute() runs the normal
        // task path, not executeResume.
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        when(actionData.getResult()).thenReturn(List.of("action1"));
        when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        when(memory.getHitlPendingToolCalls()).thenReturn(null);
        when(memory.getHitlResumeDecision()).thenReturn(null);
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        // A non-null agent result keeps the standard branch (no legacy fallback).
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("done", new ArrayList<>()));

        var t = task("taskA", List.of("action1"));
        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

        var capCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(),
                capCaptor.capture(), any());
        assertEquals(12345, capCaptor.getValue(),
                "the injected transcript-max-bytes must reach the orchestrator, not the 2MB default");
    }
}
