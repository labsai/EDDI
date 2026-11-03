/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
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
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a resume leaves behind for a client that is watching and for a receipt
 * rebuilt later: {@code tool_result} for a declined call, and trace entries
 * that keep the call id, the reviewer's own note and the redacted arguments.
 */
@DisplayName("ToolLoopResumer — stored trace identity and live tool_result events")
class ToolLoopResumerTraceAndEventsTest {

    private static final String RAW_SECRET = "aaaaaaaaaaaaaaaaaaaa";
    private static final String ARGS = "{\"expression\":\"1+1\",\"password\":\"" + RAW_SECRET + "\"}";

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        var tenantQuotaService = mock(TenantQuotaService.class);
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        orchestrator = new AgentOrchestrator(mock(CalculatorTool.class), mock(DateTimeTool.class), mock(WebSearchTool.class),
                mock(DataFormatterTool.class), mock(WebScraperTool.class), mock(TextSummarizerTool.class), mock(PdfReaderTool.class),
                mock(WeatherTool.class), mock(FetchToolResponsePageTool.class), mock(ToolExecutionService.class),
                mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class), mock(IWorkflowStore.class),
                mock(IResourceClientLibrary.class), mock(IApiCallExecutor.class), mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class), tenantQuotaService,
                mock(MemorySnapshotService.class), null, null, null, null, null, mock(IHitlToolJournalStore.class),
                new ConversationHistoryBuilder(), new TokenCounterFactory());
    }

    private static PendingToolCallBatch batchOf(String... ids) {
        var requests = new ArrayList<ToolExecutionRequest>();
        var calls = new ArrayList<PendingToolCallBatch.PendingToolCall>();
        for (String id : ids) {
            requests.add(ToolExecutionRequest.builder().id(id).name("calculate").arguments(ARGS).build());
            var c = new PendingToolCallBatch.PendingToolCall();
            c.setCallId(id);
            c.setToolName("calculate");
            c.setSource("builtin");
            c.setArgumentsRaw(ARGS);
            c.setArgsTruncated(false);
            calls.add(c);
        }
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llmTask-A");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(0);
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        batch.setAutoApproveCount(0);
        batch.setCalls(calls);
        batch.setTraceSoFar(new ArrayList<>());
        List<ChatMessage> transcript = new ArrayList<>();
        transcript.add(UserMessage.from("do it"));
        transcript.add(AiMessage.from(requests));
        var serialized = new ChatTranscriptCodec().serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        batch.setChatTranscriptJson(serialized.json());
        batch.setTranscriptOmitted(serialized.omitted());
        return batch;
    }

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    private static ToolCallDecision perCall(HitlDecision.HitlVerdict verdict, String note) {
        var d = new ToolCallDecision();
        d.setVerdict(verdict);
        d.setNote(note);
        return d;
    }

    private static HitlDecision decision(HitlDecision.HitlVerdict verdict, String note) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        d.setNote(note);
        d.setDecidedBy("reviewer-1");
        return d;
    }

    private List<Map<String, Object>> resume(ConversationMemory memory, PendingToolCallBatch batch, HitlDecision decision) throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());
        return orchestrator.resumeToolLoop(chatModel, task(), memory, batch, decision, true).trace();
    }

    private static Map<String, Object> entry(List<Map<String, Object>> trace, String type, String callId) {
        return trace.stream().filter(e -> type.equals(e.get("type")) && callId.equals(e.get("callId"))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("a rejected call: callId, the reviewer's own note and the redacted arguments are stored; tool_result is rejected")
    void rejectedCall() throws Exception {
        var sink = new ToolLoopRunnerToolEventsTest.RecordingSink();
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.setEventSink(sink);
        var decision = decision(HitlDecision.HitlVerdict.REJECTED, "top-level note");
        decision.setToolDecisions(Map.of("c1", perCall(HitlDecision.HitlVerdict.REJECTED, "its own note")));

        var trace = resume(memory, batchOf("c1", "c2"), decision);

        var rejected = entry(trace, "hitl_rejected", "c1");
        assertNotNull(rejected);
        assertEquals("calculate", rejected.get("tool"));
        assertEquals("its own note", rejected.get("note"));
        String args = String.valueOf(rejected.get("arguments"));
        assertTrue(args.contains("1+1"), "the declined arguments are kept so a receipt can name them");
        assertFalse(args.contains(RAW_SECRET), "and redacted like the tool_call step: " + args);

        // c2 is not listed: it inherits the verdict, but the entry carries no note of
        // its own
        var inherited = entry(trace, "hitl_rejected", "c2");
        assertNotNull(inherited);
        assertFalse(inherited.containsKey("note"));

        assertTrue(sink.events.contains("result:calculate:c1:rejected"), sink.events.toString());
        assertTrue(sink.events.contains("result:calculate:c2:rejected"), sink.events.toString());
        assertFalse(String.join("|", sink.events).contains(RAW_SECRET));
    }

    @Test
    @DisplayName("an approved call is recorded as hitl_approved with its call id")
    void approvedCall() throws Exception {
        var sink = new ToolLoopRunnerToolEventsTest.RecordingSink();
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.setEventSink(sink);

        var trace = resume(memory, batchOf("c1"), decision(HitlDecision.HitlVerdict.APPROVED, null));

        var approved = entry(trace, "hitl_approved", "c1");
        assertNotNull(approved);
        assertEquals("calculate", approved.get("tool"));
        assertNull(entry(trace, "hitl_rejected", "c1"));
        // an approved call is reported like any other: a result for it, never
        // "rejected"
        assertTrue(sink.events.stream().anyMatch(e -> e.startsWith("result:calculate:c1:") && !e.endsWith(":rejected")),
                sink.events.toString());
    }

    @Test
    @DisplayName("a resume with no event sink (a plain resume) still stores the entries and does not fail")
    void withoutSink() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");

        var trace = resume(memory, batchOf("c1"), decision(HitlDecision.HitlVerdict.REJECTED, null));

        assertNotNull(entry(trace, "hitl_rejected", "c1"));
    }
}
