/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
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
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * {@code answerMode: "tool"} through the real orchestrator: tool assembly, the
 * live loop, the approval gate and the HITL resume, with a scripted fake chat
 * model.
 */
@DisplayName("answerMode \"tool\" — the terminal reply tool")
class AgentOrchestratorAnswerToolTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"htmlResponseText\":{\"type\":\"string\"},"
            + "\"quickReplies\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"htmlResponseText\"]}";
    private static final String ANSWER = "{\"htmlResponseText\":\"<p>Done</p>\",\"quickReplies\":[\"More\"]}";

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
    private IWorkflowStore workflowStore;
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
    private SimpleMeterRegistry meters;

    /** Requests the fake model received, in order. */
    private final List<ChatRequest> requests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meters = new SimpleMeterRegistry();
        Metrics.addRegistry(meters);
        orchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool, textSummarizerTool,
                pdfReaderTool, weatherTool, fetchToolResponsePageTool, toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                workflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization, memoryItemConverter, userMemoryStore,
                toolResponseTruncator, tenantQuotaService, memorySnapshotService, null, null, null, null, null, journalStore,
                new ConversationHistoryBuilder(), new TokenCounterFactory());
        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(toolResponseTruncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(4)).get());
        when(dateTimeTool.getCurrentDateTime(anyString())).thenReturn("2026-11-04T10:00:00Z");
        when(calculatorTool.calculate(anyString())).thenReturn("42");
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(meters);
    }

    private static LlmConfiguration.Task task(boolean withTools) {
        var task = new LlmConfiguration.Task();
        task.setId("answer-task");
        task.setType("gemini");
        task.setEnableBuiltInTools(withTools);
        if (withTools) {
            task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));
        }
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        task.setAnswerMode("tool");
        task.setMaxToolContextTokens(-1);
        Map<String, String> parameters = new HashMap<>();
        parameters.put("convertToObject", "true");
        parameters.put("responseSchema", SCHEMA);
        task.setParameters(parameters);
        return task;
    }

    private static ToolExecutionRequest reply(String id, String args) {
        return ToolExecutionRequest.builder().id(id).name("reply").arguments(args).build();
    }

    private static ToolExecutionRequest calculate(String id) {
        return ToolExecutionRequest.builder().id(id).name("calculate").arguments("{\"expression\":\"6*7\"}").build();
    }

    private static ToolExecutionRequest dateTime(String id) {
        return ToolExecutionRequest.builder().id(id).name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
    }

    /** A fake model answering with the given messages in turn. */
    private ChatModel model(AiMessage... script) {
        return new ChatModel() {
            private int next;

            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                AiMessage message = script[Math.min(next++, script.length - 1)];
                return ChatResponse.builder().aiMessage(message).build();
            }
        };
    }

    private AgentOrchestrator.ExecutionResult run(ChatModel model, LlmConfiguration.Task task, ToolApprovalsConfig approvals, String provider)
            throws Exception {
        return orchestrator.executeIfToolsEnabled(model, "sys", List.of(UserMessage.from("hi")), task, memory, approvals, 0,
                PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT, JsonResponseFormatPolicy.of(true, provider, null, SCHEMA));
    }

    private double outcome(String outcome) {
        var counter = meters.find(AnswerTool.METRIC).tag("outcome", outcome).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private void verifyNoToolExecuted() {
        verify(toolExecutionService, never()).executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(),
                any(Supplier.class), anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a reply call ends the loop: its arguments are the answer, one model call, traced as 'answer' without arguments")
    void replyEndsTheLoop() throws Exception {
        var result = run(model(AiMessage.from(List.of(reply("r1", ANSWER)))), task(true), null, "gemini");

        assertEquals(ANSWER, result.response());
        assertEquals(1, requests.size(), "no further model call after the reply");
        assertEquals(List.of(Map.of("type", "answer", "tool", "reply")), result.trace());
        assertTrue(result.toolExchange().isEmpty(), "the reply is the answer, not a tool exchange");
        verifyNoToolExecuted();
        assertEquals(1.0, outcome(AnswerTool.OUTCOME_REPLY));
    }

    @Test
    @DisplayName("the request carries the reply tool with the responseSchema as parameters, forced on Gemini, without JSON mode")
    void requestCarriesTheReplyTool() throws Exception {
        run(model(AiMessage.from(List.of(reply("r1", ANSWER)))), task(true), null, "gemini");

        ChatRequest request = requests.get(0);
        ToolSpecification replySpec = request.toolSpecifications().stream().filter(s -> "reply".equals(s.name())).findFirst().orElseThrow();
        assertTrue(replySpec.parameters().properties().containsKey("htmlResponseText"));
        assertEquals(List.of("htmlResponseText"), replySpec.parameters().required());
        assertTrue(replySpec.description().contains("exactly once"));
        assertEquals(ToolChoice.REQUIRED, request.toolChoice());
        assertNull(request.responseFormat(), "the answer comes as arguments; no response format");
    }

    @Test
    @DisplayName("a provider without a forced tool mode, or answerToolChoice auto, gets the default tool choice")
    void unsupportedProviderOrAutoIsNotForced() throws Exception {
        run(model(AiMessage.from(List.of(reply("r1", ANSWER)))), task(true), null, "ollama");
        assertNull(requests.get(0).toolChoice());

        requests.clear();
        var auto = task(true);
        auto.setAnswerToolChoice("auto");
        run(model(AiMessage.from(List.of(reply("r1", ANSWER)))), auto, null, "openai");
        assertNull(requests.get(0).toolChoice());
    }

    @Test
    @DisplayName("reply next to an ungated call: the other call runs first, then the reply is the answer, no further model call")
    void replyAfterOtherCalls() throws Exception {
        var result = run(model(AiMessage.from(List.of(reply("r1", ANSWER), dateTime("d1")))), task(true), null, "gemini");

        assertEquals(ANSWER, result.response());
        assertEquals(1, requests.size());
        verify(dateTimeTool, times(1)).getCurrentDateTime("UTC");
        // the exchange handed on holds the executed call and its result, never the
        // reply
        assertEquals(2, result.toolExchange().size());
        AiMessage call = (AiMessage) result.toolExchange().get(0);
        assertEquals(List.of("getCurrentDateTime"), call.toolExecutionRequests().stream().map(ToolExecutionRequest::name).toList());
        assertTrue(result.toolExchange().get(1) instanceof ToolExecutionResultMessage);
        assertEquals("answer", result.trace().get(result.trace().size() - 1).get("type"));
    }

    @Test
    @DisplayName("reply next to a gated call: the turn pauses, the reply is discarded and not in the frozen transcript")
    void replyDiscardedByPause() throws Exception {
        var gate = new ToolApprovalsConfig();
        gate.setRequireApproval(List.of("calculate"));
        var model = model(AiMessage.from(List.of(calculate("c1"), reply("r1", ANSWER))));

        var pause = assertThrows(ToolApprovalRequiredException.class, () -> run(model, task(true), gate, "gemini"));

        verify(calculatorTool, never()).calculate(anyString());
        PendingToolCallBatch batch = pause.getBatch();
        assertEquals(List.of("calculate"), batch.getCalls().stream().map(PendingToolCallBatch.PendingToolCall::getToolName).toList());
        List<ChatMessage> frozen = new ChatTranscriptCodec().deserialize(batch.getChatTranscriptJson());
        for (ChatMessage message : frozen) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                assertTrue(ai.toolExecutionRequests().stream().noneMatch(r -> "reply".equals(r.name())), String.valueOf(ai));
            }
        }
        assertEquals(1.0, outcome(AnswerTool.OUTCOME_DISCARDED));
    }

    @Test
    @DisplayName("the approval gate never sees the reply: a gate on every tool does not pause a reply")
    void gateNeverSeesTheReply() throws Exception {
        var gateEverything = new ToolApprovalsConfig();
        gateEverything.setRequireApproval(List.of("*"));

        var result = run(model(AiMessage.from(List.of(reply("r1", ANSWER)))), task(true), gateEverything, "gemini");

        assertEquals(ANSWER, result.response());
        verifyNoToolExecuted();
    }

    @Test
    @DisplayName("rate limits, cache, cost tracking and quotas (all inside executeToolWrapped) never see the reply")
    void executionPipelineNeverSeesTheReply() throws Exception {
        var task = task(true);
        task.setToolRateLimits(Map.of("calculate", 1));
        run(model(AiMessage.from(List.of(dateTime("d1"), reply("r1", ANSWER)))), task, null, "gemini");

        var tools = ArgumentCaptor.forClass(ToolInvocation.class);
        verify(toolExecutionService, times(1)).executeToolWrapped(tools.capture(), anyString(), nullable(String.class), any(),
                any(Supplier.class), anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt());
        assertEquals("getCurrentDateTime", tools.getValue().dispatchName());
    }

    @Test
    @DisplayName("two reply calls in one response: the first is the answer, counted as a duplicate")
    void duplicateReplyFirstWins() throws Exception {
        var result = run(model(AiMessage.from(List.of(reply("r1", ANSWER), reply("r2", "{\"htmlResponseText\":\"second\"}")))),
                task(true), null, "gemini");

        assertEquals(ANSWER, result.response());
        assertEquals(1.0, outcome(AnswerTool.OUTCOME_DUPLICATE));
    }

    @Test
    @DisplayName("a plain-text answer is returned as text, for the convertToObject parse and its invalid-JSON policy")
    void textAnswerFallsThrough() throws Exception {
        var result = run(model(AiMessage.from("<p>plain html</p>")), task(true), null, "ollama");

        assertEquals("<p>plain html</p>", result.response());
        assertEquals(1.0, outcome(AnswerTool.OUTCOME_TEXT));
        var parsed = new ModelOutputParser(new JsonSerialization(new ObjectMapper())).parse(result.response(), true, SCHEMA, null, "t");
        assertEquals(ModelOutputParser.Kind.INVALID, parsed.kind(), "the same path an invalid JSON reply takes in text mode");
    }

    @Test
    @DisplayName("reply arguments that violate the responseSchema are a schema_mismatch, as a JSON text reply would be")
    void schemaMismatchInReplyArguments() throws Exception {
        var result = run(model(AiMessage.from(List.of(reply("r1", "{\"quickReplies\":[\"x\"]}")))), task(true), null, "gemini");

        var parsed = new ModelOutputParser(new JsonSerialization(new ObjectMapper())).parse(result.response(), true, SCHEMA, null, "t");
        assertEquals(ModelOutputParser.Kind.SCHEMA_MISMATCH, parsed.kind(), parsed.reason());
    }

    @Test
    @DisplayName("the iteration budget spent without a reply ends with plain text (never a lost turn)")
    void iterationBudgetWithoutReply() throws Exception {
        var task = task(true);
        task.setMaxToolIterations(2);
        var result = run(model(AiMessage.from(List.of(dateTime("d1")))), task, null, "gemini");

        assertEquals(2, requests.size());
        assertEquals(ToolLoopRunner.iterationBudgetSpentMessage(2), result.response());
        assertEquals(1.0, outcome(AnswerTool.OUTCOME_NO_REPLY));
    }

    @Test
    @DisplayName("a task with no other tool still runs a tool loop with only the reply tool")
    void onlyTheReplyTool() throws Exception {
        var result = run(model(AiMessage.from(List.of(reply("r1", ANSWER)))), task(false), null, "gemini");

        assertNotNull(result, "not the legacy no-tools path");
        assertEquals(ANSWER, result.response());
        assertEquals(List.of("reply"), requests.get(0).toolSpecifications().stream().map(ToolSpecification::name).toList());
    }

    @Test
    @DisplayName("answerMode text (or absent) adds no reply tool")
    void textModeHasNoReplyTool() throws Exception {
        var task = task(true);
        task.setAnswerMode(null);
        run(model(AiMessage.from(ANSWER)), task, null, "gemini");

        assertTrue(requests.get(0).toolSpecifications().stream().noneMatch(s -> "reply".equals(s.name())));
        assertNull(requests.get(0).toolChoice());
    }

    @Test
    @DisplayName("HITL resume offers the reply tool, so a resumed turn ends with a structured answer")
    void resumeOffersTheReply() throws Exception {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("answer-task");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(0);
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        var gated = new PendingToolCallBatch.PendingToolCall();
        gated.setCallId("c1");
        gated.setToolName("calculate");
        gated.setSource("builtin");
        gated.setArgumentsRaw("{\"expression\":\"6*7\"}");
        batch.setCalls(List.of(gated));
        batch.setTraceSoFar(new ArrayList<>());
        List<ChatMessage> transcript = List.of(UserMessage.from("do it"), AiMessage.from(List.of(calculate("c1"))));
        var serialized = new ChatTranscriptCodec().serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        batch.setChatTranscriptJson(serialized.json());
        batch.setTranscriptOmitted(serialized.omitted());
        when(journalStore.tryClaim(eq("conv-1"), eq("epoch-1"), eq("c1"), eq("calculate"), any())).thenReturn(true);
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer");

        var result = orchestrator.resumeToolLoop(model(AiMessage.from(List.of(reply("r1", ANSWER)))), task(true), memory, batch, decision,
                true, JsonResponseFormatPolicy.of(true, "gemini", null, SCHEMA));

        assertEquals(ANSWER, result.response());
        assertTrue(requests.get(0).toolSpecifications().stream().anyMatch(s -> "reply".equals(s.name())));
        assertEquals(ToolChoice.REQUIRED, requests.get(0).toolChoice());
        verify(calculatorTool, times(1)).calculate("6*7");
    }

    @Test
    @DisplayName("a format re-ask is answered through the reply tool too")
    void reaskTakesTheReply() throws Exception {
        var result = orchestrator.reaskFinalAnswer(model(AiMessage.from(List.of(reply("r1", ANSWER)))), List.of(UserMessage.from("again")),
                null, task(true), memory, JsonResponseFormatPolicy.of(true, "gemini", null, SCHEMA));

        assertEquals(ANSWER, result.response());
        assertEquals(ToolChoice.REQUIRED, requests.get(0).toolChoice());
    }

    @Test
    @DisplayName("a runtime tool named like the reply tool is dropped at assembly; the reply tool has no executor")
    void collisionAtAssemblyDropsTheOtherTool() {
        var mcp = ToolSpecification.builder().name("reply").description("an MCP tool").build();
        var other = ToolSpecification.builder().name("lookup").description("d").build();
        ToolExecutor executor = (request, id) -> "should never run";
        var setup = new AgentOrchestrator.ToolSetup(new ArrayList<>(List.of(other, mcp)), Map.of("reply", executor, "lookup", executor),
                Map.of("reply", "mcp", "lookup", "http"), List.of(), Map.of(), Map.of(), Map.of());

        var withReply = AgentOrchestrator.withAnswerTool(setup, task(true));

        assertEquals(List.of("lookup", "reply"), withReply.toolSpecs().stream().map(ToolSpecification::name).toList());
        assertEquals(AnswerTool.DESCRIPTION, withReply.toolSpecs().get(1).description());
        assertFalse(withReply.toolExecutors().containsKey("reply"));
        assertFalse(withReply.toolSources().containsKey("reply"));
        assertEquals(1.0, outcome(AnswerTool.OUTCOME_COLLISION));
    }
}
