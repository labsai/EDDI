/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.ConversationLogGenerator;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R6: a turn answered with an LLM task's fallback keeps the user's question in
 * the model's history but never the apology, and the history stays a valid
 * alternation of roles.
 */
@DisplayName("ConversationHistoryBuilder — fallback outputs (R6)")
class ConversationHistoryBuilderFallbackTest {

    private final ConversationHistoryBuilder builder = new ConversationHistoryBuilder();

    private static ConversationOutput turn(String input, String output) {
        var o = new ConversationOutput();
        o.put("input", input);
        if (output != null) {
            o.put("output", List.of(output));
        }
        return o;
    }

    /** Steps oldest first; the stack is newest first, like the real one. */
    private static IConversationMemory memoryOf(List<ConversationOutput> outputs, boolean... fallbackFlags) {
        var memory = mock(IConversationMemory.class);
        when(memory.getConversationOutputs()).thenReturn(outputs);
        var stack = mock(IConversationStepStack.class);
        when(stack.size()).thenReturn(outputs.size());
        for (int forward = 0; forward < outputs.size(); forward++) {
            var step = mock(IConversationStep.class);
            List<IData<Object>> flags = new ArrayList<>();
            if (fallbackFlags[forward]) {
                @SuppressWarnings("unchecked")
                IData<Object> flag = mock(IData.class);
                when(flag.getResult()).thenReturn(Boolean.TRUE);
                flags.add(flag);
            }
            when(step.getAllData("llm:fallback:")).thenReturn(flags);
            when(stack.get(outputs.size() - 1 - forward)).thenReturn(step);
        }
        when(memory.getAllSteps()).thenReturn(stack);
        return memory;
    }

    private static String text(ChatMessage message) {
        if (message instanceof AiMessage ai) {
            return ai.text();
        }
        if (message instanceof SystemMessage system) {
            return system.text();
        }
        var user = (UserMessage) message;
        return user.contents().stream().map(c -> ((TextContent) c).text()).reduce("", (a, b) -> a.isEmpty() ? b : a + "|" + b);
    }

    @Test
    @DisplayName("the fallback assistant message is not in the next turn's history; the user's input stays, merged so roles alternate")
    void fallbackIsSkippedAndUsersMerged() {
        var memory = memoryOf(List.of(turn("q1", "answer 1"), turn("q2", "Sorry, I could not answer"), turn("q3", null)), false, true, false);

        List<ChatMessage> messages = builder.buildMessages(memory, "sys", null, -1, true);

        assertTrue(messages.stream().noneMatch(m -> text(m).contains("Sorry")), "the apology must not reach the model");
        // system, q1, answer 1, then q2 and q3 folded into one user message
        assertEquals(4, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals("answer 1", text(messages.get(2)));
        assertInstanceOf(UserMessage.class, messages.get(3));
        assertEquals("q2|q3", text(messages.get(3)));
    }

    @Test
    @DisplayName("without a flag nothing changes: both turns and both answers are sent")
    void unflaggedHistoryIsUntouched() {
        var memory = memoryOf(List.of(turn("q1", "answer 1"), turn("q2", "answer 2")), false, false);

        List<ChatMessage> messages = builder.buildMessages(memory, null, null, -1, true);

        assertEquals(4, messages.size());
        assertEquals("answer 2", text(messages.get(3)));
    }

    @Test
    @DisplayName("the summarized-window path skips it too")
    void summarizedWindowSkipsFallback() {
        var memory = memoryOf(List.of(turn("q0", "old"), turn("q1", "Sorry, I could not answer"), turn("q2", null)), false, true, false);

        List<ChatMessage> messages = builder.buildMessages(memory, "sys", null, -1, true, "summary of turn 0", 1);

        assertTrue(messages.stream().noneMatch(m -> text(m).contains("Sorry")));
        assertEquals(2, messages.size(), "system + one merged user message");
        assertEquals("q1|q2", text(messages.get(1)));
    }

    @Test
    @DisplayName("the token-aware path skips it too")
    void tokenAwareSkipsFallback() {
        var memory = memoryOf(List.of(turn("q1", "answer 1"), turn("q2", "Sorry, I could not answer"), turn("q3", null)), false, true, false);

        List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 100_000, 2, true,
                new TokenCounterFactory().getEstimator("openai", "gpt-4o"));

        assertTrue(messages.stream().noneMatch(m -> text(m).contains("Sorry")));
        assertEquals("q2|q3", text(messages.getLast()));
    }

    @Test
    @DisplayName("the transcript people see keeps the fallback")
    void transcriptKeepsFallback() {
        var memory = memoryOf(List.of(turn("q1", "Sorry, I could not answer")), true);

        var log = new ConversationLogGenerator(memory).generate(-1, true);

        assertTrue(log.getMessages().stream().anyMatch(p -> "assistant".equals(p.getRole())));
    }

    private static ConversationOutput turnWithItems(String input, Object... items) {
        var o = new ConversationOutput();
        o.put("input", input);
        o.put("output", List.of(items));
        return o;
    }

    @Test
    @DisplayName("a step with one task's marked fallback and another task's answer keeps the answer (live and summarized paths)")
    void markedFallbackItemIsDroppedButAnotherTasksAnswerStays() {
        var mixed = turnWithItems("q2", Map.of("text", "Sorry, I could not answer", "fallback", true),
                Map.of("text", "the real answer"));
        var memory = memoryOf(List.of(turn("q1", "answer 1"), mixed, turn("q3", null)), false, true, false);

        List<ChatMessage> live = builder.buildMessages(memory, null, null, -1, true);
        List<ChatMessage> summarized = builder.buildMessages(memory, null, null, -1, true, "summary", 1);

        assertTrue(live.stream().noneMatch(m -> text(m).contains("Sorry")));
        assertTrue(live.stream().anyMatch(m -> text(m).equals("the real answer")), live.toString());
        assertTrue(summarized.stream().noneMatch(m -> text(m).contains("Sorry")));
        assertTrue(summarized.stream().anyMatch(m -> text(m).equals("the real answer")), summarized.toString());
    }

    @Test
    @DisplayName("a configured prompt replaces only the current input; the fallback turn's question stays")
    void promptKeepsTheEarlierQuestion() {
        var memory = memoryOf(List.of(turn("q1", "Sorry, I could not answer"), turn("q2", null)), true, false);

        List<ChatMessage> live = builder.buildMessages(memory, null, "PROMPT", -1, true);
        List<ChatMessage> summarized = builder.buildMessages(memory, null, "PROMPT", -1, true, "summary", 0 + 1);
        List<ChatMessage> tokenAware = builder.buildTokenAwareMessages(memory, null, "PROMPT", 100_000, 2, true,
                new TokenCounterFactory().getEstimator("openai", "gpt-4o"));

        assertEquals("q1|PROMPT", text(live.getLast()));
        assertEquals("q1|PROMPT", text(tokenAware.getLast()));
        assertEquals("PROMPT", text(summarized.getLast()), "the earlier question is inside the summary window here");
    }
}
