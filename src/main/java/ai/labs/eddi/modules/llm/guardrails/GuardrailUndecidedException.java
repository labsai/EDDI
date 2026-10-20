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
 * The message and reason are EDDI-written; never put the screened text, or a
 * provider error message that may echo it, into either. No stack trace is
 * captured: this is an expected outcome, not a bug.
 */
public class GuardrailUndecidedException extends RuntimeException {

    private final String reason;
    private final double costUsd;

    public GuardrailUndecidedException(String reason, double costUsd) {
        super(reason, null, false, false);
        this.reason = reason;
        this.costUsd = costUsd;
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
