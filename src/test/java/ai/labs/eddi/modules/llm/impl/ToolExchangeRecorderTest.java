/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolExchangeRecorder — only complete call/result pairs")
class ToolExchangeRecorderTest {

    private static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    @Test
    @DisplayName("nothing recorded → empty exchange")
    void emptyWhenNothingRan() {
        assertTrue(new ToolExchangeRecorder().completedExchange().isEmpty());
    }

    @Test
    @DisplayName("a fully answered batch is reported as the model sent it")
    void completeBatchKeepsOriginalMessage() {
        var a = call("a", "lookup");
        var b = call("b", "placeOrder");
        var ai = AiMessage.from(a, b);
        var recorder = new ToolExchangeRecorder();
        var resultA = ToolExecutionResultMessage.from(a, "found");
        var resultB = ToolExecutionResultMessage.from(b, "placed");
        recorder.record(ai, a, resultA);
        recorder.record(ai, b, resultB);

        assertEquals(List.of(ai, resultA, resultB), recorder.completedExchange());
        assertSame(ai, recorder.completedExchange().getFirst());
    }

    @Test
    @DisplayName("a batch cancelled between two tools reports only the call that has a result")
    void partialBatchDropsUnansweredCall() {
        var a = call("a", "lookup");
        var b = call("b", "placeOrder");
        var ai = AiMessage.from(a, b);
        var recorder = new ToolExchangeRecorder();
        var resultA = ToolExecutionResultMessage.from(a, "found");
        recorder.record(ai, a, resultA);

        List<ChatMessage> exchange = recorder.completedExchange();

        assertEquals(2, exchange.size());
        assertEquals(List.of(a), ((AiMessage) exchange.get(0)).toolExecutionRequests(), "no dangling call for the tool that never answered");
        assertEquals(resultA, exchange.get(1));
    }

    @Test
    @DisplayName("rounds stay in order and each keeps its own calls")
    void roundsInOrder() {
        var a = call("a", "lookup");
        var b = call("b", "placeOrder");
        var first = AiMessage.from(a);
        var second = AiMessage.from(b);
        var recorder = new ToolExchangeRecorder();
        var resultA = ToolExecutionResultMessage.from(a, "found");
        var resultB = ToolExecutionResultMessage.from(b, "placed");
        recorder.record(first, a, resultA);
        recorder.record(second, b, resultB);

        assertEquals(List.of(first, resultA, second, resultB), recorder.completedExchange());
    }

    @Test
    @DisplayName("null ids get a synthetic id shared by the call and its result, like a returned exchange")
    void nullIdsArePaired() {
        var a = ToolExecutionRequest.builder().name("placeOrder").arguments("{}").build();
        var recorder = new ToolExchangeRecorder();
        recorder.record(AiMessage.from(a), a, ToolExecutionResultMessage.from(null, "placeOrder", "placed"));

        List<ChatMessage> exchange = recorder.completedExchange();
        String callId = ((AiMessage) exchange.get(0)).toolExecutionRequests().getFirst().id();
        assertNotNull(callId);
        assertEquals(callId, ((ToolExecutionResultMessage) exchange.get(1)).id());
    }
}
