/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Measures an approver's edit: sizes, a bounded edit distance, digests and, for
 * text edits, the redacted edited text. Pure functions; no state.
 *
 * @since 6.7.0
 */
public final class EditMetrics {

    /**
     * Largest middle part (after trimming the common prefix and suffix), in code
     * points, for which the distance is computed exactly — 4000 × 4000 is the worst
     * case, 16M cell updates.
     */
    public static final int EXACT_DISTANCE_LIMIT = 4000;
    /** Cap of the {@code editedText} kept in the ledger, in UTF-8 bytes. */
    public static final int EDITED_TEXT_MAX_BYTES = 32 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private EditMetrics() {
    }

    /**
     * Summary of a text edit ({@code ANSWER}, {@code TASK_RESULT},
     * {@code SYNTHESIS}). The edited text is kept, secret-redacted and capped.
     */
    public static EditSummary ofText(EditSummary.Kind kind, String original, String edited) {
        String before = original != null ? original : "";
        String after = edited != null ? edited : "";
        Distance distance = distance(before, after);
        int originalChars = before.codePointCount(0, before.length());
        return new EditSummary(kind, originalChars, after.codePointCount(0, after.length()), distance.value(), distance.approximate(),
                ratio(distance.value(), originalChars), sha256Hex(before), sha256Hex(after), null, cappedRedacted(after));
    }

    /**
     * Summary of amended tool arguments. Never carries the arguments themselves:
     * raw arguments stay out of the ledger, as they always have.
     */
    public static EditSummary ofArguments(String originalJson, String amendedJson) {
        String before = originalJson != null ? originalJson : "";
        String after = amendedJson != null ? amendedJson : "";
        Distance distance = distance(before, after);
        int originalChars = before.codePointCount(0, before.length());
        return new EditSummary(EditSummary.Kind.TOOL_ARGUMENTS, originalChars, after.codePointCount(0, after.length()), distance.value(),
                distance.approximate(), ratio(distance.value(), originalChars), sha256Hex(before), sha256Hex(after),
                fieldsChanged(before, after), null);
    }

    /** A distance and whether it is the approximation. */
    record Distance(int value, boolean approximate) {
    }

    /**
     * Levenshtein distance in code points over the middle parts left after trimming
     * the common prefix and suffix; above {@link #EXACT_DISTANCE_LIMIT} the longer
     * middle's length (an upper bound).
     */
    static Distance distance(String a, String b) {
        int[] x = a.codePoints().toArray();
        int[] y = b.codePoints().toArray();
        int start = 0;
        while (start < x.length && start < y.length && x[start] == y[start]) {
            start++;
        }
        int endX = x.length;
        int endY = y.length;
        while (endX > start && endY > start && x[endX - 1] == y[endY - 1]) {
            endX--;
            endY--;
        }
        int lenX = endX - start;
        int lenY = endY - start;
        if (lenX == 0 || lenY == 0) {
            return new Distance(Math.max(lenX, lenY), false);
        }
        if (lenX > EXACT_DISTANCE_LIMIT || lenY > EXACT_DISTANCE_LIMIT) {
            return new Distance(Math.max(lenX, lenY), true);
        }
        int[] previous = new int[lenY + 1];
        int[] current = new int[lenY + 1];
        for (int j = 0; j <= lenY; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= lenX; i++) {
            current[0] = i;
            int cx = x[start + i - 1];
            for (int j = 1; j <= lenY; j++) {
                int cost = cx == y[start + j - 1] ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return new Distance(previous[lenY], false);
    }

    static double ratio(int charsChanged, int originalChars) {
        return BigDecimal.valueOf((double) charsChanged / Math.max(originalChars, 1)).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Number of leaf JSON paths whose value differs between the two documents
     * (present in one only counts too). Unparseable input counts as one changed
     * field when the texts differ.
     */
    static int fieldsChanged(String originalJson, String amendedJson) {
        Map<String, JsonNode> before = new TreeMap<>();
        Map<String, JsonNode> after = new TreeMap<>();
        try {
            flatten("$", JSON.readTree(originalJson.isBlank() ? "{}" : originalJson), before);
            flatten("$", JSON.readTree(amendedJson.isBlank() ? "{}" : amendedJson), after);
        } catch (Exception unparseable) {
            return Objects.equals(originalJson, amendedJson) ? 0 : 1;
        }
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        int changed = 0;
        for (String path : paths) {
            if (!Objects.equals(before.get(path), after.get(path))) {
                changed++;
            }
        }
        return changed;
    }

    private static void flatten(String path, JsonNode node, Map<String, JsonNode> leaves) {
        if (node == null) {
            return;
        }
        if (node.isObject() && !node.isEmpty()) {
            node.properties().forEach(field -> flatten(path + "." + field.getKey(), field.getValue(), leaves));
        } else if (node.isArray() && !node.isEmpty()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(path + "[" + i + "]", node.get(i), leaves);
            }
        } else {
            leaves.put(path, node);
        }
    }

    /** The text secret-redacted and cut at {@link #EDITED_TEXT_MAX_BYTES}. */
    static String cappedRedacted(String text) {
        String redacted = SecretRedactionFilter.redact(text);
        if (redacted == null) {
            return null;
        }
        byte[] bytes = redacted.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= EDITED_TEXT_MAX_BYTES) {
            return redacted;
        }
        var out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < redacted.length();) {
            int cp = redacted.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > EDITED_TEXT_MAX_BYTES) {
                break;
            }
            out.appendCodePoint(cp);
            used += size;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** SHA-256 of the UTF-8 bytes, lower-case hex. */
    public static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((text != null ? text : "").getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
