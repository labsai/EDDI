/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Function;

/**
 * Finds the one JSON value embedded in model prose or a markdown fence - the
 * extractor every group engine that reads a structured reply uses (facilitator
 * move, retro lessons, ballot, bargain move, bid, convergence and debate
 * verdicts, VERIFY results).
 * <p>
 * The engines used to carry their own copies, and they had diverged: some took
 * the first opening brace to the <em>last</em> closing one - so a ballot
 * followed by a closing remark containing a brace, or preceded by one, became
 * unparseable and the vote was dropped - while others scanned for the first
 * balanced object. This class does the balanced scan, honouring JSON strings
 * and escapes, and tries each opening bracket in turn until the caller's parser
 * accepts a span: a bracketed aside in the prose
 * ({@code "Checked [details]: [...]"}) no longer hides the real value behind
 * it.
 * <p>
 * <b>Exactly one.</b> A reply carrying a second acceptable value after the
 * first is ambiguous and yields {@code null}: which of two verdicts or two
 * ballots the model meant is not something to decide by position (spans nested
 * inside the accepted one are part of it, not rivals). The old greedy slice had
 * this property by accident - two objects never parsed as one - and the parsers
 * rely on it.
 * <p>
 * The number of candidate spans is bounded, so adversarial input with thousands
 * of opening brackets costs a bounded number of linear scans, not a quadratic
 * one.
 */
public final class EmbeddedJson {

    /** Opening brackets tried before giving up. */
    static final int MAX_CANDIDATES = 32;

    private EmbeddedJson() {
    }

    /**
     * The only balanced {@code {...}} span the parser accepts, parsed.
     *
     * @param parser
     *            returns the parsed value, or {@code null} to reject the span (it
     *            must not throw)
     * @return the accepted value, or {@code null} when none - or more than one - is
     *         accepted
     */
    public static <T> T soleObject(String text, Function<String, T> parser) {
        return soleValue(text, '{', '}', parser);
    }

    /**
     * As {@link #soleObject}, accepting only an object that carries at least one of
     * {@code fields} - the keys the caller actually reads. An unrelated object in
     * the prose (an example, a quoted config, an empty {@code {}}) is then neither
     * mistaken for the reply nor counted as a rival that makes the real one
     * ambiguous.
     *
     * @param reader
     *            parses a span, returning {@code null} on failure (must not throw)
     */
    public static JsonNode soleObjectWithAnyField(String text, Function<String, JsonNode> reader, String... fields) {
        return soleObject(text, span -> {
            JsonNode node = reader.apply(span);
            if (node == null || !node.isObject()) {
                return null;
            }
            for (String field : fields) {
                if (node.has(field)) {
                    return node;
                }
            }
            return null;
        });
    }

    /** As {@link #soleObject}, for a {@code [...]} array. */
    public static <T> T soleArray(String text, Function<String, T> parser) {
        return soleValue(text, '[', ']', parser);
    }

    /**
     * As {@link #soleArray}, also reporting where the accepted span ends - for a
     * caller that keeps the prose after it.
     *
     * @return the match, or {@code null}
     */
    public static <T> Match<T> soleArrayMatch(String text, Function<String, T> parser) {
        return soleMatch(text, '[', ']', parser);
    }

    /**
     * An accepted span: its parsed value and the index just past its closing
     * bracket.
     */
    public record Match<T>(T value, int end) {
    }

    private static <T> T soleValue(String text, char open, char close, Function<String, T> parser) {
        Match<T> match = soleMatch(text, open, close, parser);
        return match != null ? match.value() : null;
    }

    private static <T> Match<T> soleMatch(String text, char open, char close, Function<String, T> parser) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int[] budget = {MAX_CANDIDATES, 0};
        Match<T> first = nextMatch(text, 0, open, close, parser, budget);
        if (first == null) {
            return null;
        }
        // A rival after the accepted span makes the reply ambiguous. The budget is
        // shared, so the whole call stays bounded however the candidates fall.
        if (nextMatch(text, first.end(), open, close, parser, budget) != null) {
            return null;
        }
        // Running out of budget with candidates still unexamined is not proof that
        // the first match is the only one: a contradictory rival may sit in the
        // unread suffix, so treat it as ambiguous rather than accept the first.
        return budget[1] != 0 ? null : first;
    }

    private static <T> Match<T> nextMatch(String text, int from, char open, char close, Function<String, T> parser,
                                          int[] budget) {
        int start = text.indexOf(open, from);
        while (start >= 0 && budget[0] > 0) {
            budget[0]--;
            String span = balancedSpan(text, start, open, close);
            T parsed = span != null ? parser.apply(span) : null;
            if (parsed != null) {
                return new Match<>(parsed, start + span.length());
            }
            start = text.indexOf(open, start + 1);
        }
        if (start >= 0) {
            budget[1] = 1; // stopped by the budget, not by the end of the text
        }
        return null;
    }

    /**
     * The balanced span opening at {@code start}, or {@code null} when it never
     * closes. Brackets inside JSON strings do not count.
     */
    static String balancedSpan(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = inString;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }
}
