/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits a user-memory search query into terms that every store matches the
 * same way: each term must appear in the entry's key or value
 * (case-insensitive), and all terms must match.
 * <p>
 * The stores used to match the whole query as one literal substring, so the
 * natural-language query an LLM writes — "dog name" — could never find the key
 * {@code dog_name} it had stored itself. Splitting on anything that is not a
 * letter or digit makes "dog name", "dog_name" and "dog-name" the same search.
 * <p>
 * Terms are letters and digits only, so they need no escaping for a regex or a
 * SQL {@code LIKE} pattern.
 */
public final class MemorySearchTerms {

    /** A query with more terms than this uses the first ones only. */
    static final int MAX_TERMS = 8;

    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private MemorySearchTerms() {
    }

    /**
     * The distinct, lower-cased terms of {@code query}, in order. Empty for a null,
     * blank or punctuation-only query — callers decide what an empty search means.
     */
    public static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return SEPARATORS.splitAsStream(query.toLowerCase(Locale.ROOT))
                .filter(term -> !term.isEmpty())
                .distinct()
                .limit(MAX_TERMS)
                .toList();
    }
}
