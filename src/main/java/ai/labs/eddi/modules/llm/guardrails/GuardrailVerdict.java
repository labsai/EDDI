/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import java.util.List;

/**
 * What a {@link ContentGuardrail} found. The guardrail only reports; the
 * configured {@link GuardrailAction} decides what happens.
 *
 * @param kind
 *            {@code ALLOW} (nothing found), {@code REWRITE} (something found,
 *            and {@code text} is the cleaned-up version) or {@code VIOLATION}
 *            (something found, no rewrite possible)
 * @param text
 *            the rewritten text for {@code REWRITE}, otherwise null
 * @param reason
 *            a short EDDI-written reason for audit and SSE — never a copy of
 *            the screened text, which may hold exactly what the guardrail
 *            exists to keep out of the ledger
 * @param repromptHint
 *            output guardrails: what to tell the model on a reprompt, or null
 *            for the generic corrective message
 * @param costUsd
 *            what evaluating the guardrail itself cost (a guardrail that calls
 *            a model); 0 for the built-ins
 * @param issues
 *            the issue codes a guardrail that grades the text found
 *            ({@code judge}), author-declared and therefore safe to record;
 *            empty for every other type. Used to rate attempts for
 *            {@code onExhausted: deliver-best}
 */
public record GuardrailVerdict(Kind kind, String text, String reason, String repromptHint, double costUsd, List<String> issues) {

    /** The three possible findings. */
    public enum Kind {
        ALLOW, REWRITE, VIOLATION
    }

    private static final GuardrailVerdict ALLOW_VERDICT = new GuardrailVerdict(Kind.ALLOW, null, null, null, 0.0);

    /** A verdict without issue codes. */
    public GuardrailVerdict(Kind kind, String text, String reason, String repromptHint, double costUsd) {
        this(kind, text, reason, repromptHint, costUsd, List.of());
    }

    public GuardrailVerdict {
        issues = issues != null ? List.copyOf(issues) : List.of();
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (kind == Kind.REWRITE && text == null) {
            throw new IllegalArgumentException("a REWRITE verdict needs the rewritten text");
        }
    }

    public static GuardrailVerdict allow() {
        return ALLOW_VERDICT;
    }

    public static GuardrailVerdict rewrite(String text, String reason) {
        return new GuardrailVerdict(Kind.REWRITE, text, reason, null, 0.0);
    }

    public static GuardrailVerdict violation(String reason) {
        return new GuardrailVerdict(Kind.VIOLATION, null, reason, null, 0.0);
    }

    public static GuardrailVerdict violation(String reason, String repromptHint) {
        return new GuardrailVerdict(Kind.VIOLATION, null, reason, repromptHint, 0.0);
    }

    /** True when the guardrail found something (a rewrite or a violation). */
    public boolean triggered() {
        return kind != Kind.ALLOW;
    }

    /**
     * A violation that names the issue codes found, with the reprompt hint built
     * from the author's fix texts for them.
     */
    public static GuardrailVerdict issues(List<String> issues, String reason, String repromptHint) {
        return new GuardrailVerdict(Kind.VIOLATION, null, reason, repromptHint, 0.0, issues);
    }

    /** This verdict with the cost of evaluating it attached. */
    public GuardrailVerdict withCost(double cost) {
        return new GuardrailVerdict(kind, text, reason, repromptHint, cost, issues);
    }

    /**
     * How bad this verdict rates the text, for choosing the best of several
     * attempts: 0 when nothing was found, the number of issue codes for a grading
     * guardrail, 1 for any other finding.
     */
    public int severity() {
        if (!triggered()) {
            return 0;
        }
        return issues.isEmpty() ? 1 : issues.size();
    }
}
