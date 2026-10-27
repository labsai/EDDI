/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

/**
 * Thrown by a {@link ContentGuardrail} that could not reach a decision — its
 * backing service was unreachable, timed out, or answered something it cannot
 * interpret. The {@link GuardrailService} treats it exactly like any other
 * failing guardrail (the configured {@code failMode} decides between blocking
 * and warning), but records {@link #reason()} instead of the generic
 * {@code guardrail_error}, and still counts {@link #costUsd()} — a classifier
 * that answered gibberish was billed for it.
 * <p>
 * <p>
 * A guardrail that gave up part-way may already have found something — a
 * pattern list that timed out on its third pattern after the first one matched.
 * That {@link #partialFinding()} is not lost: a guardrail that fails open
 * applies it (a {@code redact} keeps the rewrites it managed, a {@code block}
 * refuses a text it did find a match in), while one that fails closed blocks as
 * for any other failure.
 * <p>
 * The message and reason are EDDI-written; never put the screened text, or a
 * provider error message that may echo it, into either. No stack trace is
 * captured: this is an expected outcome, not a bug.
 */
public class GuardrailUndecidedException extends RuntimeException {

    private final String reason;
    private final double costUsd;
    private final transient GuardrailVerdict partialFinding;

    public GuardrailUndecidedException(String reason, double costUsd) {
        this(reason, costUsd, null);
    }

    /**
     * @param partialFinding
     *            a triggered verdict for what was found before the guardrail gave
     *            up (e.g. a rewrite with the matches so far), or null when nothing
     *            was
     */
    public GuardrailUndecidedException(String reason, double costUsd, GuardrailVerdict partialFinding) {
        super(reason, null, false, false);
        this.reason = reason;
        this.costUsd = costUsd;
        this.partialFinding = partialFinding != null && partialFinding.triggered() ? partialFinding : null;
    }

    /** What was found before the guardrail gave up; null when nothing was. */
    public GuardrailVerdict partialFinding() {
        return partialFinding;
    }

    /** The audit reason, e.g. {@code classifier_unavailable}. */
    public String reason() {
        return reason;
    }

    /** What the failed evaluation cost anyway; 0 when nothing was billed. */
    public double costUsd() {
        return costUsd;
    }
}
