/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.memory.ConversationTitles;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one redaction every tool-trace text gets before it leaves the process on
 * a streamed turn: the {@code toolTrace} of the SSE {@code task_complete} frame
 * and the {@code summary} of each {@code tool_result} frame. One place, so the
 * two channels cannot redact differently — the live event must never show what
 * the turn-end trace hides.
 * <p>
 * Each string goes through {@link SecretRedactionFilter}, the scan
 * {@code AuditLedgerService} applies to the same content. The trace stored in
 * conversation memory is left intact for the owner-scoped tool history.
 */
public final class ToolTraceRedaction {

    /**
     * Longest {@code summary} a {@code tool_result} event carries, in characters
     * (ellipsis included). A status line, not the result: the whole (redacted)
     * result still arrives in {@code task_complete}'s {@code toolTrace}.
     */
    public static final int MAX_SUMMARY_LENGTH = 200;

    private ToolTraceRedaction() {
    }

    /** A fresh, deep-redacted copy of a collected tool trace. */
    public static List<Object> redactTrace(List<?> trace) {
        List<Object> redacted = new ArrayList<>(trace.size());
        for (Object entry : trace) {
            redacted.add(redactValue(entry));
        }
        return redacted;
    }

    /**
     * Redacts every string in {@code value} — a string, or maps and lists of them —
     * and returns a fresh structure; anything else is returned as it is.
     */
    @SuppressWarnings("unchecked")
    public static Object redactValue(Object value) {
        if (value instanceof String s) {
            return SecretRedactionFilter.redact(s);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> scrubbed = new LinkedHashMap<>(map.size());
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                scrubbed.put(e.getKey(), redactValue(e.getValue()));
            }
            return scrubbed;
        } else if (value instanceof List<?> list) {
            return list.stream().map(ToolTraceRedaction::redactValue).toList();
        }
        return value;
    }

    /**
     * The {@code summary} of a {@code tool_result} event: the text redacted first —
     * on the whole text, because a cut can leave a credential too short for the
     * redactor to recognise — then whitespace collapsed, control and bidi
     * characters dropped, and cut to {@link #MAX_SUMMARY_LENGTH}. Empty for no
     * text, never null.
     */
    public static String summary(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = (String) redactValue(text);
        String normalized = ConversationTitles.normalize(redacted);
        return ConversationTitles.truncate(normalized, MAX_SUMMARY_LENGTH);
    }
}
