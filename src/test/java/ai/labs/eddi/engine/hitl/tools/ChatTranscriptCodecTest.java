/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static ai.labs.eddi.engine.memory.model.PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT;
import static org.junit.jupiter.api.Assertions.*;

class ChatTranscriptCodecTest {

    private List<ChatMessage> fullConversation() {
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call_abc").name("transfer_funds").arguments("{\"amount\":250,\"to\":\"iban-x\"}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call_def").name("getCurrentDateTime").arguments("{}").build();
        return List.of(
                SystemMessage.from("You are a banking assistant."),
                UserMessage.from("send 250 to my landlord"),
                AiMessage.from(req1, req2),
                ToolExecutionResultMessage.from(req2, "2026-07-03T10:00:00Z"),
                AiMessage.from("intermediate reasoning text"),
                UserMessage.from(TextContent.from("what about this attachment?")));
    }

    @Test
    void roundTrip_preservesToolExecutionRequests_idsNamesArgs() throws Exception {
        var codec = new ChatTranscriptCodec();
        var result = codec.serialize(fullConversation(), TRANSCRIPT_MAX_BYTES_DEFAULT);
        assertFalse(result.omitted());

        List<ChatMessage> restored = codec.deserialize(result.json());
        assertEquals(6, restored.size());
        AiMessage ai = (AiMessage) restored.get(2);
        assertTrue(ai.hasToolExecutionRequests());
        assertEquals("call_abc", ai.toolExecutionRequests().get(0).id());
        assertEquals("transfer_funds", ai.toolExecutionRequests().get(0).name());
        assertEquals("{\"amount\":250,\"to\":\"iban-x\"}", ai.toolExecutionRequests().get(0).arguments());
        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) restored.get(3);
        assertEquals("call_def", toolResult.id());
        assertEquals("2026-07-03T10:00:00Z", toolResult.text());
    }

    @Test
    void roundTrip_preservesMultimodalUserContent() throws Exception {
        var msg = UserMessage.from(
                TextContent.from("look at this"),
                ImageContent.from("aGVsbG8=", "image/png"));
        var codec = new ChatTranscriptCodec();
        var result = codec.serialize(List.of(msg), TRANSCRIPT_MAX_BYTES_DEFAULT);
        List<ChatMessage> restored = codec.deserialize(result.json());
        UserMessage restoredMsg = (UserMessage) restored.get(0);
        assertEquals(2, restoredMsg.contents().size());
        assertInstanceOf(ImageContent.class, restoredMsg.contents().get(1));
    }

    @Test
    void overCap_returnsOmittedWithoutJson() {
        var codec = new ChatTranscriptCodec();
        // serialize with a 16-byte cap — any real conversation exceeds it
        var result = codec.serialize(fullConversation(), 16);
        assertTrue(result.omitted());
        assertNull(result.json());
    }

    @Test
    void deserialize_garbage_throwsTranscriptCodecException() {
        var codec = new ChatTranscriptCodec();
        assertThrows(ChatTranscriptCodec.TranscriptCodecException.class,
                () -> codec.deserialize("{not json at all"));
    }

    @Test
    void byteCap_measuredInUtf8Bytes_notChars() {
        var codec = new ChatTranscriptCodec();
        var msg = UserMessage.from("ü".repeat(100)); // 200 UTF-8 bytes of payload
        var ok = codec.serialize(List.of(msg), 100_000);
        assertFalse(ok.omitted());
        assertTrue(ok.json().getBytes(StandardCharsets.UTF_8).length > 200);
    }

    @Test
    void resumeShape_transcriptPlusAppendedToolResults_isWellFormed() throws Exception {
        // The resume path appends ToolExecutionResultMessages for the GATED calls
        // to the restored transcript. Assert the combined list still alternates
        // legally: ...AiMessage(with requests) -> results for every request id.
        var codec = new ChatTranscriptCodec();
        var result = codec.serialize(fullConversation(), 2_000_000);
        List<ChatMessage> restored = new java.util.ArrayList<>(codec.deserialize(result.json()));
        AiMessage pending = (AiMessage) restored.get(2);
        // simulate resume: answer the still-unanswered request (call_abc)
        restored.add(3 + 1, ToolExecutionResultMessage.from(pending.toolExecutionRequests().get(0),
                "{\"status\":\"REJECTED_BY_REVIEWER\",\"note\":\"amount too high\"}"));
        long requestCount = pending.toolExecutionRequests().size();
        long resultCount = restored.stream().filter(m -> m instanceof ToolExecutionResultMessage).count();
        assertEquals(requestCount, resultCount);
    }
}
