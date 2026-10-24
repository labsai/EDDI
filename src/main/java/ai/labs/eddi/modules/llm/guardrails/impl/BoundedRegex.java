/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs author regular expressions over untrusted text within a time budget. An
 * input cap bounds the text, not the work: a pattern such as {@code (a+)+$}
 * backtracks exponentially even on a short input. The matcher reads the text
 * through a view that checks the clock, so a runaway match ends in
 * {@link BudgetExceededException} instead of running for minutes on a request
 * thread.
 * <p>
 * What a caller does with an exceeded budget is a decision, never "no match": a
 * guardrail that cannot finish its patterns has not checked the text and
 * reports it as could-not-decide, so its {@code failMode} applies — a
 * {@code block} guardrail refuses the text rather than letting an expensive
 * input through.
 */
final class BoundedRegex {

    /** The pattern ran past its budget; the text was not fully checked. */
    static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException() {
            super(null, null, false, false);
        }
    }

    private BoundedRegex() {
    }

    /** An absolute {@link System#nanoTime()} deadline {@code budgetMs} from now. */
    static long deadline(long budgetMs) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
    }

    /**
     * A matcher over {@code text} that throws {@link BudgetExceededException} once
     * {@code deadlineNanos} (absolute, {@link System#nanoTime()}) has passed — also
     * from {@link Matcher#replaceAll}, which reads through the same view.
     */
    static Matcher matcher(Pattern pattern, CharSequence text, long deadlineNanos) {
        return pattern.matcher(new BudgetedCharSequence(text, deadlineNanos));
    }

    /**
     * A view of the text that gives up after a deadline: the regex engine reads it
     * character by character, so a catastrophically backtracking pattern hits the
     * check. The clock is read every 256 reads.
     */
    private static final class BudgetedCharSequence implements CharSequence {
        private final CharSequence text;
        private final long deadlineNanos;
        private int reads;

        private BudgetedCharSequence(CharSequence text, long deadlineNanos) {
            this.text = text;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFF) == 0 && System.nanoTime() - deadlineNanos > 0) {
                throw new BudgetExceededException();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedCharSequence(text.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
