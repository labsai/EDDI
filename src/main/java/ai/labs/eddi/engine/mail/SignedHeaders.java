/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.List;
import java.util.Set;

/**
 * Which headers of an authenticated message its aligned signature vouches for.
 * <p>
 * A DKIM signature covers the header instances its {@code h=} names and nothing
 * else, and anyone holding a signed mail can add, prepend or replace an
 * unsigned header and keep the signature valid. An aligned pass therefore
 * authenticates the {@code From} — and only those other headers whose every
 * instance is signed. {@link InboundMail#signedView()} drops the rest, so an
 * unsigned {@code Subject}, plus-tag in {@code To}, {@code In-Reply-To},
 * {@code References} or {@code Reply-To} added to someone's genuine signed mail
 * cannot steer where it is routed, which conversation it continues, or where
 * the reply goes.
 *
 * @param restricted
 *            false when nothing is restricted: the verdict is not an aligned
 *            pass, so no identity rides on the headers and they are as
 *            trustworthy as the {@code From} itself (an aligned pass on a
 *            provider's word is restricted to nothing beyond the {@code From})
 * @param covered
 *            lower-case names of which every instance in the message is signed
 *            (a name the signature lists but the message lacks counts: its
 *            absence is signed)
 * @param to
 *            the {@code To} mailboxes of the signed instances only
 * @param cc
 *            the {@code Cc} mailboxes of the signed instances only
 * @param signatureDigest
 *            the aligned signature's {@link DkimResult#signatureDigest()}, or
 *            null
 */
public record SignedHeaders(boolean restricted, Set<String> covered, List<MailAddress> to, List<MailAddress> cc, String signatureDigest) {

    private static final SignedHeaders UNRESTRICTED = new SignedHeaders(false, Set.of(), List.of(), List.of(), null);

    public SignedHeaders {
        covered = covered == null ? Set.of() : Set.copyOf(covered);
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
    }

    /** Nothing restricted: every header is read as the message has it. */
    public static SignedHeaders unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * Whether {@code name} (lower-case) may be read from the message: every
     * instance of it is signed, or nothing is restricted.
     */
    public boolean covers(String name) {
        return !restricted || covered.contains(name);
    }
}
