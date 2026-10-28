/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts a time budget on a {@link java.util.regex.Matcher} run.
 * <p>
 * {@code java.util.regex} has no timeout, and a pattern an agent designer wrote
 * — a dictionary {@code regEx}, a group's artifact validator, a guardrail's
 * {@code patterns} or {@code labelPattern} — can backtrack exponentially on an
 * input the designer never tried. An input cap bounds the text, not the work.
 * Backtracking re-reads characters, so a {@link CharSequence} whose
 * {@code charAt} checks the clock is hit constantly by exactly the pathological
 * patterns this exists to stop; the check is sampled to keep the fast path
 * cheap. Sub-sequences the engine takes carry the same deadline. A wrapper is
 * single-matcher use only — the sampling counter is not thread-safe, which is
 * also the Matcher's own contract.
 * <p>
 * What a timed-out match means is the caller's decision, never silently "no
 * match" where that would let something through: a guardrail reports it as
 * could-not-decide, so its {@code failMode} applies.
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

    /** An absolute {@link System#nanoTime()} deadline {@code budgetMs} from now. */
    public static long deadlineIn(long budgetMs) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
    }

    /**
     * Wraps {@code content} so a match against it aborts with
     * {@link MatchDeadlineExceededException} once {@code budgetNanos} have passed
     * from this call.
     */
    public static CharSequence guard(CharSequence content, long budgetNanos) {
        return guardUntil(content, System.nanoTime() + budgetNanos);
    }

    /**
     * Like {@link #guard}, but against an absolute {@link System#nanoTime()}
     * instant — so several matches can share one deadline.
     */
    public static CharSequence guardUntil(CharSequence content, long deadline) {
        if (System.nanoTime() > deadline) {
            // The sampled check inside charAt would let a short match through after
            // the deadline; a match that starts after it is refused outright.
            throw new MatchDeadlineExceededException();
        }
        return new Guarded(content, deadline);
    }

    /**
     * A matcher of {@code pattern} over {@code content} bounded by the absolute
     * {@code deadline} — also for {@link Matcher#replaceAll}, which reads through
     * the same view.
     */
    public static Matcher matcher(Pattern pattern, CharSequence content, long deadline) {
        return pattern.matcher(guardUntil(content, deadline));
    }

    private static final class Guarded implements CharSequence {
        private final CharSequence content;
        private final long deadline;
        private int accesses;

        private Guarded(CharSequence content, long deadline) {
            this.content = content;
            this.deadline = deadline;
        }

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
            // Same deadline: the engine matches some constructs on a sub-sequence.
            return new Guarded(content.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return content.toString();
        }
    }
}
