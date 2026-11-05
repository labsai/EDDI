/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolTraceRedaction — the redaction of toolTrace and of the tool_result summary")
class ToolTraceRedactionTest {

    private static final String KEY = "sk-" + "a".repeat(40);

    @Test
    @DisplayName("the trace is deep-redacted into a fresh structure; the original keeps its values")
    void redactsTraceDeeply() {
        Map<String, Object> entry = Map.of("type", "tool_result", "tool", "fetch", "result", "key " + KEY,
                "nested", List.of(Map.of("auth", "Bearer " + "b".repeat(30))));
        List<Object> trace = List.of(entry);

        var redacted = ToolTraceRedaction.redactTrace(trace);

        String text = redacted.toString();
        assertFalse(text.contains("a".repeat(20)), text);
        assertFalse(text.contains("b".repeat(20)), text);
        assertTrue(text.contains("sk-<REDACTED>"), text);
        assertEquals("key " + KEY, entry.get("result"), "the stored trace is left intact");
    }

    @Test
    @DisplayName("a summary is redacted, whitespace collapsed and cut to 200 characters")
    void summaryIsRedactedAndBounded() {
        String summary = ToolTraceRedaction.summary("line one\n\tline two " + KEY + " " + "word ".repeat(100));

        assertTrue(summary.startsWith("line one line two sk-<REDACTED> word"), summary);
        assertTrue(summary.length() <= ToolTraceRedaction.MAX_SUMMARY_LENGTH, "length " + summary.length());
        assertTrue(summary.endsWith("…"), summary);
    }

    @Test
    @DisplayName("redaction runs on the whole text before the cut, so a credential across the cut does not leak a prefix")
    void redactsBeforeTruncating() {
        // Cut first, the key would end 11 characters after "sk-" — too short for the
        // redactor's 20-character minimum — and those characters would go out clear.
        String summary = ToolTraceRedaction.summary("x".repeat(184) + "." + KEY);

        assertTrue(summary.endsWith(".sk-<REDACTED>"), summary);
        assertFalse(summary.contains("aaaa"), summary);
    }

    @Test
    @DisplayName("no text is an empty summary, never null")
    void emptySummary() {
        assertEquals("", ToolTraceRedaction.summary(null));
        assertEquals("", ToolTraceRedaction.summary(""));
    }

    @Test
    @DisplayName("control and bidi characters are dropped from the summary")
    void dropsControlCharacters() {
        assertEquals("abc def", ToolTraceRedaction.summary("a\u0000b‮c\r\ndef"));
    }
}
