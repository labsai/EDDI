/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

/**
 * Finds a {@code "key": "value"} string member in text that is JSON or merely
 * contains some — an LLM reply, a provider's error body — with a linear scan.
 * <p>
 * It replaces the regex {@code "key"\s*:\s*"((?:[^"\\]|\\.)*)"}, whose
 * alternation inside a repeated group recurses once per character in
 * {@code java.util.regex}: a value of about 2,000 characters overflowed a 1 MB
 * thread stack (security review 2026-10-17, L14). A provider echoing a long
 * user string into {@code "message"} threw a {@code StackOverflowError} out of
 * the turn's failure handler; a long {@code "response"} failed the turn.
 * <p>
 * Semantics follow the regex: the first occurrence of the quoted key that is
 * followed by optional whitespace, a colon, optional whitespace and a string is
 * taken; a backslash escapes the character after it. One difference: once a
 * value's string runs to the end of the text without a closing quote, the scan
 * stops rather than retrying every later occurrence, which bounds the work to
 * one pass for malformed input.
 */
public final class JsonStringField {

    private JsonStringField() {
    }

    /**
     * The raw content of the first {@code "key": "..."} string — escapes left in
     * place, as the regex's group 1 had them — or {@code null}.
     */
    public static String firstRawValue(String text, String key) {
        if (text == null || key == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return null;
            }
            int i = skipWhitespace(text, at + needle.length());
            if (i < text.length() && text.charAt(i) == ':') {
                i = skipWhitespace(text, i + 1);
                if (i < text.length() && text.charAt(i) == '"') {
                    int end = closingQuote(text, i + 1);
                    return end < 0 ? null : text.substring(i + 1, end);
                }
            }
            from = at + 1;
        }
    }

    /** Index of the first unescaped {@code "} at or after {@code from}, or -1. */
    private static int closingQuote(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /** Skips what regex {@code \s} matches. */
    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != 0x0B && c != '\f' && c != '\r') {
                break;
            }
            i++;
        }
        return i;
    }
}
