/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.orchestration;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolApprovalGateSupport#interimTextOf}: the model's narration in the
 * assistant message that carried the gated tool calls, as persisted with the
 * pause so the paused step (and a reload) can show what the approval is about.
 */
@DisplayName("ToolApprovalGateSupport.interimTextOf")
class ToolApprovalGateSupportInterimTextTest {

    private static ToolExecutionRequest call() {
        return ToolExecutionRequest.builder().id("c1").name("startConversation").arguments("{}").build();
    }

    @Test
    @DisplayName("takes the trailing AiMessage's text alongside its tool calls, stripped")
    void takesTrailingAiText() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("create the agent and test it"),
                AiMessage.builder()
                        .text("  Config checks out — I'll now start a test conversation, which needs your approval.  ")
                        .toolExecutionRequests(List.of(call()))
                        .build());

        assertEquals("Config checks out — I'll now start a test conversation, which needs your approval.",
                ToolApprovalGateSupport.interimTextOf(messages));
    }

    @Test
    @DisplayName("null when the gating message carried no text — a bare tool call")
    void nullWhenNoText() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("do it"),
                AiMessage.from(List.of(call())));

        assertNull(ToolApprovalGateSupport.interimTextOf(messages));
    }

    @Test
    @DisplayName("null when the transcript does not end in an AiMessage, or is empty")
    void nullWhenNotTrailingAi() {
        assertNull(ToolApprovalGateSupport.interimTextOf(List.of(UserMessage.from("hi"))));
        assertNull(ToolApprovalGateSupport.interimTextOf(List.of()));
        assertNull(ToolApprovalGateSupport.interimTextOf(null));
    }

    @Test
    @DisplayName("redacts secrets the model echoed from tool results")
    void redactsSecrets() {
        List<ChatMessage> messages = List.of(
                AiMessage.builder()
                        .text("Using key sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghij to start.")
                        .toolExecutionRequests(List.of(call()))
                        .build());

        String text = ToolApprovalGateSupport.interimTextOf(messages);
        assertFalse(text.contains("sk-ant-api03-abcdefghij"), "the key must not survive into the persisted narration: " + text);
    }

    @Test
    @DisplayName("caps a runaway preamble")
    void capsLongText() {
        String huge = "x".repeat(ToolApprovalGateSupport.INTERIM_TEXT_MAX_CHARS + 500);
        List<ChatMessage> messages = List.of(
                AiMessage.builder().text(huge).toolExecutionRequests(List.of(call())).build());

        String text = ToolApprovalGateSupport.interimTextOf(messages);
        assertEquals(ToolApprovalGateSupport.INTERIM_TEXT_MAX_CHARS, text.length(),
                "the ellipsis counts against the cap — the persisted value never exceeds it");
        assertTrue(text.endsWith("…"));
    }
}
