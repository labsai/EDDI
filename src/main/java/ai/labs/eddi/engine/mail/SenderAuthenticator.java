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
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.ParseException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
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
 * {@code Subject}, {@code Date}, {@code Message-ID}, {@code In-Reply-To},
 * {@code References}, {@code Reply-To}, {@code Content-Type},
 * {@code Content-Transfer-Encoding} or {@code MIME-Version}
 * ({@link MailAnomaly#DUPLICATE_SINGLETON_HEADER}). Two more conditions bind
 * the pass to the mail as it was signed: an unsigned instance of a header that
 * decides how the body is read refuses it
 * ({@code eddi.email.dkim.require-signed-content-headers},
 * {@link #contentHeadersRefusal}), and so does a signature older than
 * {@code eddi.email.dkim.max-signature-age} at delivery (a replay). The pass
 * then carries the {@link SignedHeaders} its signature covers, and
 * {@link InboundMail#signedView()} reads nothing else.</li>
 * <li><b>{@link Verdict#PASS_UNALIGNED}</b>: a signature passes for a domain
 * that aligns with {@code From} under relaxed but not under the strict
 * alignment the domain's DMARC record asks for (or assumed when that record
 * could not be read). A signature by an unrelated domain (a mailing list, a
 * forwarder, a bulk sender signing as itself) is not checked at all.</li>
 * <li><b>{@link Verdict#FAIL}</b>: signatures exist and none passes, the
 * message cannot be read, or {@code From} is missing or ambiguous — and also
 * when no signature is by a domain that could align with {@code From}: those
 * are {@link DkimResult.Outcome#SKIPPED}, their keys never looked up.</li>
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

    /** How old a signature may be, at delivery, unless configured otherwise. */
    static final Duration DEFAULT_MAX_SIGNATURE_AGE = Duration.ofDays(7);

    /**
     * The headers that decide how the body is read. An unsigned instance of any of
     * them lets the holder of a signed mail make EDDI read the signed body
     * differently — a prepended {@code Content-Type} with a boundary of their own
     * picks out a part they quoted into the victim's reply.
     */
    static final List<String> CONTENT_HEADERS = List.of("content-type", "content-transfer-encoding", "mime-version");

    private final Clock clock;
    private final Duration maxDeliveryAge;
    private final Duration maxSignatureAge;
    private final boolean requireSignedContentHeaders;

    @Inject
    public SenderAuthenticator(DkimVerifier dkim, DmarcAlignment dmarc, MailDnsResolver dns, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.email.dkim.max-delivery-age", defaultValue = "7d") Duration maxDeliveryAge,
            @ConfigProperty(name = "eddi.email.dkim.max-signature-age", defaultValue = "7d") Duration maxSignatureAge,
            @ConfigProperty(name = "eddi.email.dkim.require-signed-content-headers",
                            defaultValue = "true") boolean requireSignedContentHeaders) {
        this(dkim, dmarc, dns, meterRegistry, maxDeliveryAge, maxSignatureAge, requireSignedContentHeaders, Clock.systemUTC());
    }

    /** Defaults: 7-day maximum delivery and signature ages, the system clock. */
    public SenderAuthenticator(DkimVerifier dkim, DmarcAlignment dmarc, MailDnsResolver dns, MeterRegistry meterRegistry) {
        this(dkim, dmarc, dns, meterRegistry, DEFAULT_MAX_DELIVERY_AGE, DEFAULT_MAX_SIGNATURE_AGE, true, Clock.systemUTC());
    }

    /** For tests: any maximum delivery age, any clock; the other defaults. */
    public SenderAuthenticator(DkimVerifier dkim, DmarcAlignment dmarc, MailDnsResolver dns, MeterRegistry meterRegistry,
            Duration maxDeliveryAge, Clock clock) {
        this(dkim, dmarc, dns, meterRegistry, maxDeliveryAge, DEFAULT_MAX_SIGNATURE_AGE, true, clock);
    }

    /**
     * Everything configurable.
     *
     * @param maxSignatureAge
     *            how old a signature may be when the mail is delivered, judged on
     *            {@code t=} or else the signed {@code Date}; zero disables the
     *            bound
     * @param requireSignedContentHeaders
     *            whether an aligned pass needs every {@code Content-Type},
     *            {@code Content-Transfer-Encoding} and {@code MIME-Version}
     *            instance signed (see {@link #contentHeadersRefusal})
     */
    public SenderAuthenticator(DkimVerifier dkim, DmarcAlignment dmarc, MailDnsResolver dns, MeterRegistry meterRegistry,
            Duration maxDeliveryAge, Duration maxSignatureAge, boolean requireSignedContentHeaders, Clock clock) {
        this.dkim = Objects.requireNonNull(dkim, "dkim");
        this.dmarc = Objects.requireNonNull(dmarc, "dmarc");
        this.dns = Objects.requireNonNull(dns, "dns");
        this.meterRegistry = meterRegistry;
        this.maxDeliveryAge = maxDeliveryAge == null || maxDeliveryAge.isNegative() ? DEFAULT_MAX_DELIVERY_AGE : maxDeliveryAge;
        this.maxSignatureAge = maxSignatureAge == null || maxSignatureAge.isNegative() ? DEFAULT_MAX_SIGNATURE_AGE : maxSignatureAge;
        this.requireSignedContentHeaders = requireSignedContentHeaders;
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
        // Alignment first, without DNS: a signature whose d= could never align with
        // From cannot authenticate anyone, so its key is not looked up (an attacker
        // must not make EDDI query their DNS, or defer a mail for a day with a key
        // server that times out). Relaxed is the widest mode; the DMARC record can
        // only narrow it.
        String alignableFrom = parsed.from() == null ? null : parsed.from().domain();
        DkimVerifier.Verification verification = dkim.verify(raw, budget, evaluatedAt,
                domain -> alignableFrom != null && dmarc.aligned(domain, alignableFrom, DmarcAlignment.Mode.RELAXED));
        if (verification.messageFailure() != null) {
            return fail(verification.messageFailure(), false, List.of());
        }
        List<DkimResult> results = verification.results();
        if (verification.unsigned()) {
            return new MailAuthentication(Verdict.NONE, null, "the message has no DKIM signature", false, results);
        }
        List<DkimResult> checked = results.stream().filter(r -> r.outcome() != Outcome.SKIPPED).toList();
        List<DkimResult> passing = checked.stream().filter(DkimResult::passed).toList();
        boolean anyTemporary = checked.stream().anyMatch(r -> r.outcome() == Outcome.TEMPERROR);
        if (passing.isEmpty()) {
            if (checked.isEmpty()) {
                String ambiguity = ambiguity(parsed, verification.fromFieldCount());
                return fail(ambiguity != null ? ambiguity : "no signature is by a domain that can align with From (not checked)", false,
                        results);
            }
            if (checked.stream().allMatch(r -> r.outcome() == Outcome.TESTING)) {
                return new MailAuthentication(Verdict.NONE, null, "only signatures under a key in testing mode (t=y)", false, results);
            }
            if (fallback != null && checked.stream().allMatch(r -> r.outcome() == Outcome.TEMPERROR)) {
                return fromProvider(fallback, parsed, verification.fromFieldCount(), budget, results);
            }
            DkimResult first = checked.stream().filter(r -> r.outcome() == Outcome.FAIL).findFirst()
                    .orElseGet(() -> checked.stream().filter(r -> r.outcome() == Outcome.TEMPERROR).findFirst().orElse(checked.get(0)));
            return fail(first.reason(), anyTemporary, results);
        }
        String ambiguity = ambiguity(parsed, verification.fromFieldCount());
        if (ambiguity != null) {
            return fail(ambiguity, false, results);
        }
        String fromDomain = parsed.from().domain();
        DmarcAlignment.Policy policy = dmarc.policy(fromDomain, budget);
        String refusal = null;
        for (DkimResult pass : passing) {
            if (dmarc.aligned(pass.domain(), fromDomain, policy.mode())) {
                String why = policyRefusal(pass, verification, parsed, evaluatedAt);
                if (why == null) {
                    return new MailAuthentication(Verdict.ALIGNED_PASS, pass.domain(),
                            "a signature of the From domain verifies (" + mode(policy) + " alignment)", false, results,
                            signedHeaders(pass, verification, parsed));
                }
                if (refusal == null) {
                    refusal = why;
                }
            }
        }
        if (refusal != null) {
            return fail(refusal, false, results);
        }
        return new MailAuthentication(Verdict.PASS_UNALIGNED, passing.get(0).domain(),
                policy.temporaryFailure()
                        ? "a signature verifies, but the From domain's DMARC policy could not be read, so strict "
                                + "alignment was assumed and it does not align"
                        : "a signature verifies, but for a domain that does not align with From (" + mode(policy) + ")",
                policy.temporaryFailure() || anyTemporary, results);
    }

    /**
     * Why an aligned, verifying signature still does not authenticate this message,
     * or null when it does. A fixed sentence, never message text.
     */
    String policyRefusal(DkimResult pass, DkimVerifier.Verification verification, InboundMail parsed, Instant evaluatedAt) {
        if (requireSignedContentHeaders) {
            String content = contentHeadersRefusal(pass, verification);
            if (content != null) {
                return content;
            }
        }
        if (!maxSignatureAge.isZero()) {
            // t= when the signer set it, else the Date — but only a signed Date: an
            // unsigned one is the replayer's to choose.
            Instant signedAt = pass.signedAt() != null
                    ? Instant.ofEpochSecond(pass.signedAt())
                    : covers(pass, verification, "date") ? signedDate(verification) : null;
            if (signedAt == null) {
                return "the signature's age cannot be judged: it has no t= and does not sign the Date";
            }
            if (signedAt.plus(maxSignatureAge).isBefore(evaluatedAt)) {
                return "the signature is older than eddi.email.dkim.max-signature-age (a replayed mail?)";
            }
        }
        return null;
    }

    /**
     * Why the body cannot be read as the signer sent it, or null. Every instance of
     * {@link #CONTENT_HEADERS} must be signed — that is, a header may not occur
     * more often than {@code h=} lists it — which refuses a header added, prepended
     * or replaced by whoever holds the signed mail.
     * <p>
     * A signer that does not list a header at all is judged on what the header
     * could do: a single unsigned {@code Content-Type} is accepted only when it is
     * not {@code multipart/*} or {@code message/*} (a single-part body is the
     * signed bytes whatever the type says; a multipart's boundary would decide
     * which of them EDDI reads), a single unsigned
     * {@code Content-Transfer-Encoding} only when it is an identity encoding
     * ({@code 7bit}, {@code 8bit}, {@code binary}), a single {@code MIME-Version}
     * always.
     */
    static String contentHeadersRefusal(DkimResult pass, DkimVerifier.Verification verification) {
        for (String name : CONTENT_HEADERS) {
            int present = verification.headerCount(name);
            int signed = pass.timesSigned(name);
            if (present <= signed) {
                continue;
            }
            if (signed > 0 || present > 1) {
                return "an unsigned " + name + " header was added to the signed message";
            }
            String value = verification.headerValues(name).get(0).strip().toLowerCase(Locale.ROOT);
            if ("content-type".equals(name) && isContainerType(value)) {
                return "the signature does not cover content-type, and the message is multipart";
            }
            if ("content-transfer-encoding".equals(name) && !(value.equals("7bit") || value.equals("8bit") || value.equals("binary"))) {
                return "the signature does not cover content-transfer-encoding, and the body is encoded";
            }
        }
        return null;
    }

    private static boolean isContainerType(String contentType) {
        String base;
        try {
            base = new ContentType(contentType).getBaseType().toLowerCase(Locale.ROOT);
        } catch (ParseException e) {
            // The parser reads an unparseable type as text/plain, and so does this.
            return false;
        }
        return base.startsWith("multipart/") || base.startsWith("message/");
    }

    /**
     * The one {@code Date} of the header block, read from the bytes the signature
     * hashed, or null when there is not exactly one or it cannot be parsed.
     */
    private static Instant signedDate(DkimVerifier.Verification verification) {
        List<String> values = verification.headerValues("date");
        if (values.size() != 1) {
            return null;
        }
        try {
            Date date = new MailDateFormat().parse(values.get(0).strip(), new ParsePosition(0));
            return date == null ? null : date.toInstant();
        } catch (RuntimeException e) {
            // MailDateFormat is lenient and answers null rather than throwing for most
            // garbage; anything else is no date.
            return null;
        }
    }

    /**
     * Whether every instance of {@code name} in the message is signed by
     * {@code pass}.
     */
    static boolean covers(DkimResult pass, DkimVerifier.Verification verification, String name) {
        return verification.headerCount(name) <= pass.timesSigned(name);
    }

    /** What {@code pass} vouches for, for {@link InboundMail#signedView()}. */
    static SignedHeaders signedHeaders(DkimResult pass, DkimVerifier.Verification verification, InboundMail parsed) {
        Set<String> covered = new HashSet<>();
        for (String name : DkimVerifier.REPORTED_HEADERS) {
            if (covers(pass, verification, name)) {
                covered.add(name);
            }
        }
        List<MailAddress> to = covered.contains("to") ? parsed.to() : signedAddresses(pass, verification, "to");
        List<MailAddress> cc = covered.contains("cc") ? parsed.cc() : signedAddresses(pass, verification, "cc");
        return new SignedHeaders(true, covered, to, cc, pass.signatureDigest());
    }

    /**
     * The mailboxes of the signed instances of an address header: the bottom
     * {@code h=}-count of them (RFC 6376 §5.4.2). Unsigned instances above them — a
     * {@code To: alias+admin@…} prepended to someone's genuine mail — are ignored.
     */
    private static List<MailAddress> signedAddresses(DkimResult pass, DkimVerifier.Verification verification, String name) {
        List<String> values = verification.headerValues(name);
        int signed = Math.min(pass.timesSigned(name), values.size());
        List<MailAddress> addresses = new ArrayList<>();
        for (String value : values.subList(values.size() - signed, values.size())) {
            // The verifier keeps the raw bytes as ISO-8859-1; the parser reads headers
            // as UTF-8.
            String text = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            addresses.addAll(AddressNormalizer.parseList(text).addresses());
        }
        return addresses;
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
            // The provider does not say which headers its signature covered: none is
            // trusted beyond the From.
            return new MailAuthentication(Verdict.ALIGNED_PASS, domain,
                    "the local check could not run; " + provider + " reports a passing signature of the From domain", false, local,
                    new SignedHeaders(true, Set.of(), List.of(), List.of(), null));
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
