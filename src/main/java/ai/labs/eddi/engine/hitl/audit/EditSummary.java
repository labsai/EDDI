/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much an approver changed one unit before approving it — the {@code edit}
 * object of a {@code hitl.approval} unit (schema 2). Built by
 * {@link EditMetrics}; the original text itself is never part of it.
 *
 * @param kind
 *            what was edited
 * @param originalChars
 *            length of the original, in code points
 * @param editedChars
 *            length of the edit, in code points
 * @param charsChanged
 *            Levenshtein distance between the two after trimming their common
 *            prefix and suffix — exact when both middles are at most
 *            {@value EditMetrics#EXACT_DISTANCE_LIMIT} code points, otherwise
 *            the longer middle's length
 * @param approximate
 *            whether {@code charsChanged} is the approximation
 * @param changeRatio
 *            {@code charsChanged / max(originalChars, 1)}, 4 decimal places
 * @param originalSha256
 *            SHA-256 (hex) of the original
 * @param editedSha256
 *            SHA-256 (hex) of the edit
 * @param fieldsChanged
 *            {@code TOOL_ARGUMENTS} only: how many leaf JSON paths differ; null
 *            otherwise
 * @param editedText
 *            {@code ANSWER}, {@code TASK_RESULT} and {@code SYNTHESIS} only:
 *            the edit, secret-redacted and capped at 32 KB; never set for
 *            {@code TOOL_ARGUMENTS}
 * @since 6.7.0
 */
public record EditSummary(Kind kind, int originalChars, int editedChars, int charsChanged, boolean approximate, double changeRatio,
        String originalSha256, String editedSha256, Integer fieldsChanged, String editedText) {

    /** What an edit replaced. Values are only ever added. */
    public enum Kind {
        ANSWER, TOOL_ARGUMENTS, TASK_RESULT, SYNTHESIS
    }

    /** Whether the edit changed anything at all. */
    public boolean changed() {
        return originalSha256 == null || !originalSha256.equals(editedSha256);
    }

    /** The stored (audit {@code output}) form. */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("kind", kind.name());
        map.put("originalChars", originalChars);
        map.put("editedChars", editedChars);
        map.put("charsChanged", charsChanged);
        map.put("approximate", approximate);
        map.put("changeRatio", changeRatio);
        map.put("originalSha256", originalSha256);
        map.put("editedSha256", editedSha256);
        if (fieldsChanged != null) {
            map.put("fieldsChanged", fieldsChanged);
        }
        if (editedText != null) {
            map.put("editedText", editedText);
        }
        return map;
    }

    /** Reads the stored form back; null for anything that is not a map. */
    public static EditSummary fromMap(Object stored) {
        if (!(stored instanceof Map<?, ?> map)) {
            return null;
        }
        Kind kind;
        try {
            kind = Kind.valueOf(String.valueOf(map.get("kind")));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        return new EditSummary(kind, asInt(map.get("originalChars")), asInt(map.get("editedChars")), asInt(map.get("charsChanged")),
                Boolean.TRUE.equals(map.get("approximate")),
                map.get("changeRatio") instanceof Number ratio ? ratio.doubleValue() : 0.0,
                map.get("originalSha256") instanceof String original ? original : null,
                map.get("editedSha256") instanceof String edited ? edited : null,
                map.get("fieldsChanged") instanceof Number fields ? fields.intValue() : null,
                map.get("editedText") instanceof String text ? text : null);
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
