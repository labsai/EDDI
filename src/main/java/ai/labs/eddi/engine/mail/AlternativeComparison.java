/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether the two renderings of a {@code multipart/alternative} say different
 * things.
 * <p>
 * The agent reads the {@code text/plain} part; a person reviewing the mail in
 * Outlook or Gmail sees the HTML. A sender can put harmless text in one and
 * instructions in the other, and each audience would vouch for what it saw. A
 * real client generates one from the other, so their words match up to
 * formatting, link targets and the odd image caption. The check is a cheap
 * word-set comparison in both directions: the mail diverges when either side
 * has words the other lacks, at least {@value #MIN_NOVEL_WORDS} of them, and
 * either more than a quarter of its vocabulary or
 * {@value #ABSOLUTE_NOVEL_WORDS} outright. Link targets, addresses and URLs are
 * left out, because one side often spells them out and the other does not.
 */
final class AlternativeComparison {

    static final int MIN_NOVEL_WORDS = 5;
    static final int ABSOLUTE_NOVEL_WORDS = 25;
    private static final double NOVEL_FRACTION = 0.25;

    private AlternativeComparison() {
    }

    /**
     * @param plain
     *            the {@code text/plain} rendering
     * @param htmlText
     *            the HTML rendering, already converted to text with hidden content
     *            removed — what the human sees
     */
    static boolean diverge(String plain, String htmlText) {
        Set<String> plainWords = words(plain);
        Set<String> htmlWords = words(htmlText);
        return novel(plainWords, htmlWords) || novel(htmlWords, plainWords);
    }

    private static boolean novel(Set<String> side, Set<String> other) {
        int novel = 0;
        for (String word : side) {
            if (!other.contains(word)) {
                novel++;
            }
        }
        return novel >= MIN_NOVEL_WORDS && (novel >= ABSOLUTE_NOVEL_WORDS || novel > NOVEL_FRACTION * side.size());
    }

    /**
     * Drops the {@code (target)} after every {@code ]}, in one forward pass: a
     * target ends at the first {@code )} or whitespace, and scanning resumes there
     * whether or not one was found, so no character is read twice.
     */
    static String withoutLinkTargets(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            out.append(c);
            i++;
            if (c == ']' && i < text.length() && text.charAt(i) == '(') {
                int j = i + 1;
                while (j < text.length() && text.charAt(j) != ')' && !Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j < text.length() && text.charAt(j) == ')') {
                    i = j + 1;
                } else {
                    out.append(text, i, j);
                    i = j;
                }
            }
        }
        return out.toString();
    }

    /**
     * Lower-case runs of letters and digits, from chunks that are not links or
     * addresses.
     */
    static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) {
            return words;
        }
        // A Markdown link keeps its text and loses its target: "[Pay](https://x)"
        // becomes "[Pay]".
        String withoutTargets = withoutLinkTargets(text);
        for (String chunk : withoutTargets.split("\\s+")) {
            if (chunk.contains("://") || chunk.contains("@") || chunk.startsWith("mailto:")) {
                continue;
            }
            StringBuilder word = new StringBuilder();
            for (int i = 0; i <= chunk.length(); i++) {
                char c = i < chunk.length() ? chunk.charAt(i) : ' ';
                if (Character.isLetterOrDigit(c)) {
                    word.append(Character.toLowerCase(c));
                } else {
                    if (word.length() >= 2) {
                        words.add(word.toString().toLowerCase(Locale.ROOT));
                    }
                    word.setLength(0);
                }
            }
        }
        return words;
    }
}
