/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.MessageIds;
import ai.labs.eddi.engine.mail.NotifyRecipientPolicy;
import ai.labs.eddi.engine.mail.OutboundMail;
import ai.labs.eddi.engine.mail.PlainTextMailRenderer;
import ai.labs.eddi.engine.mail.SystemMailer;
import ai.labs.eddi.integrations.email.ledger.IEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Tells an email integration's owners, once per UTC day and reason, that it
 * stopped taking mail on a limit (the daily cost budget, the per-sender limit
 * or a tenant quota; email plan §13), or that a turn was lost, or a reply
 * failed or was held back (see {@link Reason}). Mail held back by a limit stays
 * in the mailbox for a later poll — nothing goes back to any sender.
 * <p>
 * <b>What "the owners" are, and how they hear it.</b> Always: one WARN log line
 * (integration id and reason only) and the {@code eddi.email.inbound} outcome
 * that already counted the mail. Additionally a short plain-text mail to the
 * integration's {@code ownerNotifyAddresses} when the operator configured the
 * system mailer ({@code eddi.mail.smtp.*}) and the address is inside the
 * deployment's notification domain policy
 * ({@code eddi.mail.notify.allowed-recipient-domains}). EDDI cannot enumerate
 * an integration's owners from the identity provider, so they are named on the
 * integration, as HITL notification recipients are on the agent.
 * <p>
 * Once per day cluster-wide through
 * {@link IEmailMailboxStateStore#claimNotice}; one attempt, never retried (the
 * next day brings the next notice if the limit is hit again). Every sent or
 * failed notice leaves a {@link Kind#NOTICE} ledger row — no body.
 */
@ApplicationScoped
public class EmailOwnerNotifier {

    private static final Logger LOGGER = Logger.getLogger(EmailOwnerNotifier.class);

    /** Why an integration stopped. */
    public enum Reason {
        BUDGET("budget", "its daily cost budget (dailyCostBudgetUsd) is used up; new mail waits until the next UTC day"), THROTTLED("throttled",
                "a sender reached maxTurnsPerSenderPerDay; that sender's further mail waits until the next UTC day"), QUOTA("quota",
                        "the tenant quota refused a new conversation or turn; mail waits for the next poll"), LOST("lost",
                                "a node stopped while it was starting the turn of a mail, so that mail may have partly run and is not run again; "
                                        + "check the email ledger rows in state REFUSED with reason stranded"), REPLY_FAILED("reply_failed",
                                                "a reply could not be delivered after its last attempt (or the relay refused it for good); check the "
                                                        + "email ledger rows of kind REPLY in state FAILED"), THREAD_THROTTLED("thread_throttled",
                                                                "a thread reached maxRepliesPerThreadPerHour, so further replies in it are not sent "
                                                                        + "until the hour has passed — possibly a mail loop"),
        /** The S/MIME signing certificate expires within 30 days (v3 phase C2). */
        CERTIFICATE_EXPIRING("certificate_expiring", "its S/MIME signing certificate (emailControl.smime.connection) expires within 30 "
                + "days; renew it and update the SMIME_SIGNING connection before it does"),
        /**
         * Mail cannot be S/MIME-signed: under sign 'required' it is held, otherwise it
         * goes out unsigned.
         */
        SMIME_UNAVAILABLE("smime_unavailable", "its mail cannot be S/MIME-signed (the certificate is missing, expired, does not match "
                + "the mailbox address, or cannot be read). With emailControl.smime.sign 'required' outgoing mail is held until it is "
                + "fixed; otherwise it goes out unsigned"),
        /**
         * A reply held for its S/MIME signature (sign 'required') reached
         * {@code eddi.email.smime.max-hold} and was given up, unsent.
         */
        SMIME_HOLD_EXPIRED("smime_hold_expired", "a reply held because it could not be S/MIME-signed (emailControl.smime.sign "
                + "'required') waited eddi.email.smime.max-hold and was given up, unsent; check the email ledger rows of kind REPLY in "
                + "state FAILED with reason smime_unavailable:expired, and fix the signing certificate"),
        /**
         * Mail would have been held for review, but today's cap
         * ({@code emailControl.review.maxHeldPerDay}) is reached (v3 phase C3).
         */
        REVIEW_FULL("review_full", "its held-mail review queue reached emailControl.review.maxHeldPerDay for today, so further mail "
                + "that would have been held is dropped until the next UTC day; decide the held mail, or raise the cap");

        private final String id;
        private final String text;

        Reason(String id, String text) {
            this.id = id;
            this.text = text;
        }

        public String id() {
            return id;
        }

        /** A reply was held back; mail intake goes on. */
        boolean isReplyProblem() {
            return this == REPLY_FAILED || this == THREAD_THROTTLED || this == SMIME_HOLD_EXPIRED;
        }

        /** About the S/MIME certificate; mail intake goes on. */
        boolean isSigningProblem() {
            return this == CERTIFICATE_EXPIRING || this == SMIME_UNAVAILABLE;
        }

        /** About held mail; mail intake goes on. */
        boolean isReviewProblem() {
            return this == REVIEW_FULL;
        }
    }

    private final SystemMailer mailer;
    private final IEmailMailboxStateStore stateStore;
    private final IEmailMessageLedger ledger;
    private final MeterRegistry meterRegistry;
    private final NotifyRecipientPolicy recipientPolicy;
    private final Duration retention;

    /**
     * For the Manager link in held-mail notices. Field-injected so the constructor
     * stays the one the tests use; absent, the notice names the page without a
     * link.
     */
    @Inject
    Instance<ConnectionsConfig> connectionsConfig;

    @Inject
    public EmailOwnerNotifier(SystemMailer mailer, IEmailMailboxStateStore stateStore, IEmailMessageLedger ledger, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.mail.notify.allowed-recipient-domains") Optional<List<String>> allowedRecipientDomains,
            @ConfigProperty(name = "eddi.email.ledger-retention", defaultValue = "30d") Duration retention) {
        this.mailer = mailer;
        this.stateStore = stateStore;
        this.ledger = ledger;
        this.meterRegistry = meterRegistry;
        NotifyRecipientPolicy policy;
        try {
            policy = NotifyRecipientPolicy.of(allowedRecipientDomains == null ? List.of() : allowedRecipientDomains.orElse(List.of()),
                    mailer == null ? null : mailer.from().map(MailAddress::address).orElse(null));
        } catch (IllegalArgumentException e) {
            // Fail closed, exactly as the HITL notifier does: nobody is mailed.
            policy = NotifyRecipientPolicy.of(List.of(), null);
        }
        this.recipientPolicy = policy;
        this.retention = retention == null || retention.isNegative() || retention.isZero() ? Duration.ofDays(30) : retention;
    }

    /**
     * Notifies the owners of {@code integrationId} that it stopped on
     * {@code reason}, unless that already happened today.
     *
     * @return whether this call was the one that notified
     */
    public boolean notifyOnce(String integrationId, String integrationName, EmailChannelSettings settings, Reason reason, Instant now) {
        String day = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
        boolean claimed;
        try {
            claimed = stateStore.claimNotice(integrationId, day + ":" + reason.id(), now);
        } catch (RuntimeException e) {
            LOGGER.warnf("Email integration %s: could not record the %s notice: %s", sanitize(integrationId), reason.id(), e.getMessage());
            return false;
        }
        if (!claimed) {
            return false;
        }
        LOGGER.warnf(
                reason.isReplyProblem() || reason.isSigningProblem() || reason.isReviewProblem()
                        ? "Email integration %s: %s."
                        : "Email integration %s stopped taking mail: %s.",
                sanitize(integrationId),
                reason.text);
        if (mailer == null || !mailer.isEnabled() || settings == null || settings.ownerNotifyAddresses().isEmpty()) {
            return true;
        }
        MailAddress from = mailer.from().orElse(null);
        if (from == null) {
            return true;
        }
        for (String address : settings.ownerNotifyAddresses()) {
            Optional<String> recipient = AddressNormalizer.tryNormalize(address);
            if (recipient.isEmpty()) {
                continue;
            }
            if (!recipientPolicy.allows(recipient.get())) {
                count("not_allowed");
                continue;
            }
            send(integrationId, integrationName, reason, day, from, recipient.get(), now);
        }
        return true;
    }

    private void send(String integrationId, String integrationName, Reason reason, String day, MailAddress from, String recipient, Instant now) {
        String messageId = MessageIds.generate(from.domain());
        String name = flatten(integrationName == null ? integrationId : integrationName);
        String subject;
        String text;
        if (reason.isReviewProblem()) {
            subject = "[EDDI] Email integration review queue full: " + name;
            text = "The email integration '" + name + "' (" + integrationId + "): " + reason.text
                    + ".\n\nNo content of any mail is included here.\n";
        } else if (reason.isSigningProblem()) {
            subject = "[EDDI] Email integration S/MIME certificate: " + name;
            text = "The email integration '" + name + "' (" + integrationId + "): " + reason.text
                    + ".\n\nMail keeps being processed. No content of any conversation is included here.\n";
        } else if (reason.isReplyProblem()) {
            subject = "[EDDI] Email integration replies held back: " + name;
            text = "The email integration '" + name + "' (" + integrationId + ") did not send a reply: " + reason.text
                    + ".\n\nMail keeps being processed. No content of the conversation is included here.\n";
        } else {
            subject = "[EDDI] Email integration paused: " + name;
            text = "The email integration '" + name + "' (" + integrationId + ") stopped taking new mail: " + reason.text
                    + ".\n\nMail that arrives meanwhile stays in the mailbox and is processed later. Nothing was sent to any sender.\n";
        }
        State state;
        String error = null;
        String provider = null;
        try {
            var rendered = PlainTextMailRenderer.render(new OutboundMail(from, List.of(new MailAddress(recipient, null)), List.of(), subject, text,
                    messageId, null, null, OutboundMail.Kind.AUTO_GENERATED, now));
            provider = mailer.send(rendered, List.of(new MailAddress(recipient, null))).serverResponse();
            state = State.SENT;
            count("sent");
        } catch (MailSendException | IllegalArgumentException | IllegalStateException e) {
            state = State.FAILED;
            error = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            if (error.length() > 300) {
                error = error.substring(0, 300) + "…";
            }
            count("failed");
            LOGGER.warnf("Email integration %s: the %s notice could not be sent: %s", sanitize(integrationId), reason.id(), error);
        }
        try {
            ledger.insertIfAbsent(new LedgerEntry(UUID.randomUUID().toString(), integrationId, Kind.NOTICE, Direction.OUT, messageId, null,
                    reason.id(), idempotencyKey(integrationId, day, reason, recipient), recipient, state, 1, null, null, provider, error, now, now,
                    now.plus(retention)));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not record the notice ledger row: %s", e.getMessage());
        }
    }

    /** Held rows a notice counts at most ("50 or more"). */
    public static final int MAX_HELD_COUNTED = 200;

    /** The Manager page where held mail is decided. */
    static final String APPROVALS_PATH = "/manage/approvals";

    /**
     * Tells the reviewers that mail is waiting for review (v3 plan §7.2): one
     * notice per poll batch that held mail, at most one per integration and UTC
     * hour cluster-wide, to {@code ownerNotifyAddresses} through the system mailer.
     * It counts, and links to the Manager approvals page; it never names a sender
     * or a subject — that is what the authenticated preview is for.
     *
     * @return whether this call was the one that notified
     */
    public boolean notifyHeld(String integrationId, String integrationName, EmailChannelSettings settings, int waiting, Instant newestHeldAt,
                              Instant now) {
        if (newestHeldAt == null || waiting <= 0) {
            return false;
        }
        String hour = heldNoticeKey(now);
        boolean claimed;
        try {
            // Nothing held since the last notice (this hour's or the last one's):
            // nothing to say. Held later than it: say so, but at most once per hour — a
            // mail held just after this hour's notice is announced by the next hour's.
            Instant last = later(stateStore.noticeClaimedAt(integrationId, hour).orElse(null),
                    stateStore.noticeClaimedAt(integrationId, heldNoticeKey(now.minus(Duration.ofHours(1)))).orElse(null));
            if (last != null && !newestHeldAt.isAfter(last)) {
                return false;
            }
            claimed = stateStore.claimNotice(integrationId, hour, now);
        } catch (RuntimeException e) {
            LOGGER.warnf("Email integration %s: could not record the held-mail notice: %s", sanitize(integrationId), e.getMessage());
            return false;
        }
        if (!claimed) {
            return false;
        }
        LOGGER.infof("Email integration %s: %d mail(s) held for review are waiting.", sanitize(integrationId), waiting);
        if (mailer == null || !mailer.isEnabled() || settings == null || settings.ownerNotifyAddresses().isEmpty()) {
            return true;
        }
        MailAddress from = mailer.from().orElse(null);
        if (from == null) {
            return true;
        }
        String name = flatten(integrationName == null ? integrationId : integrationName);
        String link = approvalsUrl();
        String subject = "[EDDI] Mail held for review: " + name;
        String text = "The email integration '" + name + "' (" + integrationId + ") held mail for review instead of dropping it: "
                + (waiting >= MAX_HELD_COUNTED ? MAX_HELD_COUNTED + " or more" : String.valueOf(waiting))
                + " are waiting for a decision.\n\nDecide them (discard, run once, or run and allow the sender) in the EDDI Manager"
                + (link == null ? ", on the Approvals page." : ": " + link) + "\nUndecided mail is dropped after " + describe(settings.reviewExpiry())
                + ". No content of any mail is included here.\n";
        for (String address : settings.ownerNotifyAddresses()) {
            Optional<String> recipient = AddressNormalizer.tryNormalize(address);
            if (recipient.isEmpty()) {
                continue;
            }
            if (!recipientPolicy.allows(recipient.get())) {
                count("not_allowed");
                continue;
            }
            deliver(integrationId, "held", hour, from, recipient.get(), subject, text, now);
        }
        return true;
    }

    static String heldNoticeKey(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.HOURS) + ":held";
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    /** "7 day(s)", or "12 hour(s)" for an expiry under a day. */
    static String describe(Duration expiry) {
        return expiry.toDays() >= 1 && expiry.toHours() % 24 == 0 ? expiry.toDays() + " day(s)" : Math.max(1, expiry.toHours()) + " hour(s)";
    }

    /**
     * The Manager approvals page, or null when no usable public base URL is set.
     */
    String approvalsUrl() {
        if (connectionsConfig == null || !connectionsConfig.isResolvable()) {
            return null;
        }
        try {
            ConnectionsConfig config = connectionsConfig.get();
            String base = config.getPublicBaseUrl();
            if (base == null || base.isBlank() || config.publicBaseUrlProblem().isPresent()) {
                return null;
            }
            return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + APPROVALS_PATH;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sends one held-mail notice and records its NOTICE row. */
    private void deliver(String integrationId, String reasonId, String window, MailAddress from, String recipient, String subject, String text,
                         Instant now) {
        String messageId = MessageIds.generate(from.domain());
        State state;
        String error = null;
        String provider = null;
        try {
            var rendered = PlainTextMailRenderer.render(new OutboundMail(from, List.of(new MailAddress(recipient, null)), List.of(), subject, text,
                    messageId, null, null, OutboundMail.Kind.AUTO_GENERATED, now));
            provider = mailer.send(rendered, List.of(new MailAddress(recipient, null))).serverResponse();
            state = State.SENT;
            count("sent");
        } catch (MailSendException | IllegalArgumentException | IllegalStateException e) {
            state = State.FAILED;
            error = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            if (error.length() > 300) {
                error = error.substring(0, 300) + "…";
            }
            count("failed");
            LOGGER.warnf("Email integration %s: the %s notice could not be sent: %s", sanitize(integrationId), reasonId, error);
        }
        try {
            ledger.insertIfAbsent(new LedgerEntry(UUID.randomUUID().toString(), integrationId, Kind.NOTICE, Direction.OUT, messageId, null, reasonId,
                    noticeKey(integrationId, window, reasonId, recipient), recipient, state, 1, null, null, provider, error, now, now,
                    now.plus(retention)));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not record the notice ledger row: %s", e.getMessage());
        }
    }

    static String noticeKey(String integrationId, String window, String reasonId, String recipient) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("NOTICE\n" + integrationId + "\n" + window + "\n" + reasonId + "\n" + recipient).getBytes(StandardCharsets.UTF_8));
            return "notice:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.email.outbound", "kind", "notice", "outcome", outcome, "signed", "false").increment();
        }
    }

    private static String flatten(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < 120; i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().trim();
    }

    static String idempotencyKey(String integrationId, String day, Reason reason, String recipient) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("NOTICE\n" + integrationId + "\n" + day + "\n" + reason.id() + "\n" + recipient).getBytes(StandardCharsets.UTF_8));
            return "notice:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
