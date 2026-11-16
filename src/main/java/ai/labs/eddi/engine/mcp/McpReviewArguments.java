/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.internal.TaskDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the review arguments of the MCP decision tools ({@code editedAnswer},
 * {@code reasonCode}, {@code toolDecisionsJson}, {@code taskDecisionsJson})
 * into the decision bodies REST takes. Only the shape is checked here; the
 * meaning (which call, which task, whether edits are allowed) is checked by the
 * same service validation a REST body goes through, before the pause is
 * consumed.
 *
 * @since 6.7.0
 */
final class McpReviewArguments {

    /** Upper bound of a decisions JSON argument. */
    static final int MAX_DECISIONS_JSON_CHARS = 256 * 1024;

    private static final ObjectMapper STRICT_READER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private McpReviewArguments() {
    }

    /** Thrown on a malformed argument; the message is safe to return. */
    static final class BadArgument extends Exception {
        BadArgument(String message) {
            super(message);
        }
    }

    /** Applies {@code editedAnswer} and {@code reasonCode} to the decision. */
    static void applyReview(HitlDecision decision, String editedAnswer, String reasonCode) throws BadArgument {
        if (editedAnswer != null && !editedAnswer.isEmpty()) {
            if (editedAnswer.length() > HitlReviewConfig.MAX_EDIT_CHARS_CEILING * 2) {
                throw new BadArgument("editedAnswer is too long");
            }
            decision.setEditedAnswer(editedAnswer);
        }
        if (reasonCode != null && !reasonCode.isBlank()) {
            decision.setReasonCode(reasonCode.trim());
        }
    }

    /**
     * {@code {"<callId>": {"verdict": "APPROVED", "note": "…", "amendedArguments":
     * "{…}", "reasonCode": "TONE"}}}.
     */
    static Map<String, ToolCallDecision> toolDecisions(IJsonSerialization json, String raw) throws BadArgument {
        Map<?, ?> parsed = parseObject(raw, "toolDecisionsJson");
        if (parsed == null) {
            return null;
        }
        var decisions = new LinkedHashMap<String, ToolCallDecision>();
        for (var entry : parsed.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> fields)) {
                throw new BadArgument("toolDecisionsJson: each value must be an object with a verdict");
            }
            var decision = new ToolCallDecision();
            decision.setVerdict(verdict(fields.get("verdict"), "toolDecisionsJson['" + entry.getKey() + "'].verdict"));
            decision.setNote(string(fields.get("note"), "note"));
            Object amended = fields.get("amendedArguments");
            if (amended instanceof Map<?, ?> || amended instanceof Iterable<?>) {
                try {
                    decision.setAmendedArguments(json.serialize(amended));
                } catch (Exception e) {
                    throw new BadArgument("toolDecisionsJson: amendedArguments cannot be serialized");
                }
            } else {
                decision.setAmendedArguments(string(amended, "amendedArguments"));
            }
            decision.setReasonCode(string(fields.get("reasonCode"), "reasonCode"));
            decisions.put(String.valueOf(entry.getKey()), decision);
        }
        return decisions;
    }

    /**
     * {@code {"<taskId>": {"verdict": "APPROVED", "note": "…", "editedResult": "…",
     * "reasonCode": "SCOPE"}}}.
     */
    static Map<String, TaskDecision> taskDecisions(String raw) throws BadArgument {
        Map<?, ?> parsed = parseObject(raw, "taskDecisionsJson");
        if (parsed == null) {
            return null;
        }
        var decisions = new LinkedHashMap<String, TaskDecision>();
        for (var entry : parsed.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> fields)) {
                throw new BadArgument("taskDecisionsJson: each value must be an object with a verdict");
            }
            var decision = new TaskDecision();
            decision.setVerdict(verdict(fields.get("verdict"), "taskDecisionsJson['" + entry.getKey() + "'].verdict"));
            decision.setNote(string(fields.get("note"), "note"));
            decision.setEditedResult(string(fields.get("editedResult"), "editedResult"));
            decision.setReasonCode(string(fields.get("reasonCode"), "reasonCode"));
            decisions.put(String.valueOf(entry.getKey()), decision);
        }
        return decisions;
    }

    private static Map<?, ?> parseObject(String raw, String name) throws BadArgument {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > MAX_DECISIONS_JSON_CHARS) {
            throw new BadArgument(name + " exceeds " + MAX_DECISIONS_JSON_CHARS + " characters");
        }
        try {
            // A second JSON value after the first would be dropped silently, taking its
            // decisions with it.
            return STRICT_READER.readValue(raw, Map.class);
        } catch (Exception e) {
            throw new BadArgument("Malformed " + name + " (expected a JSON object keyed by id)");
        }
    }

    private static HitlVerdict verdict(Object value, String field) throws BadArgument {
        HitlVerdict verdict = value instanceof String text ? HitlVerdict.fromString(text) : null;
        if (verdict == null) {
            throw new BadArgument(field + " must be APPROVED or REJECTED");
        }
        return verdict;
    }

    private static String string(Object value, String field) throws BadArgument {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new BadArgument(field + " must be a string");
        }
        return text;
    }
}
