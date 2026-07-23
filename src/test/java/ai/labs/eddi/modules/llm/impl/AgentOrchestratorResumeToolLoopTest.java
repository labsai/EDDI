/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Tests {@link AgentOrchestrator#resumeToolLoop} — verdict application, the
 * write-ahead journal protocol (at-most-once), transcript replay + fallback
 * rebuild, and loop continuation / re-pause. Task 9.
 */
class AgentOrchestratorResumeToolLoopTest {

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
    private ToolExecutionService toolExecutionService;
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
    private MemorySnapshotService memorySnapshotService;
    @Mock
    private IHitlToolJournalStore journalStore;

    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;

    private ConversationHistoryBuilder historyBuilder;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        historyBuilder = new ConversationHistoryBuilder();
        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, tenantQuotaService,
                memorySnapshotService,
                null, null, null, null, null,
                journalStore, historyBuilder, new TokenCounterFactory());

        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getAgentId()).thenReturn(null);
        when(memory.getAgentVersion()).thenReturn(null);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(toolResponseTruncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any())).thenReturn(QuotaCheckResult.OK);
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(inv -> {
                    Supplier<String> sup = inv.getArgument(4);
                    return sup.get();
                });
    }

    // ─── helpers ───

    /** Task with calculator + datetime built-in tools; gate requires calculate. */
    private LlmConfiguration.Task twoToolTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    private ToolApprovalsConfig gateCalculate() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("calculate"));
        return cfg;
    }

    private PendingToolCallBatch.PendingToolCall gatedCall(String callId, String tool, String args) {
        var c = new PendingToolCallBatch.PendingToolCall();
        c.setCallId(callId);
        c.setToolName(tool);
        c.setSource("builtin");
        c.setArgumentsRaw(args);
        c.setArgsTruncated(false);
        return c;
    }

    /**
     * Builds a batch whose transcript is a real serialized transcript ending with
     * an AiMessage that carries {@code requests}. Mirrors what the live pause path
     * persists.
     */
    private PendingToolCallBatch batchWith(int iterationIndex, List<PendingToolCallBatch.PendingToolCall> gated,
                                           List<ToolExecutionRequest> aiRequests) {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llmTask-A");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(iterationIndex);
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        batch.setAutoApproveCount(0);
        batch.setCalls(gated);
        batch.setTraceSoFar(new ArrayList<>());

        List<ChatMessage> transcript = new ArrayList<>();
        transcript.add(UserMessage.from("do the thing"));
        transcript.add(AiMessage.from(aiRequests));
        var codec = new ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec();
        var res = codec.serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        batch.setChatTranscriptJson(res.json());
        batch.setTranscriptOmitted(res.omitted());
        return batch;
    }

    private HitlDecision approveAll() {
        var d = new HitlDecision();
        d.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        d.setDecidedBy("reviewer-1");
        return d;
    }

    private HitlDecision rejectAll(String note) {
        var d = new HitlDecision();
        d.setVerdict(HitlDecision.HitlVerdict.REJECTED);
        d.setNote(note);
        d.setDecidedBy("reviewer-1");
        return d;
    }

    private ChatResponse text(String s) {
        return ChatResponse.builder().aiMessage(AiMessage.from(s)).build();
    }

    private ChatResponse toolBatch(ToolExecutionRequest... reqs) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(reqs))).build();
    }

    @SuppressWarnings("unchecked")
    private ai.labs.eddi.engine.memory.IData<Integer> dataOfInt(int v) {
        var d = mock(ai.labs.eddi.engine.memory.IData.class);
        when(d.getResult()).thenReturn(v);
        return d;
    }

    // ─── tests ───

    @Test
    @DisplayName("approve-all: each gated call claimed + executed once + markExecuted, model called again, final text")
    void approveAllExecutesEachOnce() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var r2 = ToolExecutionRequest.builder().id("c2").name("calculate").arguments("{\"expression\":\"2+2\"}").build();
        var batch = batchWith(0,
                List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}"),
                        gatedCall("c2", "calculate", "{\"expression\":\"2+2\"}")),
                List.of(r1, r2));

        when(journalStore.tryClaim(eq("conv-1"), eq("epoch-1"), anyString(), eq("calculate"), eq("reviewer-1")))
                .thenReturn(true);
        // DefaultToolExecutor unwraps the JSON args and binds {expression} to the @P
        // param.
        when(calculatorTool.calculate("6*7")).thenReturn("42");
        when(calculatorTool.calculate("2+2")).thenReturn("4");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("The answers are 42 and 4."));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true);

        assertNotNull(result);
        assertEquals("The answers are 42 and 4.", result.response());
        verify(calculatorTool, times(1)).calculate("6*7");
        verify(calculatorTool, times(1)).calculate("2+2");
        verify(journalStore).tryClaim("conv-1", "epoch-1", "c1", "calculate", "reviewer-1");
        verify(journalStore).tryClaim("conv-1", "epoch-1", "c2", "calculate", "reviewer-1");
        verify(journalStore).markExecuted("conv-1", "epoch-1", "c1", "42");
        verify(journalStore).markExecuted("conv-1", "epoch-1", "c2", "4");
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    /**
     * The HITL resume path rebuilds its own {@code ToolSetup} and threads its own
     * copy of the canonical-name map. If it is missed, prices and cache TTLs are
     * resolved on the live path but not after a human approval — "budget enforced
     * on live turns, ignored after approval" — which is exactly the kind of split
     * that only shows up in production.
     */
    @Test
    @DisplayName("resume: an approved call carries the canonical slug, same as the live path")
    void resumeCarriesCanonicalToolName() throws Exception {
        var task = twoToolTask();
        task.setDefaultRateLimit(100);
        task.setToolRateLimits(Map.of("calculator", 9));
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        when(journalStore.tryClaim(eq("conv-1"), eq("epoch-1"), anyString(), eq("calculate"), eq("reviewer-1")))
                .thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("42"));

        orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true);

        ArgumentCaptor<ToolInvocation> invocation = ArgumentCaptor.forClass(ToolInvocation.class);
        ArgumentCaptor<Integer> rateLimit = ArgumentCaptor.forClass(Integer.class);
        verify(toolExecutionService).executeToolWrapped(invocation.capture(), anyString(), nullable(String.class), any(),
                any(Supplier.class), anyBoolean(), anyBoolean(), anyBoolean(), rateLimit.capture());

        assertEquals("calculate", invocation.getValue().dispatchName());
        assertEquals("calculator", invocation.getValue().canonicalName(),
                "the resume path must resolve the slug too, or pricing and TTLs differ after a human approval");
        assertEquals(9, rateLimit.getValue(),
                "a slug-keyed toolRateLimits entry must bind on the resume path as well");
    }

    @Test
    @DisplayName("reject-all: no executor invocation, rejection envelope carries note, model produces final text")
    void rejectAll() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(text("I could not perform that action."));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, rejectAll("policy forbids this"), true);

        assertEquals("I could not perform that action.", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        verify(journalStore, never()).tryClaim(anyString(), anyString(), anyString(), anyString(), anyString());

        // The messages passed to the model include a REJECTED_BY_REVIEWER result for c1
        // carrying the note.
        var rejectionMsg = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .filter(t -> t.contains("REJECTED_BY_REVIEWER"))
                .findFirst().orElse(null);
        assertNotNull(rejectionMsg, "a REJECTED_BY_REVIEWER result must be appended");
        assertTrue(rejectionMsg.contains("policy forbids this"), "note must be embedded in the envelope");
    }

    @Test
    @DisplayName("mixed + amendment: approved executes with amended args, envelope argsAmendedByReviewer:true; rejected gets note")
    void mixedWithAmendment() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var r2 = ToolExecutionRequest.builder().id("c2").name("calculate").arguments("{\"expression\":\"1+1\"}").build();
        var batch = batchWith(0,
                List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}"),
                        gatedCall("c2", "calculate", "{\"expression\":\"1+1\"}")),
                List.of(r1, r2));

        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED); // default
        decision.setDecidedBy("reviewer-1");
        var d1 = new ToolCallDecision();
        d1.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        d1.setAmendedArguments("{\"expression\":\"6*8\"}");
        var d2 = new ToolCallDecision();
        d2.setVerdict(HitlDecision.HitlVerdict.REJECTED);
        d2.setNote("wrong tool for this");
        decision.setToolDecisions(Map.of("c1", d1, "c2", d2));

        when(journalStore.tryClaim(eq("conv-1"), eq("epoch-1"), eq("c1"), eq("calculate"), eq("reviewer-1")))
                .thenReturn(true);
        when(calculatorTool.calculate("6*8")).thenReturn("48");

        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(text("done"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, decision, true);

        assertEquals("done", result.response());
        // c1 executed with amended args only (JSON unwrapped to the {expression} value)
        verify(calculatorTool, times(1)).calculate("6*8");
        verify(calculatorTool, never()).calculate("6*7");
        verify(calculatorTool, never()).calculate("1+1");
        verify(journalStore, never()).tryClaim(anyString(), anyString(), eq("c2"), anyString(), anyString());

        var msgs = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text()).toList();
        assertTrue(msgs.stream().anyMatch(t -> t.contains("argsAmendedByReviewer") && t.contains("48")),
                "amended envelope must be present");
        assertTrue(msgs.stream().anyMatch(t -> t.contains("REJECTED_BY_REVIEWER") && t.contains("wrong tool for this")),
                "rejection with note must be present");
    }

    @Test
    @DisplayName("journal EXECUTED: replay stored result, executor NOT invoked")
    void journalExecutedReplays() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(false);
        when(journalStore.find("conv-1", "epoch-1", "c1")).thenReturn(Optional.of(
                new IHitlToolJournalStore.JournalEntry("conv-1", "epoch-1", "c1", "calculate",
                        IHitlToolJournalStore.Status.EXECUTED, "42", java.time.Instant.now(), "reviewer-1")));

        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(text("replayed 42"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true);

        assertEquals("replayed 42", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        verify(journalStore, never()).markExecuted(anyString(), anyString(), anyString(), anyString());
        var replayed = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .anyMatch(t -> t.equals("42"));
        assertTrue(replayed, "the stored result must be replayed verbatim");
    }

    @Test
    @DisplayName("journal EXECUTING: outcome-unknown envelope, executor NOT invoked, audit called")
    void journalExecutingOutcomeUnknown() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(false);
        when(journalStore.find("conv-1", "epoch-1", "c1")).thenReturn(Optional.of(
                new IHitlToolJournalStore.JournalEntry("conv-1", "epoch-1", "c1", "calculate",
                        IHitlToolJournalStore.Status.EXECUTING, null, null, "reviewer-1")));

        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(text("uncertain"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true);

        assertEquals("uncertain", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        var unknown = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .anyMatch(t -> t.contains("EXECUTION_OUTCOME_UNKNOWN"));
        assertTrue(unknown, "outcome-unknown envelope must be appended");
        // trace records the outcome-unknown
        assertTrue(result.trace().stream().anyMatch(e -> "hitl_outcome_unknown".equals(e.get("type"))),
                "trace must record hitl_outcome_unknown");
    }

    @Test
    @DisplayName("transcript codec failure: fallback rebuild uses history builder, reconstructed AiMessage carries batch callIds, approved call still executes")
    void transcriptFallbackRebuild() throws Exception {
        var task = twoToolTask();
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llmTask-A");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(0);
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        batch.setCalls(List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")));
        batch.setTraceSoFar(new ArrayList<>());
        // Omitted transcript forces fallback rebuild.
        batch.setTranscriptOmitted(true);
        batch.setChatTranscriptJson(null);

        // History builder reads conversationOutputs — supply an empty list (fresh
        // rebuild path).
        when(memory.getConversationOutputs()).thenReturn(new ArrayList<>());

        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");

        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(text("fallback done"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true);

        assertEquals("fallback done", result.response());
        verify(calculatorTool, times(1)).calculate("6*7");
        verify(memory, atLeastOnce()).getConversationOutputs();
        // trace records the fallback (transcriptRestored=false)
        assertTrue(result.trace().stream().anyMatch(e -> "hitl_resume".equals(e.get("type")) && Boolean.FALSE.equals(e.get("transcriptRestored"))),
                "trace must record hitl_resume with transcriptRestored=false");
        // The reconstructed AiMessage in the request carries the batch's callId c1 so
        // the result binds.
        var boundResult = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).id())
                .anyMatch("c1"::equals);
        assertTrue(boundResult, "the appended result must bind to the reconstructed request id c1");
    }

    @Test
    @DisplayName("re-pause: model's next response has a NEW gated call → fresh ToolApprovalRequiredException, pauseCountThisTurn = old + 1")
    void rePauseOnNewGatedCall() throws Exception {
        var task = twoToolTask();
        // Gate stays active on resume: task-level approval config resolves inside
        // resumeToolLoop.
        task.setToolApprovals(gateCalculate());
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");
        // pause count so far this turn — the re-pause must carry old + 1.
        doReturn(dataOfInt(1)).when(currentStep).getLatestData("hitl:tool_pause_count");

        // On the continuation model call, the model emits a NEW gated calculate call.
        var newGated = ToolExecutionRequest.builder().id("c2").name("calculate").arguments("{\"expression\":\"9*9\"}").build();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(newGated));

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true));

        var newBatch = ex.getBatch();
        assertNotNull(newBatch);
        assertNotEquals("epoch-1", newBatch.getPauseEpoch(), "a fresh pause must have a new epoch");
        assertEquals(2, newBatch.getPauseCountThisTurn(), "pauseCountThisTurn carries from old + 1");
        // first approved call still executed
        verify(calculatorTool, times(1)).calculate("6*7");
    }

    @Test
    @DisplayName("iteration budget: batch.iterationIndex = maxToolIterations - 1 → at most one more model call")
    void iterationBudgetContinuity() throws Exception {
        var task = twoToolTask();
        task.setMaxToolIterations(3);
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        // iterationIndex = 2 → continuation starts at i=3, loop bound is 3 → no further
        // model call.
        var batch = batchWith(2, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        when(journalStore.tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString())).thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("should not be reached"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true);

        assertNotNull(result);
        // The continuation loop makes NO further model call (budget exhausted).
        verify(chatModel, never()).chat(any(ChatRequest.class));
        // Approved call still executed during verdict application.
        verify(calculatorTool, times(1)).calculate("6*7");
    }

    /**
     * The tool-cost baseline used to be snapshotted AFTER the verdict loop, which
     * had already executed (and charged) every human-approved gated call — so the
     * delta reported as {@code toolCostUsd}, and with it the audit ledger's dollar
     * figure, excluded exactly the calls a human explicitly approved.
     */
    @Test
    @DisplayName("resume: an approved gated call's cost is included in toolCostUsd")
    void approvedGatedCallCostIsReported() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var batch = batchWith(0, List.of(gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}")), List.of(r1));

        when(journalStore.tryClaim(eq("conv-1"), eq("epoch-1"), anyString(), eq("calculate"), eq("reviewer-1")))
                .thenReturn(true);
        when(calculatorTool.calculate("6*7")).thenReturn("42");

        // Conversation-scoped tracker, charged from inside executeToolWrapped exactly
        // like the real pipeline does. It already carries spend from BEFORE the pause,
        // which the resumed turn must NOT re-report.
        var metrics = new ToolCostTracker.ConversationCostMetrics("conv-1");
        metrics.addToolCost("earlierTool", 0.005);
        var costTracker = mock(ToolCostTracker.class);
        when(costTracker.getConversationCosts("conv-1")).thenReturn(metrics);
        when(toolExecutionService.getCostTracker()).thenReturn(costTracker);
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(inv -> {
                    metrics.addToolCost("calculate", 0.002);
                    Supplier<String> sup = inv.getArgument(4);
                    return sup.get();
                });

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("42"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true);

        assertEquals(0.002, (Double) result.responseMetadata().get("toolCostUsd"), 1e-9,
                "the approved gated call ran during verdict application — its cost must reach the ledger");
    }

    @Test
    @DisplayName("APPROVED with argsTruncated: NOT_EXECUTED envelope, no claim, no execution")
    void approvedButTruncatedNotExecuted() throws Exception {
        var task = twoToolTask();
        var r1 = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var truncatedCall = gatedCall("c1", "calculate", "{\"expression\":\"6*7\"}");
        truncatedCall.setArgsTruncated(true);
        var batch = batchWith(0, List.of(truncatedCall), List.of(r1));

        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(text("handled"));

        var result = orchestrator.resumeToolLoop(chatModel, task, memory, batch, approveAll(), true);

        assertEquals("handled", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        verify(journalStore, never()).tryClaim(anyString(), anyString(), anyString(), anyString(), anyString());
        var notExecuted = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .anyMatch(t -> t.contains("NOT_EXECUTED"));
        assertTrue(notExecuted, "NOT_EXECUTED envelope must be appended for truncated approved call");
    }
}
