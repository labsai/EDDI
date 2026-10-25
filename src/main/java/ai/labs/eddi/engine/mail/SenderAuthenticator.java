/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.DkimResult.Outcome;
import ai.labs.eddi.engine.mail.MailAuthentication.Verdict;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Who sent this mail, as far as DNS and cryptography can say: EDDI's own DKIM
 * verification plus DMARC alignment (email plan §8), in one call.
 * <p>
 * A transport hands over the raw bytes it received and the {@link InboundMail}
 * the parser made of them; {@link #authenticate(byte[], InboundMail)} answers
 * with the {@link MailAuthentication} to attach with
 * {@link InboundMail#withAuthentication}. The rules:
 * <ul>
 * <li><b>{@link Verdict#ALIGNED_PASS}</b> only when a signature passes
 * <em>and</em> its {@code d=} aligns with the {@code From} domain (relaxed or
 * strict as the domain's {@code _dmarc} record says) <em>and</em> the message
 * has exactly one {@code From} field with exactly one mailbox. A second
 * {@code From}, which many clients show although the signature covers only the
 * other one, can never ride on a valid signature; neither can a duplicated
 * {@code Subject}, {@code Date}, {@code Message-ID}, {@code In-Reply-To} or
 * {@code References} ({@link MailAnomaly#DUPLICATE_SINGLETON_HEADER}).</li>
 * <li><b>{@link Verdict#PASS_UNALIGNED}</b>: a signature passes, but for
 * another domain (a mailing list, a forwarder, a bulk sender signing as
 * itself).</li>
 * <li><b>{@link Verdict#FAIL}</b>: signatures exist and none passes, the
 * message cannot be read, or {@code From} is missing or ambiguous.</li>
 * <li><b>{@link Verdict#NONE}</b>: no signature, or only signatures under a key
 * in testing mode (RFC 6376 §3.6.1: treat as unsigned).</li>
 * </ul>
 * What an aligned pass does <em>not</em> prove: that a particular person wrote
 * the mail (only that the domain's signing servers sent it), anything about
 * mail forwarded or rewritten by a list (which usually breaks the signature;
 * ARC is not evaluated), or anything SPF would say (EDDI never sees the
 * connecting IP, so SPF is not evaluated at all).
 * <p>
 * Bounded: one DNS budget per message ({@code
 * eddi.email.dns.max-lookups-per-message}) shared by every key and DMARC
 * lookup, at most {@link DkimVerifier#MAX_SIGNATURES} signatures, a header
 * block of {@link MailLimits#DEFAULT_MAX_HEADER_BYTES}, the body hashed at most
 * twice. Never throws: hostile or broken input is a {@code FAIL} or
 * {@code NONE} with a reason. Each verdict is counted as
 * {@code eddi.email.auth{result}}.
 * <p>
 * Thread-safe.
 */
@ApplicationScoped
public class SenderAuthenticator {

    private static final Logger LOGGER = Logger.getLogger(SenderAuthenticator.class);

    static final String METER_AUTH = "eddi.email.auth";

    private static final Set<MailAnomaly> AMBIGUOUS_FROM = Set.of(MailAnomaly.MULTIPLE_FROM, MailAnomaly.MISSING_FROM);

    private final DkimVerifier dkim;
    private final DmarcAlignment dmarc;
    private final MailDnsResolver dns;
    private final MeterRegistry meterRegistry;

    /** How far back a delivery instant may reach unless configured otherwise. */
    static final Duration DEFAULT_MAX_DELIVERY_AGE = Duration.ofDays(7);

    private final Clock clock;
    private final Duration maxDeliveryAge;

    @Inject
    public SenderAuthenticator(DkimVerifier dkim, DmarcAlignment dmarc, MailDnsResolver dns, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.email.dkim.max-delivery-age", defaultValue = "7d") Duration maxDeliveryAge) {
        this(dkim, dmarc, dns, meterRegistry, maxDeliveryAge, Clock.systemUTC());
    }

    /** Defaults: a 7-day maximum delivery age and the system clock. */
    public SenderAuthenticator(DkimVerifier dkim, DmarcAlignment dmarc, MailDnsResolver dns, MeterRegistry meterRegistry) {
        this(dkim, dmarc, dns, meterRegistry, DEFAULT_MAX_DELIVERY_AGE, Clock.systemUTC());
    }

    /** For tests: any maximum age, any clock. */
    public SenderAuthenticator(DkimVerifier dkim, DmarcAlignment dmarc, MailDnsResolver dns, MeterRegistry meterRegistry,
            Duration maxDeliveryAge, Clock clock) {
        this.dkim = Objects.requireNonNull(dkim, "dkim");
        this.dmarc = Objects.requireNonNull(dmarc, "dmarc");
        this.dns = Objects.requireNonNull(dns, "dns");
        this.meterRegistry = meterRegistry;
        this.maxDeliveryAge = maxDeliveryAge == null || maxDeliveryAge.isNegative() ? DEFAULT_MAX_DELIVERY_AGE : maxDeliveryAge;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Authenticates a message with EDDI's own checks only.
     *
     * @param raw
     *            the RFC 5322 bytes exactly as the transport received them — the
     *            same bytes {@code parsed} was made from
     * @param parsed
     *            what {@link MimeMessageParser} made of {@code raw}
     */
    public MailAuthentication authenticate(byte[] raw, InboundMail parsed) {
        return authenticate(raw, parsed, null);
    }

    /**
     * Authenticates a message, with a provider's signed verdict to fall back on
     * when the local check cannot run ({@link SignedProviderVerdict}).
     *
     * @param raw
     *            the raw bytes, or null when the transport does not have them
     * @param parsed
     *            the parsed message
     * @param fallback
     *            a verified provider verdict, or null
     */
    public MailAuthentication authenticate(byte[] raw, InboundMail parsed, SignedProviderVerdict fallback) {
        return authenticate(raw, parsed, null, fallback);
    }

    /**
     * Authenticates a message whose signatures are judged as of its delivery: a
     * short {@code x=} that was valid when the mailbox server accepted the mail
     * does not expire while the mail waits to be polled or for a budget.
     * <p>
     * {@code deliveredAt} must come from the receiving side — the mailbox server's
     * INTERNALDATE, a provider's receive timestamp — never from a header the sender
     * wrote. It is still bounded, so a broken or forged value cannot revive an old
     * signature or postdate one: it is never later than now and never earlier than
     * now minus {@code eddi.email.dkim.max-delivery-age} (default 7 days); outside
     * that window the nearest edge is used.
     *
     * @param raw
     *            the raw bytes, or null when the transport does not have them
     * @param parsed
     *            the parsed message
     * @param deliveredAt
     *            when the receiving server accepted the message, or null to judge
     *            as of now
     * @param fallback
     *            a verified provider verdict, or null
     */
    public MailAuthentication authenticate(byte[] raw, InboundMail parsed, Instant deliveredAt, SignedProviderVerdict fallback) {
        MailAuthentication result;
        try {
            result = evaluate(raw, parsed, effectiveInstant(deliveredAt), fallback);
        } catch (RuntimeException e) {
            // Nothing in a message may turn into an exception for the caller.
            LOGGER.warnf("Sender authentication failed internally (%s); the message is treated as unauthenticated",
                    e.getClass().getSimpleName());
            result = new MailAuthentication(Verdict.FAIL, null, "internal error during verification", false, List.of());
        }
        count(result.verdict());
        return result;
    }

    /**
     * The instant timestamps are judged at: {@code deliveredAt} clamped to [now -
     * max age, now].
     */
    Instant effectiveInstant(Instant deliveredAt) {
        Instant now = clock.instant();
        if (deliveredAt == null || deliveredAt.isAfter(now)) {
            return now;
        }
        Instant earliest = now.minus(maxDeliveryAge);
        return deliveredAt.isBefore(earliest) ? earliest : deliveredAt;
    }

    private MailAuthentication evaluate(byte[] raw, InboundMail parsed, Instant evaluatedAt, SignedProviderVerdict fallback) {
        if (parsed == null) {
            return fail("no parsed message", false, List.of());
        }
        MailDnsResolver.Budget budget = dns.newBudget();
        if (raw == null || raw.length == 0) {
            // Without the bytes the From fields cannot be counted; the parser's
            // anomalies still catch a second From.
            return fallback != null
                    ? fromProvider(fallback, parsed, parsed.from() == null ? 0 : 1, budget, List.of())
                    : fail("the raw message is not available", false, List.of());
        }
        if (raw.length != parsed.rawSize()) {
            return fail("the raw bytes are not the message that was parsed", false, List.of());
        }
        DkimVerifier.Verification verification = dkim.verify(raw, budget, evaluatedAt);
        if (verification.messageFailure() != null) {
            return fail(verification.messageFailure(), false, List.of());
        }
        List<DkimResult> results = verification.results();
        if (verification.unsigned()) {
            return new MailAuthentication(Verdict.NONE, null, "the message has no DKIM signature", false, results);
        }
        List<DkimResult> passing = results.stream().filter(DkimResult::passed).toList();
        boolean anyTemporary = results.stream().anyMatch(r -> r.outcome() == Outcome.TEMPERROR);
        if (passing.isEmpty()) {
            if (results.stream().allMatch(r -> r.outcome() == Outcome.TESTING)) {
                return new MailAuthentication(Verdict.NONE, null, "only signatures under a key in testing mode (t=y)", false, results);
            }
            if (fallback != null && results.stream().allMatch(r -> r.outcome() == Outcome.TEMPERROR)) {
                return fromProvider(fallback, parsed, verification.fromFieldCount(), budget, results);
            }
            DkimResult first = results.stream().filter(r -> r.outcome() == Outcome.FAIL).findFirst()
                    .orElseGet(() -> results.stream().filter(r -> r.outcome() == Outcome.TEMPERROR).findFirst().orElse(results.get(0)));
            return fail(first.reason(), anyTemporary, results);
        }
        String ambiguity = ambiguity(parsed, verification.fromFieldCount());
        if (ambiguity != null) {
            return fail(ambiguity, false, results);
        }
        String fromDomain = parsed.from().domain();
        DmarcAlignment.Policy policy = dmarc.policy(fromDomain, budget);
        for (DkimResult pass : passing) {
            if (dmarc.aligned(pass.domain(), fromDomain, policy.mode())) {
                return new MailAuthentication(Verdict.ALIGNED_PASS, pass.domain(),
                        "a signature of the From domain verifies (" + mode(policy) + " alignment)", false, results);
            }
        }
        return new MailAuthentication(Verdict.PASS_UNALIGNED, passing.get(0).domain(),
                policy.temporaryFailure()
                        ? "a signature verifies, but the From domain's DMARC policy could not be read, so strict "
                                + "alignment was assumed and it does not align"
                        : "a signature verifies, but for a domain that does not align with From (" + mode(policy) + ")",
                policy.temporaryFailure() || anyTemporary, results);
    }

    private static String mode(DmarcAlignment.Policy policy) {
        return policy.mode().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Why the message's sender is ambiguous whatever the signatures say, or null.
     */
    private static String ambiguity(InboundMail parsed, int fromFieldCount) {
        if (parsed.from() == null || fromFieldCount != 1 || parsed.anomalies().stream().anyMatch(AMBIGUOUS_FROM::contains)) {
            return "the From header is missing or ambiguous";
        }
        if (parsed.anomalies().contains(MailAnomaly.DUPLICATE_SINGLETON_HEADER)) {
            return "a header that must appear once appears more than once";
        }
        return null;
    }

    private MailAuthentication fromProvider(SignedProviderVerdict fallback, InboundMail parsed, int fromFieldCount,
                                            MailDnsResolver.Budget budget, List<DkimResult> local) {
        String provider = safeProvider(fallback.provider());
        if (!fallback.dkimPass()) {
            return fail("the local check could not run and " + provider + " reports no passing signature", !local.isEmpty(), local);
        }
        String domain = DkimVerifier.validDomain(fallback.signingDomain());
        if (domain == null) {
            return fail("the local check could not run and " + provider + " names no signing domain", !local.isEmpty(), local);
        }
        // Ambiguity is judged on what EDDI parsed, never on the provider's word.
        String ambiguity = ambiguity(parsed, fromFieldCount);
        if (ambiguity != null) {
            return fail(ambiguity, false, local);
        }
        String fromDomain = parsed.from().domain();
        DmarcAlignment.Policy policy = dmarc.policy(fromDomain, budget);
        if (dmarc.aligned(domain, fromDomain, policy.mode())) {
            return new MailAuthentication(Verdict.ALIGNED_PASS, domain,
                    "the local check could not run; " + provider + " reports a passing signature of the From domain", false, local);
        }
        return new MailAuthentication(Verdict.PASS_UNALIGNED, domain,
                "the local check could not run; " + provider + " reports a passing signature that does not align with From",
                policy.temporaryFailure(), local);
    }

    private static String safeProvider(String provider) {
        if (provider == null || provider.isBlank() || provider.length() > 32 || !provider.matches("[A-Za-z0-9_.-]+")) {
            return "the provider";
        }
        return provider;
    }

    private static MailAuthentication fail(String reason, boolean temporary, List<DkimResult> results) {
        return new MailAuthentication(Verdict.FAIL, null, reason, temporary, results);
    }

    private void count(Verdict verdict) {
        if (meterRegistry != null) {
            meterRegistry.counter(METER_AUTH, "result", verdict.name().toLowerCase(Locale.ROOT)).increment();
        }
    }
}
