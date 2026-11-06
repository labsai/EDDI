/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code tool_result} stream event (B6): what {@link ToolLoopRunner} tells
 * a streaming client once a tool call it announced has finished. Built from the
 * trace entry the call wrote, through the same redaction as the turn-end
 * {@code toolTrace}.
 */
@DisplayName("ToolLoopRunner — tool_result event")
class ToolLoopRunnerToolResultEventTest {

    private IConversationMemory memory;
    private ConversationEventSink sink;
    private List<Map<String, Object>> trace;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
        sink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(sink);
        trace = new ArrayList<>();
        // An entry of an earlier call in the same loop run: never this call's result.
        trace.add(entry("tool_result", "other", "result", "an earlier call's result"));
    }

    private static Map<String, Object> entry(String type, String tool, String key, Object value) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("type", type);
        entry.put("tool", tool);
        entry.put(key, value);
        return entry;
    }

    @Test
    @DisplayName("a tool that ran and answered reports ok with the start of its result")
    void successfulCall() {
        int start = trace.size();
        trace.add(entry("tool_call", "weather", "arguments", "{\"city\":\"Vienna\"}"));
        trace.add(entry("tool_result", "weather", "result", "{\"temp\":21,\"sky\":\"clear\"}"));

        ToolLoopRunner.emitToolResult(memory, "weather", trace, start,
                new ToolLoopRunner.ToolCallResult("governed", "{\"temp\":21,\"sky\":\"clear\"}", false));

        verify(sink).onToolResult("weather", true, "{\"temp\":21,\"sky\":\"clear\"}");
    }

    @Test
    @DisplayName("a refused call (budget) reports not ok, with the refusal as its summary")
    void refusedCall() {
        int start = trace.size();
        trace.add(entry("tool_call", "weather", "arguments", "{}"));
        trace.add(entry("tool_error", "weather", "error", "Budget exceeded for conversation c1"));

        ToolLoopRunner.emitToolResult(memory, "weather", trace, start,
                new ToolLoopRunner.ToolCallResult("Error: Budget exceeded for conversation c1"));

        verify(sink).onToolResult("weather", false, "Budget exceeded for conversation c1");
    }

    @Test
    @DisplayName("a call that did not run (deadline, quota) is not ok even though it wrote a result")
    void notExecutedCall() {
        int start = trace.size();
        trace.add(entry("tool_result", "weather", "result", "Error: the turn deadline has passed"));

        ToolLoopRunner.emitToolResult(memory, "weather", trace, start,
                new ToolLoopRunner.ToolCallResult("x", "Error: the turn deadline has passed", true));

        verify(sink).onToolResult(eq("weather"), eq(false), anyString());
    }

    @Test
    @DisplayName("a result that reports a failure (an HTTP error body) is not ok")
    void failedCall() {
        int start = trace.size();
        String raw = "{\"httpCode\":500,\"error\":\"upstream down\"}";
        trace.add(entry("tool_result", "fetch", "result", raw));

        ToolLoopRunner.emitToolResult(memory, "fetch", trace, start, new ToolLoopRunner.ToolCallResult(raw, raw, false));

        verify(sink).onToolResult(eq("fetch"), eq(false), anyString());
    }

    @Test
    @DisplayName("a result saying the call was not executed (a mock suite's refusal) is not ok")
    void deniedCall() {
        int start = trace.size();
        String raw = "{\"status\":\"NOT_EXECUTED\",\"reason\":\"not mocked\"}";
        trace.add(entry("tool_result", "fetch", "result", raw));

        ToolLoopRunner.emitToolResult(memory, "fetch", trace, start, new ToolLoopRunner.ToolCallResult(raw, raw, false));

        verify(sink).onToolResult(eq("fetch"), eq(false), anyString());
    }

    @Test
    @DisplayName("the summary is redacted like toolTrace and bounded")
    void summaryIsRedactedAndBounded() {
        int start = trace.size();
        String key = "sk-" + "k".repeat(40);
        // Not redacted in the trace entry here, to show the event redacts on its own
        // account rather than trusting what the entry holds.
        trace.add(entry("tool_result", "fetch", "result", "token " + key + " " + "data ".repeat(200)));

        ToolLoopRunner.emitToolResult(memory, "fetch", trace, start, new ToolLoopRunner.ToolCallResult("x", "{}", false));

        var summary = ArgumentCaptor.forClass(String.class);
        verify(sink).onToolResult(eq("fetch"), eq(true), summary.capture());
        assertFalse(summary.getValue().contains("kkkk"), summary.getValue());
        assertTrue(summary.getValue().startsWith("token sk-<REDACTED> data"), summary.getValue());
        assertTrue(summary.getValue().length() <= 200, "length " + summary.getValue().length());
    }

    @Test
    @DisplayName("an entry of an earlier call is never reported for this one")
    void onlyThisCallsEntries() {
        int start = trace.size();

        ToolLoopRunner.emitToolResult(memory, "weather", trace, start, new ToolLoopRunner.ToolCallResult("x", "{}", false));

        verify(sink).onToolResult("weather", false, "");
    }

    @Test
    @DisplayName("without a streaming client nothing is sent")
    void noSink() {
        when(memory.getEventSink()).thenReturn(null);

        assertDoesNotThrow(() -> ToolLoopRunner.emitToolResult(memory, "weather", trace, 0, null));
        assertDoesNotThrow(() -> ToolLoopRunner.emitToolResult(null, "weather", trace, 0, null));
        verify(sink, never()).onToolResult(anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("a failing sink never breaks the tool loop")
    void failingSink() {
        doThrow(new IllegalStateException("closed")).when(sink).onToolResult(anyString(), anyBoolean(), anyString());
        int start = trace.size();
        trace.add(entry("tool_result", "weather", "result", "ok"));

        assertDoesNotThrow(() -> ToolLoopRunner.emitToolResult(memory, "weather", trace, start,
                new ToolLoopRunner.ToolCallResult("ok", "ok", false)));
        assertEquals(2, trace.size());
    }
}
