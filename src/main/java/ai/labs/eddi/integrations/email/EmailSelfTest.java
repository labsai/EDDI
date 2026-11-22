/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailSelfTestResult;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.mail.DmarcAlignment;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MailDnsResolver;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.MessageIds;
import ai.labs.eddi.engine.mail.OutboundMail;
import ai.labs.eddi.engine.mail.PlainTextMailRenderer;
import ai.labs.eddi.engine.mail.SmimeCertificateCheck;
import ai.labs.eddi.engine.mail.SmimeSigner;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.IEmailSuppressionStore;
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The sending-health self-test of an email integration (email channel v3 plan
 * §6.5): one short mail from the integration to its own address, through its
 * own SMTP relay, signed as {@code emailControl.smime} says, carrying a
 * single-use token. When the mailbox poll picks it up, the processor hands it
 * here <em>before</em> the own-address refusal: the result — the DKIM verdict
 * and signing domain, alignment, whether the S/MIME signature verified, whether
 * the domain publishes DMARC — is recorded, and the mail is settled as
 * {@code self_test}. It never starts a turn.
 * <p>
 * <b>The token.</b> 32 random bytes, base64url, returned once to the caller who
 * started the test and carried in the mail's {@value #TOKEN_HEADER} header —
 * only there: a token in the body would let any mail that quotes or forwards
 * the test mail count as its result. Only its SHA-256 is stored. A returned
 * mail counts only when it carries the token in that header <em>and</em> its
 * {@code Message-ID} is the one the test mail was sent with, so a forged mail
 * cannot write a result without both. The token is valid for {@link #VALIDITY}
 * and for one use: the ledger transition from {@code SENT} to {@code DONE} is
 * atomic, so a second copy of the mail — a replay, a forward back to the
 * mailbox — finds the row already done and is refused like any other mail from
 * the integration's own address.
 * <p>
 * <b>The limit.</b> At most {@value #MAX_PER_HOUR} tests per integration per
 * clock hour (UTC), cluster-wide and atomic: a test starts only by claiming one
 * of the hour's {@value #MAX_PER_HOUR} slot rows in the ledger, whose
 * idempotency key ({@code self-test-slot:<integrationId>:<hour>:<n>}) is unique
 * on both datastores, so two nodes can never both take the last one. A fixed
 * window, not a sliding one: up to twice the limit can start across the turn of
 * an hour.
 */
@ApplicationScoped
public class EmailSelfTest {

    private static final Logger LOGGER = Logger.getLogger(EmailSelfTest.class);

    /** The header that carries the token. */
    public static final String TOKEN_HEADER = "X-EDDI-Self-Test";
    /** How long a test stays open. */
    public static final Duration VALIDITY = Duration.ofMinutes(15);
    /** Tests per integration per hour. */
    public static final int MAX_PER_HOUR = 5;
    /** The disposition reason of a recognised self-test mail. */
    public static final String SETTLED_REASON = "self_test";

    static final String SUBJECT = "EDDI sending-health self-test";
    static final String RESULT_PREFIX = "self_test:v1;";
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Session SESSION = Session.getInstance(new Properties());
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IEmailMessageLedger ledger;
    private final MailTransport transport;
    private final DmarcAlignment dmarc;
    private final MailDnsResolver dns;
    private final EmailMetrics metrics;
    private final Duration retention;
    private final Clock clock;

    @Inject
    public EmailSelfTest(IEmailMessageLedger ledger, ImapMailTransport transport, DmarcAlignment dmarc, MailDnsResolver dns,
            MeterRegistry meterRegistry, @ConfigProperty(name = "eddi.email.ledger-retention", defaultValue = "30d") Duration retention) {
        this(ledger, (MailTransport) transport, dmarc, dns, meterRegistry, retention, Clock.systemUTC());
    }

    /** For tests: any transport, a fixed clock. */
    public EmailSelfTest(IEmailMessageLedger ledger, MailTransport transport, DmarcAlignment dmarc, MailDnsResolver dns, MeterRegistry meterRegistry,
            Duration retention, Clock clock) {
        this.ledger = ledger;
        this.transport = transport;
        this.dmarc = dmarc;
        this.dns = dns;
        this.metrics = new EmailMetrics(meterRegistry);
        this.retention = retention == null || retention.isZero() || retention.isNegative() ? Duration.ofDays(30) : retention;
        this.clock = clock;
    }

    /** Why a test cannot start. */
    public static final class Refused extends RuntimeException {
        private final boolean rateLimited;

        Refused(String message, boolean rateLimited) {
            super(message);
            this.rateLimited = rateLimited;
        }

        /** Whether the hourly limit refused it (a 429 rather than a 400). */
        public boolean rateLimited() {
            return rateLimited;
        }
    }

    // --- Starting ------------------------------------------------------------

    /**
     * Sends the test mail.
     *
     * @return the open test, its token included — the only time it is shown
     * @throws Refused
     *             when the integration has no SMTP relay or address, or the hourly
     *             limit is reached
     */
    public EmailSelfTestResult start(String integrationId, EmailChannelSettings settings) {
        if (settings == null || !settings.hasSmtp() || settings.address() == null) {
            throw new Refused("The self-test sends a mail through the integration's SMTP relay: set platformConfig.smtpHost (and smtpPort) "
                    + "first. A trigger-mode integration still never replies; it uses the relay only for this test.", false);
        }
        Instant now = Instant.now(clock);
        if (!claimSlot(integrationId, now)) {
            throw new Refused("At most " + MAX_PER_HOUR + " self-tests per integration per hour. Try again later.", true);
        }
        String token = newToken();
        String hash = hash(token);
        String messageId = MessageIds.generate(IEmailSuppressionStore.domainOf(settings.address()));
        byte[] mime = render(settings, messageId, token, now);
        LedgerEntry row = new LedgerEntry(UUID.randomUUID().toString(), integrationId, Kind.SELF_TEST, Direction.OUT, messageId, null, hash,
                key(hash), settings.address(), State.PENDING, 1, now.plus(VALIDITY), UUID.randomUUID().toString(), null, null, now, now,
                now.plus(retention));
        if (!ledger.insertIfAbsent(row)) {
            throw new IllegalStateException("The self-test row could not be written.");
        }
        try {
            MailTransport.Sent sent = transport.sendSelfTest(integrationId, settings, mime);
            ledger.complete(row.id(), row.claimToken(), State.SENT, sent.providerResponse(), null, sent.signed(), now);
            metrics.selfTest("sent", sent.signed());
            LOGGER.infof("Email integration %s: self-test %s sent (signed=%s)", sanitize(integrationId), row.id(), sent.signed());
            return EmailSelfTestResult.open(token, now.plus(VALIDITY), sent.signed());
        } catch (MailSendException e) {
            String error = e.getCategory() + ": " + truncate(e.getMessage());
            ledger.complete(row.id(), row.claimToken(), State.FAILED, null, error, false, now);
            metrics.selfTest(e.getCategory() == MailSendException.Category.SIGNING ? "held" : "failed", false);
            LOGGER.warnf("Email integration %s: self-test %s could not be sent: %s", sanitize(integrationId), row.id(), e.getCategory());
            return EmailSelfTestResult.sendFailed(token, e.getCategory().name().toLowerCase(Locale.ROOT), truncate(e.getMessage()));
        }
    }

    /**
     * Claims one of the hour's {@value #MAX_PER_HOUR} slots of
     * {@code integrationId}: the first slot row whose unique key is still free.
     * Atomic per row on both datastores
     * ({@link IEmailMessageLedger#insertIfAbsent}), so the limit holds however many
     * nodes start tests at once. A slot row is terminal ({@code DONE}), names no
     * recipient and is removed an hour after its window closed.
     */
    boolean claimSlot(String integrationId, Instant now) {
        long hour = Math.floorDiv(now.getEpochSecond(), Duration.ofHours(1).toSeconds());
        Instant expires = Instant.ofEpochSecond((hour + 2) * Duration.ofHours(1).toSeconds());
        for (int slot = 0; slot < MAX_PER_HOUR; slot++) {
            String key = SLOT_PREFIX + integrationId + ":" + hour + ":" + slot;
            LedgerEntry row = new LedgerEntry(UUID.randomUUID().toString(), integrationId, Kind.SELF_TEST, Direction.OUT,
                    "<self-test-slot." + hour + "." + slot + "@eddi.invalid>", null, SLOT_SOURCE, key, null, State.DONE, 0, null, null, null,
                    null, now, now, expires);
            if (ledger.insertIfAbsent(row)) {
                return true;
            }
        }
        return false;
    }

    /** The idempotency-key prefix of a slot row. */
    static final String SLOT_PREFIX = "self-test-slot:";
    /** The {@code sourceRef} of a slot row (a test row holds a token hash). */
    static final String SLOT_SOURCE = "slot";

    /** The test mail, with the token in its header (never in its body). */
    static byte[] render(EmailChannelSettings settings, String messageId, String token, Instant now) {
        MailAddress self = new MailAddress(settings.address(), settings.fromName());
        String text = "This is a sending-health self-test of an EDDI email integration. EDDI reads it back from this mailbox to check "
                + "how the mail provider signs mail from this address (DKIM, DMARC) and whether the S/MIME signature verifies.\n\n"
                + "You can ignore or delete it. It never starts an agent conversation.\n";
        byte[] rendered = PlainTextMailRenderer.render(new OutboundMail(self, List.of(new MailAddress(settings.address(), null)), List.of(), SUBJECT,
                text, messageId, null, List.of(), OutboundMail.Kind.AUTO_GENERATED, now)).mime();
        try {
            MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(rendered)) {
                @Override
                protected void updateMessageID() throws MessagingException {
                    setHeader("Message-ID", messageId);
                }
            };
            message.setHeader(TOKEN_HEADER, token);
            message.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream(rendered.length + 128);
            message.writeTo(out);
            return out.toByteArray();
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException("The self-test mail could not be rendered: " + e.getMessage(), e);
        }
    }

    // --- Recognising ---------------------------------------------------------

    /**
     * Whether {@code mail} is the open self-test of {@code integrationId}, and if
     * so records its result and closes the test. Called by the processor after the
     * sender was authenticated and before the own-address refusal.
     * <p>
     * True only when every condition holds: {@code From} is the integration's own
     * address, the mail carries a token in its {@value #TOKEN_HEADER} header, the
     * token's hash names a {@link Kind#SELF_TEST} row of <em>this</em> integration
     * that is still open ({@code SENT}) and younger than {@link #VALIDITY}, the
     * mail's {@code Message-ID} is the row's, and this call is the one that moved
     * it to {@code DONE}. Anything else returns false, and the mail meets the
     * normal checks — where its own address refuses it.
     */
    public boolean recognize(String integrationId, EmailChannelSettings settings, InboundMail mail, byte[] raw, MailAuthentication authentication,
                             Instant now) {
        String from = mail.fromAddress() == null ? null : AddressNormalizer.tryNormalize(mail.fromAddress()).orElse(null);
        if (from == null || settings.address() == null || !from.equals(settings.address())) {
            return false;
        }
        String token = tokenOf(raw);
        if (token == null) {
            return false;
        }
        Optional<LedgerEntry> row = ledger.findByIdempotencyKey(key(hash(token)));
        if (row.isEmpty() || row.get().kind() != Kind.SELF_TEST || !integrationId.equals(row.get().integrationId())
                || row.get().state() != State.SENT || !row.get().createdAt().plus(VALIDITY).isAfter(now) || mail.syntheticMessageId()
                || !row.get().messageId().equals(mail.messageId())) {
            return false;
        }
        String result = encode(evaluate(settings, raw, authentication, now));
        // The atomic SENT -> DONE is what makes the token single-use.
        boolean closed = ledger.transition(row.get().id(), State.SENT, State.DONE, null, null, result, false, now);
        if (closed) {
            LOGGER.infof("Email integration %s: self-test %s came back (%s)", sanitize(integrationId), row.get().id(), result);
        }
        return closed;
    }

    /**
     * The token in the mail's {@value #TOKEN_HEADER} header, or null — never one
     * from the body, which any quoting or forwarding mail can carry.
     */
    static String tokenOf(byte[] raw) {
        try {
            InternetHeaders headers = new InternetHeaders(new ByteArrayInputStream(raw));
            String[] values = headers.getHeader(TOKEN_HEADER);
            if (values != null && values.length > 0 && values[0] != null && TOKEN.matcher(values[0].trim()).matches()) {
                return values[0].trim();
            }
        } catch (MessagingException e) {
            // Unreadable headers carry no token.
        }
        return null;
    }

    /** What the returned mail shows. */
    EmailSelfTestResult.Checks evaluate(EmailChannelSettings settings, byte[] raw, MailAuthentication authentication, Instant now) {
        String domain = IEmailSuppressionStore.domainOf(settings.address());
        String signingDomain = authentication == null ? null : authentication.signingDomain();
        if (signingDomain == null && authentication != null) {
            for (var signature : authentication.signatures()) {
                if (signature.passed()) {
                    signingDomain = signature.domain();
                    break;
                }
            }
        }
        String dkim = authentication == null ? "not-evaluated" : EmailInboundProcessor.verdictId(authentication);
        boolean temporary = authentication != null && authentication.temporary();
        SmimeSigner.VerificationResult smime = SmimeSigner.verify(raw);
        String smimeResult = switch (smime.verification()) {
            case NOT_SIGNED -> "not_signed";
            case INVALID -> "invalid";
            case VERIFIED -> smime.signerAddresses().stream().map(a -> AddressNormalizer.tryNormalize(a).orElse(a))
                    .anyMatch(a -> a.equalsIgnoreCase(settings.address())) ? trust(smime, now) : "wrong_signer";
        };
        String dmarcResult;
        String dmarcDomain = null;
        try {
            DmarcAlignment.Policy policy = dmarc.policy(domain, dns.newBudget());
            dmarcResult = policy.temporaryFailure() ? "unknown" : policy.recordDomain() == null ? "absent" : "present";
            dmarcDomain = policy.recordDomain();
        } catch (RuntimeException e) {
            dmarcResult = "unknown";
        }
        return new EmailSelfTestResult.Checks(dkim, signingDomain, authentication != null && authentication.isAuthenticated(), temporary, smimeResult,
                settings.wantsSignature(), dmarcResult, dmarcDomain, isMicrosoft365(settings), domain);
    }

    /**
     * A verified signature, by how far the chain it carries builds:
     * {@code verified} (to a root in the JDK trust store),
     * {@code verified_untrusted_root} (complete, or to a root that is not shipped
     * and not publicly trusted — recipients decide whether they trust it), or
     * {@code chain_broken}.
     */
    static String trust(SmimeSigner.VerificationResult smime, Instant now) {
        if (smime.certificates().isEmpty()) {
            return "chain_broken";
        }
        SmimeCertificateCheck.Chain chain = SmimeCertificateCheck.chainOf(smime.certificates(), now);
        return switch (chain) {
            case TRUSTED -> "verified";
            case COMPLETE, UNTRUSTED_ROOT -> "verified_untrusted_root";
            case BROKEN -> "chain_broken";
        };
    }

    /**
     * Microsoft 365 relays and mailboxes, which do not DKIM-sign intra-org mail.
     */
    static boolean isMicrosoft365(EmailChannelSettings settings) {
        for (String host : new String[]{settings.smtpHost(), settings.host()}) {
            if (host != null && (host.endsWith(".office365.com") || host.endsWith(".outlook.com") || host.equals("outlook.office365.com"))) {
                return true;
            }
        }
        return false;
    }

    // --- Reading -------------------------------------------------------------

    /**
     * The state of the test {@code token} names, if it belongs to
     * {@code integrationId}.
     */
    public Optional<EmailSelfTestResult> read(String integrationId, String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Optional<LedgerEntry> found = ledger.findByIdempotencyKey(key(hash(token)));
        if (found.isEmpty() || found.get().kind() != Kind.SELF_TEST || !integrationId.equals(found.get().integrationId())) {
            return Optional.empty();
        }
        LedgerEntry row = found.get();
        Instant expiresAt = row.createdAt().plus(VALIDITY);
        Instant now = Instant.now(clock);
        return Optional.of(switch (row.state()) {
            case DONE -> EmailSelfTestResult.done(expiresAt, Boolean.TRUE.equals(row.signed()), decode(row.lastError()));
            case FAILED -> EmailSelfTestResult.sendFailed(null, "failed", row.lastError());
            case SENT, PENDING -> expiresAt.isAfter(now)
                    ? EmailSelfTestResult.waiting(expiresAt, Boolean.TRUE.equals(row.signed()))
                    : EmailSelfTestResult.expired(expiresAt, Boolean.TRUE.equals(row.signed()));
            default -> EmailSelfTestResult.expired(expiresAt, Boolean.TRUE.equals(row.signed()));
        });
    }

    // --- Encoding ------------------------------------------------------------

    /** The result as {@code self_test:v1;key=value;…}, at most 512 characters. */
    static String encode(EmailSelfTestResult.Checks checks) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("dkim", checks.dkim());
        values.put("d", checks.signingDomain());
        values.put("aligned", Boolean.toString(checks.aligned()));
        values.put("temporary", Boolean.toString(checks.dnsTemporary()));
        values.put("smime", checks.smime());
        values.put("smimeExpected", Boolean.toString(checks.smimeExpected()));
        values.put("dmarc", checks.dmarc());
        values.put("dmarcDomain", checks.dmarcDomain());
        values.put("m365", Boolean.toString(checks.microsoft365()));
        values.put("domain", checks.domain());
        List<String> parts = new ArrayList<>();
        values.forEach((k, v) -> {
            if (v != null) {
                parts.add(k + "=" + v.replace(";", "").replace("=", ""));
            }
        });
        String encoded = RESULT_PREFIX + String.join(";", parts);
        return encoded.length() > IEmailMessageLedger.MAX_LAST_ERROR ? encoded.substring(0, IEmailMessageLedger.MAX_LAST_ERROR) : encoded;
    }

    static EmailSelfTestResult.Checks decode(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded != null && encoded.startsWith(RESULT_PREFIX)) {
            for (String part : encoded.substring(RESULT_PREFIX.length()).split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    values.put(part.substring(0, eq), part.substring(eq + 1));
                }
            }
        }
        return new EmailSelfTestResult.Checks(values.getOrDefault("dkim", "not-evaluated"), values.get("d"),
                Boolean.parseBoolean(values.get("aligned")),
                Boolean.parseBoolean(values.get("temporary")), values.getOrDefault("smime", "not_signed"),
                Boolean.parseBoolean(values.get("smimeExpected")), values.getOrDefault("dmarc", "unknown"), values.get("dmarcDomain"),
                Boolean.parseBoolean(values.get("m365")), values.get("domain"));
    }

    // --- Tokens --------------------------------------------------------------

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String key(String hash) {
        return "self-test:" + hash;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
