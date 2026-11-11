/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.List;
import java.util.Objects;

/**
 * Whether the {@code From} domain of a message is authenticated — the verdict
 * of EDDI's own DKIM check and DMARC alignment (email plan §8).
 * <p>
 * The parser does not verify anything and always produces
 * {@link #notEvaluated()}; {@link SenderAuthenticator} works on the raw bytes
 * and attaches its verdict with {@link InboundMail#withAuthentication}. Only
 * {@link Verdict#ALIGNED_PASS} counts as authenticated, so "not evaluated yet"
 * can never be mistaken for a pass.
 * <p>
 * An aligned pass authenticates the <em>domain</em> of {@code From}, not the
 * person: anyone who can send through that domain's signing servers can write
 * as any of its addresses.
 *
 * @param verdict
 *            the outcome
 * @param signingDomain
 *            the {@code d=} of the passing signature (the aligned one for
 *            {@link Verdict#ALIGNED_PASS}), or null
 * @param reason
 *            why, as one of the verifier's fixed sentences (safe to log), or
 *            null when not evaluated
 * @param temporary
 *            true when a DNS failure or the per-message lookup budget kept a
 *            signature or the DMARC policy from being checked, so trying again
 *            later may give a better verdict
 * @param signatures
 *            every evaluated signature with its outcome, top to bottom (at most
 *            {@link DkimVerifier#MAX_SIGNATURES})
 * @param signedHeaders
 *            for an aligned pass, which headers the aligned signature covers
 *            ({@link InboundMail#signedView()} reads only those);
 *            {@link SignedHeaders#unrestricted()} otherwise
 */
public record MailAuthentication(Verdict verdict, String signingDomain, String reason, boolean temporary, List<DkimResult> signatures,
        SignedHeaders signedHeaders) {

    /** The outcomes, as in the plan, plus the state before any check ran. */
    public enum Verdict {
        /** No verifier has looked at this message. */
        NOT_EVALUATED,
        /** A valid DKIM signature whose domain aligns with the From domain. */
        ALIGNED_PASS,
        /** A valid signature from a domain that does not align with From. */
        PASS_UNALIGNED,
        /**
         * Signatures that do not verify or could not be checked, or a message whose
         * {@code From} is missing or ambiguous.
         */
        FAIL,
        /** No signature (or only signatures under a key in testing mode). */
        NONE
    }

    private static final MailAuthentication NOT_EVALUATED = new MailAuthentication(Verdict.NOT_EVALUATED, null);

    public MailAuthentication {
        Objects.requireNonNull(verdict, "verdict");
        signatures = signatures == null ? List.of() : List.copyOf(signatures);
        signedHeaders = signedHeaders == null ? SignedHeaders.unrestricted() : signedHeaders;
    }

    /** A verdict that restricts no header. */
    public MailAuthentication(Verdict verdict, String signingDomain, String reason, boolean temporary, List<DkimResult> signatures) {
        this(verdict, signingDomain, reason, temporary, signatures, SignedHeaders.unrestricted());
    }

    /** A verdict without details. */
    public MailAuthentication(Verdict verdict, String signingDomain) {
        this(verdict, signingDomain, null, false, List.of());
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
