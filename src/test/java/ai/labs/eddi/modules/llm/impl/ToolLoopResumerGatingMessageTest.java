/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The degraded HITL resume — transcript omitted — and the gating assistant
 * message it replays, from the pause snapshot ({@code buildPendingBatch})
 * through {@link ToolLoopResumer#gatingExchange}. Without the persisted message
 * the resume rebuilt a bare {@code AiMessage.from(requests)}, losing Gemini
 * 3.x's {@code thoughtSignature}; see
 * {@code PendingToolCallBatch#gatingAssistantMessageJson}.
 */
@DisplayName("ToolLoopResumer — the gating assistant message on a degraded resume")
class ToolLoopResumerGatingMessageTest {

    private static final String THINKING_SIGNATURE_KEY = "thinking_signature";
    private static final String SIGNATURE = "CtkBAcu98PBRSHOULDBEECHOEDBACKVERBATIM==";

    private final ChatTranscriptCodec codec = new ChatTranscriptCodec();
    private ToolLoopResumer resumer;

    @BeforeEach
    void setUp() {
        resumer = new ToolLoopResumer(mock(AgentOrchestrator.class), mock(ToolLoopRunner.class),
                mock(ToolApprovalGateSupport.class), codec, mock(IHitlToolJournalStore.class));
    }

    private static ToolExecutionRequest request(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    /**
     * The message as the model emitted it: two calls, narration, and a signature.
     */
    private static AiMessage modelTurn() {
        return AiMessage.builder()
                .text("Checking status, then I'll deploy.")
                .toolExecutionRequests(List.of(request("c1", "getStatus"), request("c2", "deployAgent")))
                .attributes(Map.of(THINKING_SIGNATURE_KEY, SIGNATURE))
                .build();
    }

    private PendingToolCallBatch batchWithPersistedMessage() {
        var batch = new PendingToolCallBatch();
        batch.setGatingAssistantMessageJson(
                codec.serializeMessage(modelTurn(), PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT));
        assertNotNull(batch.getGatingAssistantMessageJson(), "the gating message must be persistable");
        assertTrue(batch.getGatingAssistantMessageJson().contains(SIGNATURE),
                "the persisted document must carry the signature");
        return batch;
    }

    /**
     * The replayed assistant message — always the first element of the exchange.
     */
    private static AiMessage assistantOf(List<ChatMessage> exchange) {
        return assertInstanceOf(AiMessage.class, exchange.getFirst());
    }

    @Test
    @DisplayName("restores the signature and narration from the persisted message")
    void restoresProviderOpaqueFields() {
        AiMessage rebuilt = assistantOf(resumer.gatingExchange(batchWithPersistedMessage(), List.of(request("c2", "deployAgent"))));

        assertEquals(SIGNATURE, rebuilt.attribute(THINKING_SIGNATURE_KEY, String.class),
                "without this the resumed request is the one Gemini 3.x rejects");
        assertEquals("Checking status, then I'll deploy.", rebuilt.text());
    }

    @Test
    @DisplayName("replays the model's original calls in order, and answers the ungated one handled before the pause")
    void keepsOriginalPartsAndAnswersTheUngatedCall() {
        // Gemini places the signature on a specific functionCall part — part 0 of a
        // parallel batch — so the turn is replayed exactly as emitted rather than
        // rebuilt from the gated calls. getStatus was ungated and ran before the pause;
        // its real result was only in the lost transcript, so it gets an explicit
        // "handled before the pause" answer: no dangling call id, no blind repeat.
        List<ChatMessage> exchange = resumer.gatingExchange(batchWithPersistedMessage(), List.of(request("c2", "deployAgent")));

        AiMessage replayed = assistantOf(exchange);
        assertEquals(List.of("c1", "c2"), replayed.toolExecutionRequests().stream().map(ToolExecutionRequest::id).toList(),
                "original parts, original order");
        assertEquals(2, exchange.size(), "the assistant message plus one answer for the one ungated call");
        var answered = assertInstanceOf(ToolExecutionResultMessage.class, exchange.get(1));
        assertEquals("c1", answered.id());
        assertEquals("getStatus", answered.toolName());
        assertEquals(ToolLoopResumer.HANDLED_BEFORE_PAUSE_RESULT, answered.text());
    }

    @Test
    @DisplayName("an all-gated batch needs no synthetic results")
    void allGatedBatchAddsNoResults() {
        List<ChatMessage> exchange = resumer.gatingExchange(batchWithPersistedMessage(),
                List.of(request("c1", "getStatus"), request("c2", "deployAgent")));

        assertEquals(1, exchange.size());
        assertEquals(SIGNATURE, assistantOf(exchange).attribute(THINKING_SIGNATURE_KEY, String.class));
    }

    @Test
    @DisplayName("a persisted message that does not contain the gated call is not trusted")
    void mismatchedMessageFallsBack() {
        List<ChatMessage> exchange = resumer.gatingExchange(batchWithPersistedMessage(), List.of(request("c9", "somethingElse")));

        assertEquals(1, exchange.size());
        AiMessage rebuilt = assistantOf(exchange);
        assertEquals("c9", rebuilt.toolExecutionRequests().getFirst().id());
        assertTrue(rebuilt.attributes().isEmpty(), "another turn's signature must not be borrowed");
    }

    @Test
    @DisplayName("falls back to the bare reconstruction for a batch persisted before the field existed")
    void legacyBatchStillResumes() {
        var legacy = new PendingToolCallBatch(); // gatingAssistantMessageJson == null

        List<ChatMessage> exchange = resumer.gatingExchange(legacy, List.of(request("c2", "deployAgent")));

        assertEquals(1, exchange.size());
        AiMessage rebuilt = assistantOf(exchange);
        assertEquals(1, rebuilt.toolExecutionRequests().size());
        assertEquals("deployAgent", rebuilt.toolExecutionRequests().getFirst().name());
        assertNull(rebuilt.text());
        assertTrue(rebuilt.attributes().isEmpty());
    }

    @Test
    @DisplayName("falls back rather than throwing when the persisted message is corrupt")
    void corruptValueDegradesInsteadOfFailingTheResume() {
        var corrupt = new PendingToolCallBatch();
        corrupt.setGatingAssistantMessageJson("{not json");

        AiMessage rebuilt = assistantOf(resumer.gatingExchange(corrupt, List.of(request("c2", "deployAgent"))));

        assertEquals(1, rebuilt.toolExecutionRequests().size());
        assertTrue(rebuilt.attributes().isEmpty(), "nothing to restore, but the resume still proceeds");
    }

    @Test
    @DisplayName("an over-cap message keeps its signature by shedding text, not by being dropped")
    void overCapMessageShedsTextRatherThanTheSignature() {
        // The signature is the only thing on this message the resume cannot do without.
        // Dropping the whole message to a chatty narration would lose it and 400 the
        // resume — the exact failure the field exists to prevent — so the bulk goes
        // first. The narration is still available to the approver via interimText.
        AiMessage chatty = AiMessage.builder()
                .text("x".repeat(4096))
                .thinking("y".repeat(4096))
                .toolExecutionRequests(List.of(request("c1", "getStatus")))
                .attributes(Map.of(THINKING_SIGNATURE_KEY, SIGNATURE))
                .build();

        String json = codec.serializeMessage(chatty, 512);
        assertNotNull(json, "an over-cap message must still persist its provider fields");
        assertTrue(json.length() <= 512);
        assertTrue(json.contains(SIGNATURE), "the signature is what must survive the cap");

        AiMessage restored = (AiMessage) codec.deserializeMessage(json);
        assertEquals(SIGNATURE, restored.attribute(THINKING_SIGNATURE_KEY, String.class));
        assertNull(restored.text(), "the narration was shed to fit");
        assertEquals("getStatus", restored.toolExecutionRequests().getFirst().name());
    }

    @Test
    @DisplayName("dropped whole only when even the reduced message will not fit")
    void droppedWhenNothingFits() {
        AiMessage message = AiMessage.builder()
                .toolExecutionRequests(List.of(request("c1", "getStatus")))
                .attributes(Map.of(THINKING_SIGNATURE_KEY, SIGNATURE))
                .build();

        assertNull(codec.serializeMessage(message, 8), "a cap nothing can satisfy degrades to the old reconstruction");
        assertNull(codec.serializeMessage(null, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT));
    }

    @Test
    @DisplayName("a mixed batch persists the gating message even though ungated results follow it")
    void mixedBatchPersistsTheGatingMessageThroughBuildPendingBatch() {
        // The real ordering in ToolLoopRunner: the ungated call (getStatus) executes
        // and its result is appended BEFORE buildPendingBatch snapshots the pause, so
        // the transcript ends in a tool result, not in the assistant message. Reading
        // only the last message found nothing to persist here — and a mixed batch is
        // exactly the case the degraded resume has to handle. This drives the real
        // snapshot and the real resume helper end to end, not a hand-built batch.
        ToolExecutionRequest ungated = request("c1", "getStatus");
        ToolExecutionRequest gated = request("c2", "deployAgent");
        // A long earlier turn pushes the transcript over its cap: the only case in
        // which the gating message is written, since a kept transcript carries it.
        List<ChatMessage> transcript = List.of(
                UserMessage.from("background: " + "z".repeat(4_000)),
                UserMessage.from("check health, then deploy"),
                modelTurn(),
                ToolExecutionResultMessage.from(ungated, "{\"status\":\"ok\"}"));
        var gateResult = new ToolApprovalGate.GateResult(List.of(gated), List.of(ungated), Map.of("c2", "deployAgent"));

        PendingToolCallBatch batch = snapshot(transcript, gateResult, 2_000);

        assertTrue(batch.isTranscriptOmitted(), "precondition: this is the degraded-resume case");
        assertNotNull(batch.getGatingAssistantMessageJson(),
                "the gating message sits behind the ungated result and must still be found");
        assertEquals("Checking status, then I'll deploy.", batch.getInterimText(),
                "the approver's narration had the same blind spot");

        List<ChatMessage> exchange = resumer.gatingExchange(batch,
                List.of(ToolLoopResumer.rebuiltRequest(batch.getCalls().getFirst())));
        assertEquals(SIGNATURE, assistantOf(exchange).attribute(THINKING_SIGNATURE_KEY, String.class),
                "the degraded resume must carry the signature for a mixed batch too");
        assertEquals(2, exchange.size(), "and answer the ungated call handled before the pause");
    }

    @Test
    @DisplayName("stops at anything that is not one of this batch's tool results")
    void doesNotReachBackIntoAnEarlierTurn() {
        // A transcript that does not end in a gated batch must not borrow an older
        // assistant message — that would persist another turn's signature.
        List<ChatMessage> transcript = List.of(modelTurn(), UserMessage.from("a later user message " + "z".repeat(4_000)));
        var gateResult = new ToolApprovalGate.GateResult(List.of(request("c2", "deployAgent")), List.of(), Map.of());

        PendingToolCallBatch batch = snapshot(transcript, gateResult, 2_000);

        assertTrue(batch.isTranscriptOmitted());
        assertNull(batch.getGatingAssistantMessageJson());
        assertNull(batch.getInterimText());
    }

    @Test
    @DisplayName("not written when the transcript was kept — it already carries the message")
    void notDuplicatedAlongsideAKeptTranscript() {
        List<ChatMessage> transcript = List.of(UserMessage.from("deploy"), modelTurn());
        var gateResult = new ToolApprovalGate.GateResult(List.of(request("c2", "deployAgent")),
                List.of(request("c1", "getStatus")), Map.of());

        PendingToolCallBatch batch = snapshot(transcript, gateResult, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);

        assertNotNull(batch.getChatTranscriptJson());
        assertNull(batch.getGatingAssistantMessageJson(),
                "a second copy would only add size to a document that must stay under 16 MB");
    }

    private PendingToolCallBatch snapshot(List<ChatMessage> transcript, ToolApprovalGate.GateResult gateResult, int transcriptMaxBytes) {
        var task = new LlmConfiguration.Task();
        task.setId("task-1");
        return new ToolApprovalGateSupport(codec).buildPendingBatch(transcript, gateResult, task,
                mock(IConversationMemory.class), 0, List.of(), new ArrayList<>(), 1, 0, Map.of(), null,
                transcriptMaxBytes, Map.of(), null, Map.of());
    }
}
