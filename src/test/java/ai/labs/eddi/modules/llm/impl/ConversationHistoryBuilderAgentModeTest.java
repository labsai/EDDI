/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H12: what the agent (tool-loop) path takes from the built history. The loop
 * adds its own system message, so it must get the composed one — summary
 * included — and the history minus only that leading message, keeping the
 * windowing gap marker and system-role history entries.
 */
@DisplayName("ConversationHistoryBuilder — agent-mode helpers")
class ConversationHistoryBuilderAgentModeTest {

    private static final String GAP = "[... 4 earlier messages omitted from context — the full conversation is preserved and can be recalled if needed ...]";

    @Test
    @DisplayName("composeSystemMessage appends the summary exactly as the builders do")
    void composeMatchesBuilders() {
        assertEquals("prompt\n\n## SUMMARY", ConversationHistoryBuilder.composeSystemMessage("prompt", "## SUMMARY"));
        assertEquals("## SUMMARY", ConversationHistoryBuilder.composeSystemMessage("", "## SUMMARY"));
        assertEquals("prompt", ConversationHistoryBuilder.composeSystemMessage("prompt", null));
    }

    @Test
    @DisplayName("only the leading system message is removed; the gap marker survives")
    void gapMarkerSurvives() {
        String system = ConversationHistoryBuilder.composeSystemMessage("prompt", "## SUMMARY");
        List<ChatMessage> built = List.of(SystemMessage.from(system), UserMessage.from("anchor"), SystemMessage.from(GAP),
                UserMessage.from("recent"), AiMessage.from("reply"), UserMessage.from("now"));

        List<ChatMessage> forLoop = ConversationHistoryBuilder.withoutLeadingSystemMessage(built, system);

        assertEquals(built.subList(1, built.size()), forLoop,
                "stripping every SystemMessage used to delete the only signal that earlier turns were omitted");
    }

    @Test
    @DisplayName("a leading system-role history entry that is not the prompt is kept")
    void foreignLeadingSystemMessageIsKept() {
        List<ChatMessage> built = List.of(SystemMessage.from("a system-role log entry"), UserMessage.from("hi"));

        assertEquals(built, ConversationHistoryBuilder.withoutLeadingSystemMessage(built, "prompt"));
        assertEquals(built, ConversationHistoryBuilder.withoutLeadingSystemMessage(built, ""));
    }
}
