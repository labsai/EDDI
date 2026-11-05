/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reason-code vocabulary a decision's {@code reasonCode} must belong to:
 * the configured {@code review.reasonCodes}, or the built-in set when none is
 * configured.
 * <p>
 * The built-in codes are part of the audit contract; {@code FALSE_CLAIM} in
 * particular always means "the answer states something untrue", so analytics
 * may rely on it.
 *
 * @since 6.7.0
 */
public final class ReasonCodes {

    /** The built-in vocabulary, in display order. */
    public static final Map<String, String> BUILT_IN;

    static {
        var codes = new LinkedHashMap<String, String>();
        codes.put("FALSE_CLAIM", "States something untrue");
        codes.put("FACTUAL_ERROR", "Factual or calculation error");
        codes.put("TONE", "Wrong tone or style");
        codes.put("OFF_POLICY", "Against policy");
        codes.put("FORMAT", "Wrong format");
        codes.put("SCOPE", "Out of scope");
        codes.put("OTHER", "Other");
        BUILT_IN = Collections.unmodifiableMap(codes);
    }

    private ReasonCodes() {
    }

    /** The configured vocabulary when there is one, else the built-in. */
    public static Map<String, String> effective(Map<String, String> configured) {
        return configured != null && !configured.isEmpty() ? configured : BUILT_IN;
    }

    /** The vocabulary as a list of {@code {code, label}} for an API response. */
    public static List<Map<String, String>> asList(Map<String, String> configured) {
        var list = new ArrayList<Map<String, String>>();
        effective(configured).forEach((code, label) -> {
            var entry = new LinkedHashMap<String, String>();
            entry.put("code", code);
            entry.put("label", label);
            list.add(entry);
        });
        return list;
    }

    /**
     * Refuses a code outside the vocabulary, naming the valid ones.
     *
     * @throws IllegalArgumentException
     *             on an unknown code
     */
    public static void require(String code, Map<String, String> configured, String fieldPath) {
        if (code == null) {
            return;
        }
        Map<String, String> vocabulary = effective(configured);
        if (!vocabulary.containsKey(code)) {
            throw new IllegalArgumentException(fieldPath + " '" + abbreviate(code) + "' is not a known reason code; use one of "
                    + vocabulary.keySet());
        }
    }

    private static String abbreviate(String value) {
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }
}
