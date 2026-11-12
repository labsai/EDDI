/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which headers of an authenticated message its aligned signature vouches for.
 * <p>
 * A DKIM signature covers the header instances its {@code h=} names and nothing
 * else, and anyone holding a signed mail can add, prepend or replace an
 * unsigned header and keep the signature valid. An aligned pass therefore
 * authenticates the {@code From} — and only those other headers whose every
 * instance is signed. {@link InboundMail#signedView()} drops the uncovered ones
 * that steer the mail, so an unsigned {@code Subject}, plus-tag in {@code To}
 * or {@code Reply-To} added to someone's genuine signed mail cannot choose the
 * target agent or where the reply goes.
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
 * @param signatureDigests
 *            the {@link DkimResult#signatureDigest()} of every verifying
 *            signature of a domain aligned with {@code From}, sorted: the
 *            replay key of the mail. All of them, not the first in header order
 *            — a holder could reorder the signature fields or drop one and
 *            present a key never seen
 */
public record SignedHeaders(boolean restricted, Set<String> covered, List<MailAddress> to, List<MailAddress> cc, List<String> signatureDigests) {

    private static final SignedHeaders UNRESTRICTED = new SignedHeaders(false, Set.of(), List.of(), List.of(), List.of());

    public SignedHeaders {
        signatureDigests = signatureDigests == null ? List.of() : signatureDigests.stream().filter(Objects::nonNull).distinct().sorted().toList();
        covered = covered == null ? Set.of() : Set.copyOf(covered);
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
    }

    /** With a single replay key (or none). */
    public SignedHeaders(boolean restricted, Set<String> covered, List<MailAddress> to, List<MailAddress> cc, String signatureDigest) {
        this(restricted, covered, to, cc, signatureDigest == null ? List.of() : List.of(signatureDigest));
    }

    /** The first replay key in sorted order, or null when there is none. */
    public String signatureDigest() {
        return signatureDigests.isEmpty() ? null : signatureDigests.get(0);
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
