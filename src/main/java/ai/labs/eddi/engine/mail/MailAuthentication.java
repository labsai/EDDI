/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.Objects;

/**
 * Whether the {@code From} domain of a message is authenticated — the verdict
 * of EDDI's own DKIM check and DMARC alignment (email plan §8).
 * <p>
 * The parser does not verify anything and always produces
 * {@link #notEvaluated()}; the verifier (a later phase) works on the raw bytes
 * and attaches its verdict with {@link InboundMail#withAuthentication}. Only
 * {@link Verdict#ALIGNED_PASS} counts as authenticated, so "not evaluated yet"
 * can never be mistaken for a pass.
 *
 * @param verdict
 *            the outcome
 * @param signingDomain
 *            the {@code d=} of the passing signature, or null
 */
public record MailAuthentication(Verdict verdict, String signingDomain) {

    /** The outcomes, as in the plan, plus the state before any check ran. */
    public enum Verdict {
        /** No verifier has looked at this message. */
        NOT_EVALUATED,
        /** A valid DKIM signature whose domain aligns with the From domain. */
        ALIGNED_PASS,
        /** A valid signature from a domain that does not align with From. */
        PASS_UNALIGNED,
        /** A signature that does not verify. */
        FAIL,
        /** No signature. */
        NONE
    }

    private static final MailAuthentication NOT_EVALUATED = new MailAuthentication(Verdict.NOT_EVALUATED, null);

    public MailAuthentication {
        Objects.requireNonNull(verdict, "verdict");
    }

    /** The state of a freshly parsed message. */
    public static MailAuthentication notEvaluated() {
        return NOT_EVALUATED;
    }

    /** Only an aligned pass authenticates the sender's domain. */
    public boolean isAuthenticated() {
        return verdict == Verdict.ALIGNED_PASS;
    }
}
