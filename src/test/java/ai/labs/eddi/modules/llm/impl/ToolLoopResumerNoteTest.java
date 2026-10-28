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
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the model is told about a rejected tool call: a person's note asks for a
 * revision, an automated rejection or a missing note keeps the "declined" text,
 * and a per-call rejection without a note of its own inherits the top-level
 * note.
 */
@DisplayName("ToolLoopResumer: a rejection note asks the model for changes")
class ToolLoopResumerNoteTest {

    private static final String NOTE_TEXT = "The user did not approve this call and left a note. "
            + "If the note asks for changes, call the tool again with exactly those changes applied "
            + "and nothing else changed; the user will be asked to approve the new call. "
            + "If the note declines or asks for something else, do not call it again; reply briefly.";
    private static final String DECLINED_TEXT = "The reviewer declined this action. Do not retry this exact call; "
            + "explain the situation to the user and offer alternatives.";
    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ToolLoopResumer resumer;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        resumer = new ToolLoopResumer(mock(AgentOrchestrator.class), mock(ToolLoopRunner.class), mock(ToolApprovalGateSupport.class),
                new ChatTranscriptCodec(), mock(IHitlToolJournalStore.class));
        var tenantQuotaService = mock(TenantQuotaService.class);
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any())).thenReturn(QuotaCheckResult.OK);
        orchestrator = new AgentOrchestrator(mock(CalculatorTool.class), mock(DateTimeTool.class), mock(WebSearchTool.class),
                mock(DataFormatterTool.class), mock(WebScraperTool.class), mock(TextSummarizerTool.class), mock(PdfReaderTool.class),
                mock(WeatherTool.class), mock(FetchToolResponsePageTool.class), mock(ToolExecutionService.class),
                mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class), mock(IWorkflowStore.class),
                mock(IResourceClientLibrary.class), mock(IApiCallExecutor.class), mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class), tenantQuotaService,
                mock(MemorySnapshotService.class), null, null, null, null, null, mock(IHitlToolJournalStore.class),
                new ConversationHistoryBuilder(), new TokenCounterFactory());
    }

    private static Map<String, Object> envelope(String json) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(json, Map.class);
        return map;
    }

    // ── envelope text ──

    @Test
    @DisplayName("a person's note: the new instruction, the note verbatim")
    void personNote() throws Exception {
        var e = envelope(resumer.rejectionEnvelope("createSession", "use a shorter title", "reviewer-1"));
        assertEquals("REJECTED_BY_REVIEWER", e.get("status"));
        assertEquals("createSession", e.get("tool"));
        assertEquals("use a shorter title", e.get("note"));
        assertEquals(NOTE_TEXT, e.get("instruction"));
    }

    @Test
    @DisplayName("no note, or a blank one: today's text, unchanged")
    void noNote() throws Exception {
        assertEquals(DECLINED_TEXT, envelope(resumer.rejectionEnvelope("t", null, "reviewer-1")).get("instruction"));
        assertEquals(DECLINED_TEXT, envelope(resumer.rejectionEnvelope("t", "   ", "reviewer-1")).get("instruction"));
        assertEquals(DECLINED_TEXT, envelope(resumer.rejectionEnvelope("t", "", null)).get("instruction"));
    }

    @Test
    @DisplayName("an automated decision with a note keeps the declined text, so the model cannot loop on it")
    void automatedWithNote() throws Exception {
        String note = "tool approval is not available during group discussions in this version";
        for (String decider : List.of("system:timeout", "system:group")) {
            var e = envelope(resumer.rejectionEnvelope("t", note, decider));
            assertEquals(DECLINED_TEXT, e.get("instruction"), decider);
            assertEquals(note, e.get("note"));
        }
    }

    @Test
    @DisplayName("a decider that merely contains 'system:' is still a person")
    void systemInsideNameIsAPerson() throws Exception {
        assertEquals(NOTE_TEXT, envelope(resumer.rejectionEnvelope("t", "n", "ops-system:bot")).get("instruction"));
    }

    // ── C2: per-call note fallback, through the real resume loop ──

    private static PendingToolCallBatch batchOf(String... ids) {
        var requests = new ArrayList<ToolExecutionRequest>();
        var calls = new ArrayList<PendingToolCallBatch.PendingToolCall>();
        for (String id : ids) {
            requests.add(ToolExecutionRequest.builder().id(id).name("calculate").arguments("{\"expression\":\"1+1\"}").build());
            var c = new PendingToolCallBatch.PendingToolCall();
            c.setCallId(id);
            c.setToolName("calculate");
            c.setSource("builtin");
            c.setArgumentsRaw("{\"expression\":\"1+1\"}");
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

    private static HitlDecision topLevel(HitlDecision.HitlVerdict verdict, String note, String decidedBy) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        d.setNote(note);
        d.setDecidedBy(decidedBy);
        return d;
    }

    /**
     * Resumes and returns the rejection envelopes the model receives, by call id.
     */
    private Map<String, Map<String, Object>> rejectionsSeenByModel(IConversationMemory memory, PendingToolCallBatch batch, HitlDecision decision)
            throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());
        orchestrator.resumeToolLoop(chatModel, task(), memory, batch, decision, true);
        var seen = new LinkedHashMap<String, Map<String, Object>>();
        for (ChatMessage m : captor.getValue().messages()) {
            if (m instanceof ToolExecutionResultMessage r && r.text().contains("REJECTED_BY_REVIEWER")) {
                seen.put(r.id(), envelope(r.text()));
            }
        }
        return seen;
    }

    @Test
    @DisplayName("C2: a listed REJECTED call without a note gets the top-level note; one with its own keeps it")
    void perCallNoteFallback() throws Exception {
        var decision = topLevel(HitlDecision.HitlVerdict.REJECTED, "shorter title please", "reviewer-1");
        decision.setToolDecisions(Map.of("c1", perCall(HitlDecision.HitlVerdict.REJECTED, null), "c2",
                perCall(HitlDecision.HitlVerdict.REJECTED, "  "), "c3", perCall(HitlDecision.HitlVerdict.REJECTED, "its own note")));

        var seen = rejectionsSeenByModel(new ConversationMemory("conv-1", "agent-1", 1, "user-1"), batchOf("c1", "c2", "c3"), decision);

        assertEquals("shorter title please", seen.get("c1").get("note"));
        assertEquals(NOTE_TEXT, seen.get("c1").get("instruction"));
        assertEquals("shorter title please", seen.get("c2").get("note"));
        assertEquals("its own note", seen.get("c3").get("note"));
    }

    @Test
    @DisplayName("C2: a listed call rejected by its own verdict under an APPROVED top level also falls back to the top-level note")
    void perCallRejectUnderApprovedTop() throws Exception {
        var decision = topLevel(HitlDecision.HitlVerdict.APPROVED, "change the title", "reviewer-1");
        decision.setToolDecisions(Map.of("c1", perCall(HitlDecision.HitlVerdict.REJECTED, null)));

        var seen = rejectionsSeenByModel(new ConversationMemory("conv-1", "agent-1", 1, "user-1"), batchOf("c1"), decision);

        assertEquals("change the title", seen.get("c1").get("note"));
    }

    @Test
    @DisplayName("C2: an unlisted rejected call still inherits the top-level note (unchanged)")
    void unlistedInherits() throws Exception {
        var decision = topLevel(HitlDecision.HitlVerdict.REJECTED, "nope, do X", "reviewer-1");
        var seen = rejectionsSeenByModel(new ConversationMemory("conv-1", "agent-1", 1, "user-1"), batchOf("c1"), decision);
        assertEquals("nope, do X", seen.get("c1").get("note"));
    }

    @Test
    @DisplayName("C2 + C1: an automated top-level note reaches a listed call but keeps the declined text")
    void automatedFallbackKeepsDeclinedText() throws Exception {
        var decision = topLevel(HitlDecision.HitlVerdict.REJECTED, "approval timed out", "system:timeout");
        decision.setToolDecisions(Map.of("c1", perCall(HitlDecision.HitlVerdict.REJECTED, null)));
        var seen = rejectionsSeenByModel(new ConversationMemory("conv-1", "agent-1", 1, "user-1"), batchOf("c1"), decision);
        assertEquals(DECLINED_TEXT, seen.get("c1").get("instruction"));
    }

    // ── through the persistence boundary ──

    @Test
    @DisplayName("a paused conversation saved and loaded as a stored snapshot, then resumed REJECTED with a note: the model's next request carries the instruction and the note")
    void resumeAfterStoreRoundTrip() throws Exception {
        var paused = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        paused.setHitlPendingToolCalls(batchOf("c1"));

        // Save: the snapshot is what the stores persist (as a JSON / BSON document).
        String stored = MAPPER.writeValueAsString(ConversationMemoryUtilities.convertConversationMemory(paused));
        // Load: only the stored document is carried across; nothing of the object
        // above.
        IConversationMemory loaded = ConversationMemoryUtilities
                .convertConversationMemorySnapshot(MAPPER.readValue(stored, ConversationMemorySnapshot.class));
        var batch = loaded.getHitlPendingToolCalls();
        assertNotNull(batch, "the pending batch must survive the store");
        assertEquals("c1", batch.getCalls().getFirst().getCallId());

        var decision = topLevel(HitlDecision.HitlVerdict.REJECTED, "use a shorter title", "reviewer-1");
        var seen = rejectionsSeenByModel(loaded, batch, decision);

        var e = seen.get("c1");
        assertNotNull(e, "the model must receive the rejection as the tool result");
        assertEquals("use a shorter title", e.get("note"));
        assertEquals(NOTE_TEXT, e.get("instruction"));
        assertEquals("calculate", e.get("tool"));
    }
}
