/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.connections.settings.ConnectionSettingsRules;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
                                                                        + "until the hour has passed — possibly a mail loop"), DIGEST(
                                                                                "digest", "the daily mail digest (ownerDigest: daily)");

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
            return this == REPLY_FAILED || this == THREAD_THROTTLED;
        }
    }

    private final SystemMailer mailer;
    private final IEmailMailboxStateStore stateStore;
    private final IEmailMessageLedger ledger;
    private final MeterRegistry meterRegistry;
    private final NotifyRecipientPolicy recipientPolicy;
    private final Duration retention;

    /**
     * For the digest's absolute mail-log link. Field-injected so the constructor
     * stays the one the tests use.
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
        LOGGER.warnf(reason.isReplyProblem() ? "Email integration %s: %s." : "Email integration %s stopped taking mail: %s.", sanitize(integrationId),
                reason.text);
        if (mailer == null || !mailer.isEnabled() || settings == null || settings.ownerNotifyAddresses().isEmpty()) {
            return true;
        }
        MailAddress from = mailer.from().orElse(null);
        if (from == null) {
            return true;
        }
        for (String recipient : allowedOwners(settings, true)) {
            send(integrationId, integrationName, reason, day, from, recipient, now);
        }
        return true;
    }

    /**
     * Sends the integration's daily digest — how many mails of the previous UTC day
     * ended in which outcome — to its {@code ownerNotifyAddresses}, once per day
     * cluster-wide, from 00:05 UTC on. Counts only: no address, subject or body.
     * Nothing is sent for a day without mail, nor when the digest is off, no owner
     * address is set or the system mailer is not configured.
     *
     * @return whether this call sent (or tried to send) the digest
     */
    public boolean sendDigestIfDue(String integrationId, String integrationName, EmailChannelSettings settings, Instant now) {
        if (settings == null || !settings.dailyOwnerDigest() || settings.ownerNotifyAddresses().isEmpty() || mailer == null
                || !mailer.isEnabled()) {
            return false;
        }
        MailAddress from = mailer.from().orElse(null);
        if (from == null) {
            return false;
        }
        var utc = now.atZone(ZoneOffset.UTC);
        if (utc.toLocalTime().isBefore(DIGEST_NOT_BEFORE)) {
            return false;
        }
        // Before the claim: an owner list the domain policy empties sends nothing,
        // and must not use up the day or count as sent. Not counted here: this runs
        // on every sweep, the refusal metric once per sent digest below.
        if (allowedOwners(settings, false).isEmpty()) {
            return false;
        }
        String day = utc.toLocalDate().toString();
        // Claimed before counting, so the count runs once per day and node-wide
        // only on the winner: at most once — a count that fails after the claim
        // loses that day's digest rather than risking two.
        try {
            if (!stateStore.claimNotice(integrationId, day + ":" + Reason.DIGEST.id(), now)) {
                return false;
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Email integration %s: could not record the digest: %s", sanitize(integrationId), e.getMessage());
            return false;
        }
        Instant todayStart = utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yesterdayStart = todayStart.minus(Duration.ofDays(1));
        Map<String, Long> counts;
        try {
            counts = ledger.countInboundByOutcome(integrationId, yesterdayStart, todayStart);
        } catch (RuntimeException e) {
            LOGGER.warnf("Email integration %s: the daily digest could not be counted, none is sent today: %s", sanitize(integrationId),
                    e.getMessage());
            return false;
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        // Loop refusals alone are no reason to mail: a digest (or any automated
        // mail) that reached a polled mailbox is refused as a loop, and counting it
        // would make tomorrow's digest send itself again, every day.
        long loops = counts.entrySet().stream().filter(e -> e.getKey().startsWith(LOOP_OUTCOME)).mapToLong(Map.Entry::getValue).sum();
        if (total - loops == 0) {
            return false;
        }
        String subject = "[EDDI] Daily mail digest: " + flatten(integrationName == null ? integrationId : integrationName);
        String text = digestText(integrationId, integrationName, yesterdayStart, counts, total, mailLogUrl(integrationId));
        for (String recipient : allowedOwners(settings, true)) {
            send(integrationId, Reason.DIGEST, day, from, recipient, subject, text, now);
        }
        return true;
    }

    /** The digest goes out from this time of the UTC day on. */
    static final LocalTime DIGEST_NOT_BEFORE = LocalTime.of(0, 5);

    /** The outcome key prefix of a refusal as a mail loop. */
    static final String LOOP_OUTCOME = "refused:loop";

    /** Where the Manager shows an integration's mail log. */
    static String mailLogPath(String integrationId) {
        return "/manage/channels/" + integrationId + "#mail-log";
    }

    /**
     * The mail log's absolute URL from {@code eddi.connections.public-base-url} (as
     * the HITL approval mails build theirs), or null when none usable is set.
     */
    String mailLogUrl(String integrationId) {
        if (connectionsConfig == null || !connectionsConfig.isResolvable()) {
            return null;
        }
        // One read, validated as read: a second read for the check could see a value
        // an admin changed in between.
        String base = connectionsConfig.get().getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        try {
            ConnectionSettingsRules.requireBarePublicOrigin(base, ConnectionsConfig.PUBLIC_BASE_URL, ConnectionSettingsRules.isDevOrTest());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + mailLogPath(integrationId);
    }

    /**
     * @param link
     *            the mail log's absolute URL, or null to name the page instead
     */
    static String digestText(String integrationId, String integrationName, Instant dayStart, Map<String, Long> counts, long total, String link) {
        String name = flatten(integrationName == null ? integrationId : integrationName);
        StringBuilder text = new StringBuilder("Mail recorded by the email integration '").append(name).append("' (").append(integrationId)
                .append(") on ").append(dayStart.atZone(ZoneOffset.UTC).toLocalDate())
                .append(" (UTC; the day EDDI fetched it, not when it was delivered), by outcome:\n\n");
        new TreeMap<>(counts).forEach((outcome, n) -> text.append("  ").append(outcome).append(": ").append(n).append('\n'));
        text.append("\n  total: ").append(total).append("\n\nWhy each mail was or was not acted on: ");
        if (link != null) {
            text.append(link).append(" (sign-in required).");
        } else {
            text.append("the Mail log of the integration in the EDDI Manager (").append(mailLogPath(integrationId)).append(", sign-in required).");
        }
        text.append(" No addresses, subjects or bodies are included here.\n");
        return text.toString();
    }

    /**
     * The owner addresses inside the domain policy, normalised and each once. The
     * integration's own polled address is left out: it would only receive the mail
     * back as a loop.
     */
    private List<String> allowedOwners(EmailChannelSettings settings, boolean countRefusals) {
        String own = settings.address() == null ? null : AddressNormalizer.tryNormalize(settings.address()).orElse(null);
        Set<String> allowed = new LinkedHashSet<>();
        for (String address : settings.ownerNotifyAddresses()) {
            Optional<String> recipient = AddressNormalizer.tryNormalize(address);
            if (recipient.isEmpty() || recipient.get().equals(own)) {
                continue;
            }
            if (!recipientPolicy.allows(recipient.get())) {
                if (countRefusals) {
                    count("not_allowed");
                }
                continue;
            }
            allowed.add(recipient.get());
        }
        return List.copyOf(allowed);
    }

    private void send(String integrationId, String integrationName, Reason reason, String day, MailAddress from, String recipient, Instant now) {
        String name = flatten(integrationName == null ? integrationId : integrationName);
        String subject;
        String text;
        if (reason.isReplyProblem()) {
            subject = "[EDDI] Email integration replies held back: " + name;
            text = "The email integration '" + name + "' (" + integrationId + ") did not send a reply: " + reason.text
                    + ".\n\nMail keeps being processed. No content of the conversation is included here.\n";
        } else {
            subject = "[EDDI] Email integration paused: " + name;
            text = "The email integration '" + name + "' (" + integrationId + ") stopped taking new mail: " + reason.text
                    + ".\n\nMail that arrives meanwhile stays in the mailbox and is processed later. Nothing was sent to any sender.\n";
        }
        send(integrationId, reason, day, from, recipient, subject, text, now);
    }

    private void send(String integrationId, Reason reason, String day, MailAddress from, String recipient, String subject, String text,
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

    private void count(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.email.outbound", "kind", "notice", "outcome", outcome).increment();
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
