/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.DkimVerifier.TagList;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * DMARC identifier alignment for DKIM (RFC 7489 §3.1.1): does a passing
 * signature's {@code d=} speak for the {@code From} domain?
 * <p>
 * <b>Relaxed</b> alignment (the default, {@code adkim=r}): both domains have
 * the same organisational domain, found with the {@link PublicSuffixList} —
 * {@code mail.example.co.uk} aligns with {@code example.co.uk}, while
 * {@code example.co.uk} and {@code other.co.uk} do not. <b>Strict</b>
 * ({@code adkim=s}): the domains are equal.
 * <p>
 * The mode is read from the {@code From} domain's {@code _dmarc} record and,
 * when that domain has none, from its organisational domain's (§6.6.3). A
 * domain without a record is checked relaxed, as {@code adkim} defaults to. If
 * the lookup fails temporarily, strict is assumed — the conservative choice,
 * which still lets a signature by the exact {@code From} domain through — and
 * the result says so, so the caller may retry. A record set holding more than
 * one {@code v=DMARC1} record is no record (§6.6.3 step 4).
 * <p>
 * The {@code p=} policy is not applied: EDDI is not a receiving MTA and only
 * needs to know whether the sender is authenticated. <b>SPF is not
 * evaluated</b>: EDDI never sees the connecting IP address, so DMARC can only
 * be satisfied by aligned DKIM here, and a domain that relies on SPF alone
 * never authenticates (plan §8).
 * <p>
 * Thread-safe.
 */
@ApplicationScoped
public class DmarcAlignment {

    /** {@code adkim=}. */
    public enum Mode {
        RELAXED, STRICT
    }

    /**
     * The alignment mode that applies to a {@code From} domain.
     *
     * @param mode
     *            relaxed or strict
     * @param recordDomain
     *            the domain whose {@code _dmarc} record set the mode, or null when
     *            none did
     * @param temporaryFailure
     *            true when a lookup failed and strict was assumed
     */
    public record Policy(Mode mode, String recordDomain, boolean temporaryFailure) {
        public Policy {
            Objects.requireNonNull(mode, "mode");
        }
    }

    private enum Discovery {
        FOUND, NONE, AMBIGUOUS, TEMPFAIL
    }

    private record Found(Discovery discovery, Mode mode) {
    }

    private final MailDnsResolver dns;
    private final PublicSuffixList suffixes;

    @Inject
    public DmarcAlignment(MailDnsResolver dns) {
        this(dns, PublicSuffixList.bundled());
    }

    public DmarcAlignment(MailDnsResolver dns, PublicSuffixList suffixes) {
        this.dns = Objects.requireNonNull(dns, "dns");
        this.suffixes = Objects.requireNonNull(suffixes, "suffixes");
    }

    /** The organisational domain of a name, or null when it is not a domain. */
    public String organizationalDomain(String domain) {
        return suffixes.organizationalDomain(domain);
    }

    /**
     * Discovers the alignment mode for a {@code From} domain: its own
     * {@code _dmarc} record, else its organisational domain's.
     */
    public Policy policy(String fromDomain, MailDnsResolver.Budget budget) {
        String from = PublicSuffixList.toAscii(fromDomain);
        if (from == null) {
            return new Policy(Mode.STRICT, null, false);
        }
        Found own = discover(from, budget);
        if (own.discovery() == Discovery.FOUND) {
            return new Policy(own.mode(), from, false);
        }
        if (own.discovery() == Discovery.TEMPFAIL) {
            return new Policy(Mode.STRICT, null, true);
        }
        if (own.discovery() == Discovery.AMBIGUOUS) {
            return new Policy(Mode.RELAXED, null, false);
        }
        String organisation = suffixes.organizationalDomain(from);
        if (organisation != null && !organisation.equals(from)) {
            Found inherited = discover(organisation, budget);
            if (inherited.discovery() == Discovery.FOUND) {
                return new Policy(inherited.mode(), organisation, false);
            }
            if (inherited.discovery() == Discovery.TEMPFAIL) {
                return new Policy(Mode.STRICT, null, true);
            }
        }
        return new Policy(Mode.RELAXED, null, false);
    }

    /**
     * Whether a signing domain aligns with a {@code From} domain under a mode.
     * False when either is not a domain.
     */
    public boolean aligned(String signingDomain, String fromDomain, Mode mode) {
        String d = PublicSuffixList.toAscii(signingDomain);
        String from = PublicSuffixList.toAscii(fromDomain);
        if (d == null || from == null) {
            return false;
        }
        if (mode == Mode.STRICT) {
            return d.equals(from);
        }
        String orgD = suffixes.organizationalDomain(d);
        return orgD != null && orgD.equals(suffixes.organizationalDomain(from));
    }

    private Found discover(String domain, MailDnsResolver.Budget budget) {
        MailDnsResolver.TxtLookup lookup = dns.lookupTxt("_dmarc." + domain, budget);
        return switch (lookup.status()) {
            case FOUND -> fromRecords(lookup.records());
            case TEMPFAIL, BUDGET_EXHAUSTED -> new Found(Discovery.TEMPFAIL, null);
            default -> new Found(Discovery.NONE, null);
        };
    }

    private static Found fromRecords(List<String> records) {
        List<TagList> dmarc = new ArrayList<>();
        for (String record : records) {
            TagList tags = TagList.parse(record);
            if (tags != null && "v".equals(tags.first()) && "DMARC1".equalsIgnoreCase(tags.value("v"))) {
                dmarc.add(tags);
            }
        }
        if (dmarc.isEmpty()) {
            return new Found(Discovery.NONE, null);
        }
        if (dmarc.size() > 1) {
            // Several: policy discovery ends and DMARC does not apply (§6.6.3).
            return new Found(Discovery.AMBIGUOUS, null);
        }
        String adkim = dmarc.get(0).value("adkim");
        Mode mode = adkim != null && "s".equals(adkim.toLowerCase(Locale.ROOT)) ? Mode.STRICT : Mode.RELAXED;
        return new Found(Discovery.FOUND, mode);
    }
}
