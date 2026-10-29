/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regex matching that cannot hang the runner.
 * <p>
 * Suite authors write the patterns and agents write the text they run against,
 * and {@link java.util.regex} backtracks without limit: {@code (a+)+$} against
 * thirty {@code a}s and a {@code b} takes longer than the age of the universe.
 * Two bounds make that impossible here:
 * <ul>
 * <li>the pattern is at most {@code AgentTestSuiteValidator.MAX_PATTERN_LENGTH}
 * characters, checked when the suite is saved, and the text at most
 * {@link #MAX_TEXT_LENGTH};</li>
 * <li>the match runs against a {@link CharSequence} that throws once a deadline
 * has passed. The regex engine reads its input through {@code charAt}, so even
 * a catastrophically backtracking match stops within milliseconds of the
 * deadline instead of occupying a thread forever.</li>
 * <li>the deadline cannot see work that reads no input, and a counted
 * repetition of something that matches empty is exactly that:
 * {@code (?:(?:){99999}){99999}} spins ten billion iterations against a
 * three-character text without one {@code charAt}, about 25 seconds of CPU.
 * {@link #excessiveRepetition} therefore caps the iterations such repetitions
 * can force, at save time and again before every match (for suites saved before
 * the check existed).</li>
 * </ul>
 * A timed-out or refused match is reported as a failed expectation, never as a
 * pass.
 * <p>
 * What remains: a pattern within the cap that reads nothing and never matches
 * still costs up to {@link #MAX_FORCED_REPETITIONS} iterations per start
 * position, unchecked by the deadline — at most around a hundred million steps
 * over a {@link #MAX_TEXT_LENGTH} text, which is bounded but can exceed the
 * nominal budget.
 *
 * @since 6.5.0
 */
public final class BoundedRegex {

    /** Text longer than this is cut before matching. */
    public static final int MAX_TEXT_LENGTH = 100_000;

    /** Wall-clock budget of one match. */
    public static final long DEFAULT_TIMEOUT_MS = 250;

    /**
     * Ceiling on the iterations that counted repetitions of groups, anchors and
     * back-references can force, summed over the pattern and multiplied through
     * nesting. See {@link #excessiveRepetition}.
     */
    public static final int MAX_FORCED_REPETITIONS = 1000;

    private BoundedRegex() {
    }

    /** The outcome of one bounded {@code find}. */
    public enum Result {
        MATCH, NO_MATCH, TIMED_OUT
    }

    public static Result find(String regex, String text) {
        return find(Pattern.compile(regex), text, DEFAULT_TIMEOUT_MS);
    }

    public static Result find(Pattern pattern, String text, long timeoutMs) {
        if ((pattern.flags() & Pattern.COMMENTS) != 0 || excessiveRepetition(pattern.pattern()).isPresent()) {
            // The deadline cannot stop this pattern, so it is not run at all.
            return Result.TIMED_OUT;
        }
        String input = text == null ? "" : text;
        if (input.length() > MAX_TEXT_LENGTH) {
            input = input.substring(0, MAX_TEXT_LENGTH);
        }
        try {
            return pattern.matcher(new DeadlineCharSequence(input, System.nanoTime() + timeoutMs * 1_000_000L)).find()
                    ? Result.MATCH
                    : Result.NO_MATCH;
        } catch (MatchTimedOut e) {
            return Result.TIMED_OUT;
        }
    }

    /**
     * Why a pattern can force more empty work than the deadline can police, or
     * empty if it cannot.
     * <p>
     * {@code java.util.regex} stops a {@code *}, {@code +} or the optional part of
     * {@code {n,m}} as soon as an iteration matches empty, but it runs the
     * <em>minimum</em> {@code n} of a counted repetition unconditionally. A group,
     * an anchor or a back-reference can match without reading, so
     * {@code (?:(?=)){999999999}} loops a billion times on any text and two nested
     * counts multiply. This scan weighs every counted minimum applied to one of
     * those — a group's weight is what its contents force, at least one — sums the
     * weights over the pattern, and refuses a total above
     * {@link #MAX_FORCED_REPETITIONS}. A counted repetition of a single
     * character-reading atom ({@code \d{4}}, {@code [a-z]{2,8}}) costs nothing
     * here: every iteration reads input, so the deadline sees it.
     * <p>
     * The comments flag {@code (?x)} is refused too: it turns {@code #} into a
     * comment that hides the rest of the line, and a scanner that reads comments
     * differently from the engine could be shown a different pattern than the one
     * that runs.
     */
    public static Optional<String> excessiveRepetition(String regex) {
        return new RepetitionScan(regex).run();
    }

    /** One left-to-right pass over a regex that Pattern has already accepted. */
    private static final class RepetitionScan {
        private static final int NO_ATOM = 0;
        private static final int READING_ATOM = 1;
        private static final int WEIGHTED_ATOM = 2;

        private final String regex;
        /** The weight summed so far in each open group, innermost on top. */
        private final Deque<long[]> frames = new ArrayDeque<>();
        private int index;
        private int lastAtom = NO_ATOM;
        /** The last atom's weight, already added to the innermost frame. */
        private long lastWeight;

        RepetitionScan(String regex) {
            this.regex = regex;
            frames.push(new long[]{0});
        }

        Optional<String> run() {
            int length = regex.length();
            while (index < length) {
                char c = regex.charAt(index);
                switch (c) {
                    case '\\' -> escape();
                    case '[' -> {
                        index = endOfClass(index);
                        atom(READING_ATOM, 0);
                    }
                    case '(' -> {
                        String refused = openGroup();
                        if (refused != null) {
                            return Optional.of(refused);
                        }
                    }
                    case ')' -> {
                        index++;
                        long inner = frames.size() > 1 ? frames.pop()[0] : 0;
                        atom(WEIGHTED_ATOM, inner);
                    }
                    case '^', '$' -> {
                        index++;
                        atom(WEIGHTED_ATOM, 0);
                    }
                    case '{' -> {
                        if (!countedQuantifier()) {
                            index++;
                            atom(READING_ATOM, 0);
                        }
                    }
                    case '*', '+', '?' -> {
                        index++;
                        skipQuantifierMode();
                        lastAtom = NO_ATOM;
                    }
                    case '|' -> {
                        index++;
                        lastAtom = NO_ATOM;
                    }
                    default -> {
                        index++;
                        atom(READING_ATOM, 0);
                    }
                }
                if (frames.getLast()[0] > MAX_FORCED_REPETITIONS || frames.peek()[0] > MAX_FORCED_REPETITIONS) {
                    return Optional.of(tooMuch());
                }
            }
            long total = 0;
            for (long[] frame : frames) {
                total += frame[0];
            }
            return total > MAX_FORCED_REPETITIONS ? Optional.of(tooMuch()) : Optional.empty();
        }

        private static String tooMuch() {
            return "repeats a group, anchor or back-reference so often that the match could run past its time limit without reading"
                    + " any input — counted repetitions of those may force at most " + MAX_FORCED_REPETITIONS
                    + " iterations in total (nested counts multiply)";
        }

        private void atom(int kind, long weight) {
            lastAtom = kind;
            lastWeight = weight;
            frames.peek()[0] += weight;
        }

        private void escape() {
            int length = regex.length();
            if (index + 1 >= length) {
                index = length;
                atom(READING_ATOM, 0);
                return;
            }
            char e = regex.charAt(index + 1);
            switch (e) {
                case 'Q' -> {
                    int end = regex.indexOf("\\E", index + 2);
                    index = end < 0 ? length : end + 2;
                    atom(READING_ATOM, 0);
                }
                case 'b', 'B', 'A', 'z', 'Z', 'G' -> {
                    index += 2;
                    atom(WEIGHTED_ATOM, 0);
                }
                case 'k' -> {
                    int end = regex.indexOf('>', index + 2);
                    index = end < 0 ? length : end + 1;
                    atom(WEIGHTED_ATOM, 0);
                }
                case 'p', 'P', 'x', 'N' -> {
                    if (index + 2 < length && regex.charAt(index + 2) == '{') {
                        int end = regex.indexOf('}', index + 3);
                        index = end < 0 ? length : end + 1;
                    } else {
                        index += e == 'x' ? 4 : 3;
                    }
                    atom(READING_ATOM, 0);
                }
                case 'c' -> {
                    index += 3;
                    atom(READING_ATOM, 0);
                }
                default -> {
                    index += 2;
                    if (e >= '1' && e <= '9') {
                        // a back-reference to a group that matched empty matches empty
                        while (index < length && isAsciiDigit(regex.charAt(index))) {
                            index++;
                        }
                        atom(WEIGHTED_ATOM, 0);
                    } else {
                        atom(READING_ATOM, 0);
                    }
                }
            }
            index = Math.min(index, length);
        }

        /**
         * Opens a group, or applies an inline flag set such as {@code (?i)}. Returns a
         * refusal message for the comments flag.
         */
        private String openGroup() {
            int length = regex.length();
            index++;
            if (index < length && regex.charAt(index) == '?') {
                index++;
                char kind = index < length ? regex.charAt(index) : ')';
                if (kind == '<' && index + 1 < length && regex.charAt(index + 1) != '=' && regex.charAt(index + 1) != '!') {
                    int end = regex.indexOf('>', index);
                    index = end < 0 ? length : end + 1;
                } else if (kind == '<') {
                    index += 2;
                } else if (kind == ':' || kind == '=' || kind == '!' || kind == '>') {
                    index++;
                } else {
                    int flagsStart = index;
                    while (index < length && regex.charAt(index) != ':' && regex.charAt(index) != ')') {
                        index++;
                    }
                    String flags = regex.substring(flagsStart, index);
                    int minus = flags.indexOf('-');
                    if ((minus < 0 ? flags : flags.substring(0, minus)).indexOf('x') >= 0) {
                        return "uses the comments flag (?x), which agent test regexes do not support";
                    }
                    if (index < length && regex.charAt(index) == ')') {
                        // (?i) — a flag change, not a group
                        index++;
                        lastAtom = NO_ATOM;
                        return null;
                    }
                    index++;
                }
            }
            frames.push(new long[]{0});
            lastAtom = NO_ATOM;
            return null;
        }

        /**
         * The digits {@link Pattern} reads in a quantifier or a back-reference: ASCII
         * only. {@link Character#isDigit} also accepts other scripts' digits, which
         * Pattern does not treat as a count.
         */
        private static boolean isAsciiDigit(char c) {
            return c >= '0' && c <= '9';
        }

        /**
         * Applies {@code {n}}, {@code {n,}} or {@code {n,m}} at the index, if one is
         * there.
         */
        private boolean countedQuantifier() {
            int length = regex.length();
            int cursor = index + 1;
            int digitsStart = cursor;
            while (cursor < length && isAsciiDigit(regex.charAt(cursor))) {
                cursor++;
            }
            if (cursor == digitsStart || cursor >= length) {
                return false;
            }
            String digits = regex.substring(digitsStart, cursor);
            int close = regex.indexOf('}', cursor);
            if (close < 0) {
                return false;
            }
            index = close + 1;
            skipQuantifierMode();
            if (lastAtom == WEIGHTED_ATOM) {
                long minimum = digits.length() > 9 ? Long.MAX_VALUE / 4 : Long.parseLong(digits);
                long weight = Math.multiplyExact(Math.min(minimum, MAX_FORCED_REPETITIONS + 1L),
                        Math.max(1, Math.min(lastWeight, MAX_FORCED_REPETITIONS + 1L)));
                frames.peek()[0] += weight - lastWeight;
                lastWeight = weight;
            }
            return true;
        }

        private void skipQuantifierMode() {
            if (index < regex.length() && (regex.charAt(index) == '?' || regex.charAt(index) == '+')) {
                index++;
            }
        }

        /** The index just past the character class opening at {@code start}. */
        private int endOfClass(int start) {
            int length = regex.length();
            int cursor = start + 1;
            if (cursor < length && regex.charAt(cursor) == '^') {
                cursor++;
            }
            if (cursor < length && regex.charAt(cursor) == ']') {
                cursor++;
            }
            int depth = 1;
            while (cursor < length) {
                char c = regex.charAt(cursor);
                if (c == '\\') {
                    if (cursor + 1 < length && regex.charAt(cursor + 1) == 'Q') {
                        int end = regex.indexOf("\\E", cursor + 2);
                        cursor = end < 0 ? length : end + 2;
                    } else {
                        cursor += 2;
                    }
                    continue;
                }
                if (c == '[') {
                    depth++;
                } else if (c == ']' && --depth == 0) {
                    return cursor + 1;
                }
                cursor++;
            }
            return length;
        }
    }

    private static final class MatchTimedOut extends RuntimeException {
        MatchTimedOut() {
            super("regex match exceeded its time budget", null, false, false);
        }
    }

    /**
     * A {@link CharSequence} over a string that refuses to be read after a
     * deadline. Checking {@link System#nanoTime()} on every read would dominate an
     * ordinary match, so it is checked every 4096 reads.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final String text;
        private final long deadlineNanos;
        private int reads;

        DeadlineCharSequence(String text, long deadlineNanos) {
            this.text = text;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadlineNanos) {
                throw new MatchTimedOut();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(text.substring(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
