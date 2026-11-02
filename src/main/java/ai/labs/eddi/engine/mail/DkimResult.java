/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.Objects;

/**
 * What one {@code DKIM-Signature} of a message came to.
 * <p>
 * {@link #domain()} and {@link #selector()} are copied from the signature, so
 * on a forged message they are the forger's text; they are validated as DNS
 * names before they are kept (or null when they were not). {@link #reason()} is
 * always one of {@link DkimVerifier}'s fixed sentences, never message text, so
 * it is safe to log.
 *
 * @param domain
 *            the {@code d=} tag, lower-case, or null when missing or invalid
 * @param selector
 *            the {@code s=} tag, or null when missing or invalid
 * @param algorithm
 *            the {@code a=} tag as written (bounded), or null
 * @param outcome
 *            the result
 * @param reason
 *            why, in a fixed sentence; {@code "verified"} for a pass
 */
public record DkimResult(String domain, String selector, String algorithm, Outcome outcome, String reason) {

    /** The outcome of one signature. */
    public enum Outcome {
        /** The signature verifies against the published key. */
        PASS,
        /**
         * The signature does not verify, or is refused (RFC 6376 PERMFAIL, plus EDDI's
         * refusals: {@code rsa-sha1}, {@code l=}, short keys).
         */
        FAIL,
        /**
         * The key could not be fetched now (DNS timeout or failure, or the message's
         * lookup budget is spent). Not a pass; a later attempt may succeed.
         */
        TEMPERROR,
        /**
         * The signature verifies, but the key is flagged {@code t=y} (testing). RFC
         * 6376 §3.6.1: such mail must be treated as unsigned, so this is not a pass.
         */
        TESTING
    }

    public DkimResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
    }

    /** Whether this signature passed. */
    public boolean passed() {
        return outcome == Outcome.PASS;
    }
}
