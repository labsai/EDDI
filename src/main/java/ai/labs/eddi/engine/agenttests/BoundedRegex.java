/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

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
 * </ul>
 * A timed-out match is reported as a failed expectation, never as a pass.
 *
 * @since 6.5.0
 */
public final class BoundedRegex {

    /** Text longer than this is cut before matching. */
    public static final int MAX_TEXT_LENGTH = 100_000;

    /** Wall-clock budget of one match. */
    public static final long DEFAULT_TIMEOUT_MS = 250;

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
