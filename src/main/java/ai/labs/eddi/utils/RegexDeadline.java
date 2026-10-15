/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

/**
 * Puts a time budget on a {@link java.util.regex.Matcher} run.
 * <p>
 * {@code java.util.regex} has no timeout, and a pattern an agent designer wrote
 * — a dictionary {@code regEx}, a group's artifact validator — can backtrack
 * exponentially on an input the designer never tried. Backtracking re-reads
 * characters, so a {@link CharSequence} whose {@code charAt} checks the clock
 * is hit constantly by exactly the pathological patterns this exists to stop;
 * the check is sampled to keep the fast path cheap. A wrapper is single-matcher
 * use only — the sampling counter is not thread-safe, which is also the
 * Matcher's own contract.
 */
public final class RegexDeadline {

    private RegexDeadline() {
    }

    /**
     * Thrown from {@code charAt} once the budget is spent; the match aborts and the
     * caller decides what a timed-out match means. Stackless: it carries no
     * diagnostic beyond the fact.
     */
    public static final class MatchDeadlineExceededException extends RuntimeException {
        public MatchDeadlineExceededException() {
            super("regex match exceeded its time budget", null, false, false);
        }
    }

    /**
     * Wraps {@code content} so a match against it aborts with
     * {@link MatchDeadlineExceededException} once {@code budgetNanos} have passed
     * from this call.
     */
    public static CharSequence guard(String content, long budgetNanos) {
        return guardUntil(content, System.nanoTime() + budgetNanos);
    }

    /**
     * Like {@link #guard}, but against an absolute {@link System#nanoTime()}
     * instant — so several matches can share one deadline.
     */
    public static CharSequence guardUntil(String content, long deadline) {
        if (System.nanoTime() > deadline) {
            // The sampled check inside charAt would let a short match through after
            // the deadline; a match that starts after it is refused outright.
            throw new MatchDeadlineExceededException();
        }
        return new CharSequence() {
            private int accesses;

            @Override
            public int length() {
                return content.length();
            }

            @Override
            public char charAt(int index) {
                if ((++accesses & 0x3FF) == 0 && System.nanoTime() > deadline) {
                    throw new MatchDeadlineExceededException();
                }
                return content.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return content.subSequence(start, end);
            }

            @Override
            public String toString() {
                return content;
            }
        };
    }
}
