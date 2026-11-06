/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the occurrences of an author's term list ("Musterkanzlei GmbH") in a
 * text: case-insensitive and whole-word — a term that begins or ends with a
 * letter or digit must not continue a word there, so "Mai" is not found in
 * "Maine".
 */
public final class TermMatcher {

    private TermMatcher() {
    }

    /**
     * Every occurrence of every non-blank term, sorted by start; occurrences of
     * different terms may overlap.
     */
    public static List<TextRange> occurrences(String text, Collection<String> terms) {
        List<TextRange> found = new ArrayList<>();
        if (terms == null || terms.isEmpty() || text.isEmpty()) {
            return found;
        }
        for (String raw : terms) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String term = raw.strip();
            int length = term.length();
            for (int i = 0; i + length <= text.length(); i++) {
                if (text.regionMatches(true, i, term, 0, length) && boundaryBefore(text, i, term) && boundaryAfter(text, i + length, term)) {
                    found.add(new TextRange(i, i + length));
                }
            }
        }
        found.sort(Comparator.comparingInt(TextRange::start).thenComparingInt(TextRange::end));
        return found;
    }

    /**
     * Whether a match of {@code term} may start at {@code index}: at the start of
     * the text, or where the term or the preceding character is not a letter or
     * digit.
     */
    private static boolean boundaryBefore(String text, int index, String term) {
        return index == 0 || !Character.isLetterOrDigit(term.charAt(0)) || !Character.isLetterOrDigit(text.charAt(index - 1));
    }

    /**
     * Whether a match of {@code term} may end at {@code index}: at the end of the
     * text, or where the term or the following character is not a letter or digit.
     */
    private static boolean boundaryAfter(String text, int index, String term) {
        return index == text.length() || !Character.isLetterOrDigit(term.charAt(term.length() - 1))
                || !Character.isLetterOrDigit(text.charAt(index));
    }
}
