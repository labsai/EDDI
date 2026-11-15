/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.context;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code hitl} and {@code call} roots of the approval-context
 * template data from the paused conversation.
 * <p>
 * <b>Raw tool arguments are never read.</b> A call exposes
 * {@code argumentsRedacted} — the redacted, size-capped text stored when the
 * batch was built — re-redacted through the <em>current</em>
 * {@link SecretRedactionFilter}, as {@code approval-status} does at serve time.
 * {@code args} is that same text parsed as JSON (empty when it is not JSON).
 * Every string handed to a template is capped at {@value #MAX_STRING_CHARS}
 * characters, so the render input is bounded whatever a model wrote.
 */
public final class ApprovalContextTemplateData {

    /** Cap for any single string exposed to a template by this class. */
    public static final int MAX_STRING_CHARS = 4_000;

    /** Calls exposed in {@code hitl.toolCalls}, matching the Slack preview. */
    public static final int MAX_CALLS = 5;

    private static final int MAX_DEPTH = 8;
    private static final int MAX_COLLECTION_SIZE = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApprovalContextTemplateData() {
    }

    /**
     * The {@code hitl} root: {@code pauseType}, {@code reason}, {@code toolCalls[]}
     * and {@code executedUngatedCalls[]}.
     */
    public static Map<String, Object> hitl(IConversationMemory memory) {
        Map<String, Object> hitl = new LinkedHashMap<>();
        String pauseType = memory.getHitlPauseType();
        hitl.put("pauseType", pauseType == null ? "RULE" : pauseType);
        hitl.put("reason", capString(memory.getHitlPauseReason()));
        PendingToolCallBatch batch = memory.getHitlPendingToolCalls();
        List<Map<String, Object>> calls = new ArrayList<>();
        List<String> ungated = new ArrayList<>();
        if (batch != null) {
            if (batch.getCalls() != null) {
                for (PendingToolCall call : batch.getCalls()) {
                    if (calls.size() >= MAX_CALLS) {
                        break;
                    }
                    calls.add(call(call));
                }
            }
            if (batch.getExecutedUngatedCallNames() != null) {
                for (String name : batch.getExecutedUngatedCallNames()) {
                    if (ungated.size() >= MAX_COLLECTION_SIZE) {
                        break;
                    }
                    ungated.add(capString(name));
                }
            }
        }
        hitl.put("toolCalls", calls);
        hitl.put("executedUngatedCalls", ungated);
        return hitl;
    }

    /**
     * One gated call as a template sees it ({@code call.*} in a rule block, one
     * entry of {@code hitl.toolCalls}): {@code name}, {@code source},
     * {@code gateReason}, {@code matchedRule}, {@code arguments} (redacted text)
     * and {@code args} (that text parsed as JSON, or empty).
     */
    public static Map<String, Object> call(PendingToolCall call) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", capString(call.getToolName()));
        map.put("source", capString(call.getSource()));
        map.put("gateReason", capString(call.getGateReason()));
        map.put("matchedRule", capString(call.getMatchedRule()));
        // The ONLY argument field read: redacted at build time, re-redacted now.
        String redacted = SecretRedactionFilter.redact(call.getArgumentsRedacted());
        map.put("arguments", capString(redacted));
        map.put("args", parseArgs(redacted));
        return map;
    }

    private static Map<String, Object> parseArgs(String redactedJson) {
        if (redactedJson == null || redactedJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(redactedJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> capped = (Map<String, Object>) cap(parsed, 0);
            return capped;
        } catch (Exception e) {
            // Not JSON (or a truncated cut of it): the text form in "arguments" remains.
            return Map.of();
        }
    }

    /**
     * A copy of {@code value} with every string capped at
     * {@value #MAX_STRING_CHARS} characters and every collection at a fixed size
     * and nesting depth. Maps and lists are copied; scalars pass through.
     */
    public static Object cap(Object value) {
        return cap(value, 0);
    }

    private static Object cap(Object value, int depth) {
        if (value instanceof String s) {
            return capString(s);
        }
        if (depth >= MAX_DEPTH) {
            return value instanceof Map || value instanceof Iterable ? "" : value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (copy.size() >= MAX_COLLECTION_SIZE) {
                    break;
                }
                copy.put(String.valueOf(entry.getKey()), cap(entry.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            for (Object element : iterable) {
                if (copy.size() >= MAX_COLLECTION_SIZE) {
                    break;
                }
                copy.add(cap(element, depth + 1));
            }
            return copy;
        }
        return value;
    }

    /**
     * {@code s} cut to {@value #MAX_STRING_CHARS} characters; {@code null} stays
     * null.
     */
    public static String capString(String s) {
        if (s == null || s.length() <= MAX_STRING_CHARS) {
            return s;
        }
        int end = MAX_STRING_CHARS;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
