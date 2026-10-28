/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
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
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Tells an email integration's owners, once per UTC day and reason, that it
 * stopped taking mail on a limit: the daily cost budget, the per-sender limit
 * or a tenant quota (email plan §13). The mail itself stays in the mailbox for
 * a later poll — nothing goes back to any sender.
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
                                        + "check the email ledger rows in state REFUSED with reason stranded");

        private final String id;
        private final String text;

        Reason(String id, String text) {
            this.id = id;
            this.text = text;
        }

        public String id() {
            return id;
        }
    }

    private final SystemMailer mailer;
    private final IEmailMailboxStateStore stateStore;
    private final IEmailMessageLedger ledger;
    private final MeterRegistry meterRegistry;
    private final NotifyRecipientPolicy recipientPolicy;
    private final Duration retention;

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
        LOGGER.warnf("Email integration %s stopped taking mail: %s.", sanitize(integrationId), reason.text);
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
        String subject = "[EDDI] Email integration paused: " + flatten(integrationName == null ? integrationId : integrationName);
        String text = "The email integration '" + flatten(integrationName == null ? integrationId : integrationName) + "' (" + integrationId
                + ") stopped taking new mail: " + reason.text + ".\n\nMail that arrives meanwhile stays in the mailbox and is processed later. "
                + "Nothing was sent to any sender.\n";
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
