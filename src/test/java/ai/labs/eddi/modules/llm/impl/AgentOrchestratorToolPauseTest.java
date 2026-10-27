/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.ConfigReferenceRefusedException;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Tests the tool-approval gate hook inside {@link AgentOrchestrator}'s
 * tool-calling loop (Task 5, Step 3): a gated tool call pauses the loop by
 * throwing {@link ToolApprovalRequiredException}, while ungated calls in the
 * same batch still execute.
 */
class AgentOrchestratorToolPauseTest {

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
    private IAgentStore restAgentStore;
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
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;
    @Mock
    private IHitlToolJournalStore journalStore;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, tenantQuotaService,
                memorySnapshotService,
                null, null, null, null, null,
                journalStore, new ConversationHistoryBuilder(), new TokenCounterFactory());

        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getAgentId()).thenReturn(null);
        when(memory.getAgentVersion()).thenReturn(null);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        // toolResponseTruncator is transparent by default
        when(toolResponseTruncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        // tenant quota always allows
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any()))
                .thenReturn(QuotaCheckResult.OK);
        // executeToolWrapped actually runs the supplied executor so we can verify
        // which tool method was invoked.
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    Supplier<String> sup = inv.getArgument(4);
                    return sup.get();
                });
    }

    /** Task with two built-in tools enabled (calculate + getCurrentDateTime). */
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

    private ChatResponse toolBatch(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    @Test
    @DisplayName("gated call never executes; ungated call in same batch executes exactly once")
    void gatedNotExecuted_ungatedExecutes() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var ungatedReq = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq, ungatedReq));

        assertThrows(ToolApprovalRequiredException.class, () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                task, memory, gateCalculate(), 0));

        // The ungated datetime tool ran once; the gated calculator never ran.
        verify(dateTimeTool, times(1)).getCurrentDateTime(anyString());
        verify(calculatorTool, never()).calculate(anyString());
    }

    /**
     * A tool call waiting for a human is not on the execution clock.
     *
     * <p>
     * The per-tool execution timeout lives inside
     * {@link ToolExecutionService#executeToolWrapped}, and it starts when the
     * supplier is invoked. So the question "can an awaiting-approval call be timed
     * out?" reduces to "does a gated call reach that method at all?" — and it does
     * not: the gate classifies the batch and {@code ToolLoopRunner} throws the
     * pause before any gated call is dispatched.
     * </p>
     *
     * <p>
     * Proved two-sided rather than with a bare {@code never()}, which would pass
     * just as happily if the loop had executed nothing whatsoever: the batch
     * carries one gated call and one ungated one, the task's timeout is set to the
     * tightest bound expressible, and the single execution that does reach the
     * service is asserted to be the ungated one.
     * </p>
     */
    @Test
    @DisplayName("a call waiting for human approval never reaches the timed execution step, so it cannot be timed out")
    void gatedCallNeverEntersTheTimedExecutionStep() {
        var task = twoToolTask();
        task.setDefaultToolTimeoutMs(1);
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var ungatedReq = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq, ungatedReq));

        assertThrows(ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                        task, memory, gateCalculate(), 0));

        var executed = ArgumentCaptor.forClass(ToolInvocation.class);
        verify(toolExecutionService, times(1)).executeToolWrapped(executed.capture(), anyString(), nullable(String.class), any(),
                any(Supplier.class), anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt());

        assertEquals(1, executed.getAllValues().size(),
                "exactly one of the two calls may be executed while the other waits for a human");
        assertEquals("getCurrentDateTime", executed.getValue().dispatchName(),
                "the gated call must never be handed to the timed execution step — a 1ms bound would otherwise "
                        + "expire on it while the human is still deciding");
    }

    @Test
    @DisplayName("thrown exception carries a batch with gated call metadata + transcript + ungated names + llmTaskId")
    void batchCarriesMetadata() {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var ungatedReq = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq, ungatedReq));

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                        task, memory, gateCalculate(), 7));

        PendingToolCallBatch batch = ex.getBatch();
        assertNotNull(batch, "exception must carry a pending batch");
        assertEquals("llmTask-A", batch.getLlmTaskId());
        assertEquals(7, batch.getLlmTaskIndex());
        assertNotNull(batch.getPauseEpoch());

        // one gated call, correct id/name/source/gateReason
        assertEquals(1, batch.getCalls().size());
        var call = batch.getCalls().get(0);
        assertEquals("c1", call.getCallId());
        assertEquals("calculate", call.getToolName());
        assertEquals("builtin", call.getSource());
        assertEquals("calculate", call.getGateReason());
        assertNotNull(call.getArgumentsRedacted());

        // ungated names recorded
        assertEquals(List.of("getCurrentDateTime"), batch.getExecutedUngatedCallNames());

        // transcript serialized and contains the AiMessage's tool request
        assertNotNull(batch.getChatTranscriptJson());
        assertFalse(batch.isTranscriptOmitted());
        assertTrue(batch.getChatTranscriptJson().contains("calculate"));

        assertEquals(1, batch.getPauseCountThisTurn());

        // Fix #1: the batch carries the EXACT effective tool-approval config that
        // gated it, so the post-pause resolvers (timeout/no-progress/pending-message)
        // read the task-scoped config instead of re-deriving from the agent level.
        assertNotNull(batch.getEffectiveToolApprovals(),
                "the batch must persist the effective tool-approval config that gated it");
        assertEquals(List.of("calculate"), batch.getEffectiveToolApprovals().getRequireApproval());
    }

    @Test
    @DisplayName("configured small transcript-max-bytes cap governs serialization: transcript omitted even though it fits the 2MB default")
    void configuredTranscriptMaxBytes_smallCap_omitsTranscript() {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var ungatedReq = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq, ungatedReq));

        // Tiny cap (16 bytes) — the serialized transcript is far larger than this,
        // but comfortably under the 2MB DEFAULT. If the configured value were being
        // ignored (hard-coded default), the transcript would NOT be omitted.
        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                        task, memory, gateCalculate(), 0, 16));

        PendingToolCallBatch batch = ex.getBatch();
        assertNotNull(batch);
        assertTrue(batch.isTranscriptOmitted(), "a 16-byte configured cap must govern serialization, not the 2MB default");
        assertNull(batch.getChatTranscriptJson(), "omitted transcript must not carry a JSON payload");
    }

    @Test
    @DisplayName("oversized raw args set argsTruncated; redacted args never exceed cap")
    void argsTruncation() {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);

        StringBuilder big = new StringBuilder("{\"expression\":\"");
        big.append("9".repeat(PendingToolCallBatch.ARGS_RAW_MAX_BYTES + 1000));
        big.append("\"}");
        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments(big.toString()).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq));

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                        task, memory, gateCalculate(), 0));

        var call = ex.getBatch().getCalls().get(0);
        assertTrue(call.isArgsTruncated(), "argsTruncated must be set when raw args exceed the cap");
        assertTrue(call.getArgumentsRaw().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PendingToolCallBatch.ARGS_RAW_MAX_BYTES);
        assertTrue(
                call.getArgumentsRedacted().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PendingToolCallBatch.ARGS_REDACTED_MAX_BYTES);
    }

    @Test
    @DisplayName("pause-cap reached: no throw, gated calls answered with DENIED envelope, loop continues")
    void pauseCapReached() throws Exception {
        var task = twoToolTask();
        var cfg = gateCalculate();
        cfg.setMaxPausesPerTurn(1);
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        // First model turn: gated tool call. Second turn: plain text (loop ends).
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(gatedReq))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());

        // Pre-seed the pause count at the cap so the gate fails closed.
        doReturn(dataOfInt(1)).when(currentStep).getLatestData("hitl:tool_pause_count");

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                task, memory, cfg, 0);

        assertNotNull(result);
        assertEquals("done", result.response());
        // Gated tool still never actually executed
        verify(calculatorTool, never()).calculate(anyString());
    }

    @Test
    @DisplayName("pause-cap reached: writes a hitl.tool.pause_cap audit entry via the memory audit collector (guard audit)")
    void pauseCapReached_writesGuardAudit() throws Exception {
        var task = twoToolTask();
        var cfg = gateCalculate();
        cfg.setMaxPausesPerTurn(1);
        ChatModel chatModel = mock(ChatModel.class);

        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolBatch(gatedReq))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
        doReturn(dataOfInt(1)).when(currentStep).getLatestData("hitl:tool_pause_count");

        // Audit context present — Task 10 records the guard through the collector.
        var collected = new ArrayList<AuditEntry>();
        when(memory.getUserId()).thenReturn("user-1");
        when(memory.getAuditCollector()).thenReturn(collected::add);

        orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, cfg, 0);

        var guard = collected.stream()
                .filter(e -> "hitl.tool.pause_cap".equals(e.taskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a hitl.tool.pause_cap guard audit entry, got: "
                        + collected.stream().map(AuditEntry::taskId).toList()));
        assertEquals("pause_cap", guard.output().get("guard"));
        assertNotNull(guard.output().get("fingerprint"), "guard audit carries the gated fingerprint");
        assertEquals(true, guard.output().get("automated"));
    }

    @Test
    @DisplayName("null effective config: zero behavior change — gate short-circuits, model text returned")
    void nullConfig_noGate() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("plain answer")).build());

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                task, memory, null, 0);

        assertNotNull(result);
        assertEquals("plain answer", result.response());
    }

    @Test
    @DisplayName("AgentExecutionHelper.executeWithRetry rethrows the SAME ToolApprovalRequiredException, no retry, no wrap")
    void executeWithRetryRethrowsSameInstance() {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        var tare = new ToolApprovalRequiredException("needs approval", batch);

        var task = new LlmConfiguration.Task();
        // A retry config that WOULD retry (3 attempts, long backoff) — the guard
        // must bypass it so no retry sleep occurs.
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(3);
        retry.setBackoffDelayMs(60_000L);
        task.setRetry(retry);

        long start = System.currentTimeMillis();
        var thrown = assertThrows(ToolApprovalRequiredException.class,
                () -> AgentExecutionHelper.executeWithRetry(() -> {
                    throw tare;
                }, task, "x"));
        long elapsed = System.currentTimeMillis() - start;

        assertSame(tare, thrown, "must rethrow the exact same instance, not a wrapped copy");
        assertTrue(elapsed < 5_000, "must not sleep/retry before rethrowing (elapsed=" + elapsed + "ms)");
    }

    @SuppressWarnings("unchecked")
    private IData<Integer> dataOfInt(int v) {
        var d = mock(IData.class);
        when(d.getResult()).thenReturn(v);
        return d;
    }

    // ─── A gated call that is certain to be refused never reaches a human ───

    private static final String DOOMED_MESSAGE = "a request body contains the reference ${vault:anthropic-api-key}, which the agent "
            + "configuration does not write there: it came from conversation data. Send the credential's NAME in a field the "
            + "endpoint documents (for example vaultKeyName).";

    /**
     * Routes {@code calculate} to the given resolver, as an httpcall tool's would
     * be.
     */
    private AgentOrchestrator withResolver(ToolRequestResolver resolver) {
        var spied = spy(orchestrator);
        doAnswer(invocation -> {
            var real = (AgentOrchestrator.ToolSetup) invocation.callRealMethod();
            return new AgentOrchestrator.ToolSetup(real.toolSpecs(), real.toolExecutors(), real.toolSources(),
                    real.builtInSpecs(), real.toolCanonicalNames(), real.toolEndpoints(), Map.of("calculate", resolver));
        }).when(spied).buildToolSetup(any(), any());
        return spied;
    }

    /** Routes each named tool to its resolver. */
    private AgentOrchestrator withResolvers(Map<String, ToolRequestResolver> resolvers) {
        var spied = spy(orchestrator);
        doAnswer(invocation -> {
            var real = (AgentOrchestrator.ToolSetup) invocation.callRealMethod();
            return new AgentOrchestrator.ToolSetup(real.toolSpecs(), real.toolExecutors(), real.toolSources(),
                    real.builtInSpecs(), real.toolCanonicalNames(), real.toolEndpoints(), resolvers);
        }).when(spied).buildToolSetup(any(), any());
        return spied;
    }

    private ToolApprovalsConfig gateBoth() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("calculate", "getCurrentDateTime"));
        return cfg;
    }

    private static HitlDecision decision(HitlDecision.HitlVerdict verdict) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        d.setDecidedBy("reviewer-1");
        return d;
    }

    private static List<ToolExecutionResultMessage> resultsOf(ChatRequest request) {
        return request.messages().stream().filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> (ToolExecutionResultMessage) m).toList();
    }

    /**
     * A transcript with a tool call that has no result — or two — is a 400 at
     * Anthropic and a rejection at OpenAI. So the refused call's result, appended
     * before the pause, has to survive the pause/resume round trip exactly once.
     */
    @Test
    @DisplayName("resume after a mixed batch: the refused call keeps exactly one result and never runs — approve and reject")
    void doomedAndHealthy_resumeKeepsOneResultPerCall() throws Exception {
        for (var verdict : List.of(HitlDecision.HitlVerdict.APPROVED, HitlDecision.HitlVerdict.REJECTED)) {
            reset(calculatorTool, dateTimeTool, journalStore);
            when(journalStore.tryClaim(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
            var task = twoToolTask();
            var doomed = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1\"}").build();
            var healthy = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
            ChatModel pauseModel = mock(ChatModel.class);
            when(pauseModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(doomed, healthy));
            var spied = withResolvers(Map.of("calculate", req -> {
                throw new ConfigReferenceRefusedException(DOOMED_MESSAGE);
            }));

            var ex = assertThrows(ToolApprovalRequiredException.class,
                    () -> spied.executeIfToolsEnabled(pauseModel, "sys", List.of(UserMessage.from("hi")), task, memory, gateBoth(), 0));

            ChatModel resumeModel = mock(ChatModel.class);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);
            when(resumeModel.chat(captor.capture())).thenReturn(toolBatchFreeText("done"));
            spied.resumeToolLoop(resumeModel, task, memory, ex.getBatch(), decision(verdict), true);

            var results = resultsOf(captor.getValue());
            assertEquals(List.of("c1", "c2"), results.stream().map(ToolExecutionResultMessage::id).sorted().toList(),
                    verdict + ": exactly one result per tool-call id");
            var refused = results.stream().filter(r -> "c1".equals(r.id())).findFirst().orElseThrow();
            assertTrue(refused.text().contains("vaultKeyName"), verdict + ": the refusal itself is what is replayed: " + refused.text());
            verify(calculatorTool, never()).calculate(anyString());
            if (verdict == HitlDecision.HitlVerdict.APPROVED) {
                verify(dateTimeTool, times(1)).getCurrentDateTime(anyString());
            } else {
                verify(dateTimeTool, never()).getCurrentDateTime(anyString());
            }
            verify(journalStore, never()).tryClaim(anyString(), anyString(), eq("c1"), anyString(), anyString());
        }
    }

    private ChatResponse toolBatchFreeText(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    @Test
    @DisplayName("each gated call is resolved once: the pin step reuses the screening's resolution")
    void gatedCallIsResolvedOnce() {
        var task = twoToolTask();
        var healthy = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1\"}").build();
        var doomed = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(healthy, doomed));
        var healthyResolutions = new AtomicInteger();
        var failingResolutions = new AtomicInteger();
        var spied = withResolvers(Map.of(
                "calculate", req -> {
                    healthyResolutions.incrementAndGet();
                    return ResolvedRequest.of("POST", "https://eddi.example/x", Map.of(), Map.of(), "{}", true);
                },
                "getCurrentDateTime", req -> {
                    failingResolutions.incrementAndGet();
                    throw new ConfigReferenceRefusedException(DOOMED_MESSAGE);
                }));

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> spied.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, gateBoth(), 0));

        assertEquals(1, healthyResolutions.get(), "the surviving call is resolved once, not once to screen and again to pin");
        assertEquals(1, failingResolutions.get());
        assertEquals(1, ex.getBatch().getCalls().size());
        assertTrue(ex.getBatch().getCalls().get(0).isRequestPinned(), "and it is still pinned from that one resolution");
    }

    @Test
    @DisplayName("a resolve failure that is not the guard's still cannot stop the pause, and is not retried to pin")
    void otherResolveFailureStillPausesUnpinned() {
        var task = twoToolTask();
        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1\"}").build();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq));
        var attempts = new AtomicInteger();
        var spied = withResolvers(Map.of("calculate", req -> {
            attempts.incrementAndGet();
            throw new LifecycleException("boom");
        }));

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> spied.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, gateCalculate(), 0));

        assertFalse(ex.getBatch().getCalls().get(0).isRequestPinned());
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("when the ONLY call of the turn is a doomed gated one: no pause, no pause count, and the model gets the refusal")
    void onlyCallIsDoomed_noPauseAndModelGetsRefusal() throws Exception {
        var task = twoToolTask();
        var doomed = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1\"}").build();
        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(toolBatch(doomed))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("corrected")).build());
        var spied = withResolver(req -> {
            throw new ConfigReferenceRefusedException(DOOMED_MESSAGE);
        });

        var result = spied.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, gateCalculate(), 0);

        assertEquals("corrected", result.response());
        verify(memory, never()).setHitlPendingToolCalls(any());
        verify(calculatorTool, never()).calculate(anyString());
        var results = resultsOf(captor.getAllValues().get(1));
        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).id());
        assertTrue(results.get(0).text().contains("vaultKeyName"), results.get(0).text());
    }

    @Test
    @DisplayName("a gated call whose request the credential-reference guard refuses is refused BEFORE the pause — no approval is asked for it")
    void doomedGatedCall_isRefusedWithoutPausing() throws Exception {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);
        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        var ungatedReq = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(toolBatch(gatedReq, ungatedReq))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("I will correct the request.")).build());

        var spied = withResolver(req -> {
            throw new ConfigReferenceRefusedException(DOOMED_MESSAGE);
        });

        // No ToolApprovalRequiredException: the loop carried on to the model.
        var result = spied.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, gateCalculate(), 0);

        assertEquals("I will correct the request.", result.response());
        verify(calculatorTool, never()).calculate(anyString());
        verify(dateTimeTool, times(1)).getCurrentDateTime(anyString());
        // The model is told why, in the guard's own words, as the tool result.
        var refusal = captor.getAllValues().get(1).messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> (ToolExecutionResultMessage) m)
                .filter(m -> "c1".equals(m.id()))
                .map(ToolExecutionResultMessage::text)
                .findFirst().orElse(null);
        assertNotNull(refusal, "the refused call must receive a tool result");
        assertTrue(refusal.contains("vaultKeyName"), refusal);
        assertTrue(refusal.contains("conversation data"), refusal);
    }

    @Test
    @DisplayName("a gated call whose request merely fails to resolve for any other reason still pauses (approved unpinned, as before)")
    void unresolvableGatedCall_stillPauses() {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);
        var gatedReq = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(gatedReq));

        var spied = withResolver(req -> {
            throw new LifecycleException("could not resolve the request");
        });

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> spied.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, gateCalculate(), 0));
        assertEquals(1, ex.getBatch().getCalls().size());
        assertFalse(ex.getBatch().getCalls().get(0).isRequestPinned());
    }

    @Test
    @DisplayName("in a batch of two gated calls only the refused one is dropped; the other still pauses for approval")
    void doomedAndHealthyGatedCalls_onlyTheHealthyOnePauses() {
        var task = twoToolTask();
        ChatModel chatModel = mock(ChatModel.class);
        var doomed = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"1\"}").build();
        var healthy = ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(doomed, healthy));

        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("calculate", "getCurrentDateTime"));
        var spied = withResolver(req -> {
            throw new ConfigReferenceRefusedException(DOOMED_MESSAGE);
        });

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> spied.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, cfg, 0));

        assertEquals(List.of("c2"), ex.getBatch().getCalls().stream().map(PendingToolCallBatch.PendingToolCall::getCallId).toList());
        verify(calculatorTool, never()).calculate(anyString());
    }

    @Test
    @DisplayName("the pause carries what the run spent before it: priced model rounds and tracked tool cost")
    void pauseCarriesTheSpendSoFar() {
        var task = twoToolTask();
        // 1M input tokens per round at $0.01/1M: each round costs $0.01.
        task.setInputPricePer1M(0.01);
        double[] trackedToolCost = {0.0};
        var tracker = mock(ToolCostTracker.class);
        var metrics = mock(ToolCostTracker.ConversationCostMetrics.class);
        when(metrics.getTotalCost()).thenAnswer(inv -> trackedToolCost[0]);
        when(tracker.getConversationCosts("conv-1")).thenReturn(metrics);
        when(toolExecutionService.getCostTracker()).thenReturn(tracker);
        // The ungated call of round 1 charges $0.005.
        when(dateTimeTool.getCurrentDateTime(anyString())).thenAnswer(inv -> {
            trackedToolCost[0] = 0.005;
            return "2026-10-27T12:00:00Z";
        });
        ChatModel chatModel = mock(ChatModel.class);
        var ungatedReq = ToolExecutionRequest.builder().id("c1").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
        var gatedReq = ToolExecutionRequest.builder().id("c2").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(ungatedReq))).tokenUsage(new TokenUsage(1_000_000, 0)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(gatedReq))).tokenUsage(new TokenUsage(1_000_000, 0)).build());

        var ex = assertThrows(ToolApprovalRequiredException.class,
                () -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), task, memory, gateCalculate(), 0));

        // Two rounds at $0.01 plus the $0.005 tool call: LlmTask records this on the
        // step, so the resume's ceiling checks see it.
        assertEquals(0.025, ex.getCostSoFarUsd(), 1e-12);
        assertEquals(2_000_000L, ((Number) ex.getTokenUsageSoFar().get("inputTokens")).longValue());
    }
}
