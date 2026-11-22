/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailHeldMailView;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailLimits;
import ai.labs.eddi.engine.mail.MailParseException;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.integrations.email.ledger.EmailReviewState;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.integrations.email.transport.MailboxException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Held mail: the review queue, the live preview and the decisions (email
 * channel v3 plan §7.2, §7.3). Who may decide is the REST resource's concern
 * ({@code RestChannelIntegrationStore}); this class only ever moves a row that
 * is still {@link State#HELD} and belongs to the integration named, with a
 * conditional transition, so two reviewers — or a reviewer and the expiry sweep
 * — settle a mail once.
 * <p>
 * <b>No body is stored.</b> The preview re-reads the mail from the mailbox by
 * its Message-ID (polled, move and Held folders) and shows it only when it
 * hashes as the mail that was held: a Message-ID is not a secret.
 */
@ApplicationScoped
public class EmailHeldMailReview {

    private static final Logger LOGGER = Logger.getLogger(EmailHeldMailReview.class);

    /** The most of the text a preview shows. */
    public static final int PREVIEW_CHARS = 4000;
    /** The most attachment names a preview shows. */
    static final int PREVIEW_MAX_ATTACHMENTS = 20;
    /** The most of a reviewer's note that is kept. */
    public static final int MAX_NOTE = 500;
    /** Held mail one queue read returns at most. */
    public static final int MAX_QUEUE = 200;

    private final IEmailMessageLedger ledger;
    private final MailTransport transport;
    private final MimeMessageParser parser;
    private final EmailSenderListAudit audit;

    @Inject
    public EmailHeldMailReview(IEmailMessageLedger ledger, ImapMailTransport transport, MimeMessageParser parser, EmailSenderListAudit audit) {
        this(ledger, (MailTransport) transport, parser, audit);
    }

    /** For tests: any transport. */
    public EmailHeldMailReview(IEmailMessageLedger ledger, MailTransport transport, MimeMessageParser parser, EmailSenderListAudit audit) {
        this.ledger = ledger;
        this.transport = transport;
        this.parser = parser;
        this.audit = audit;
    }

    /** The held mail of one integration, oldest first. */
    public EmailHeldMailView.Queue queue(String integrationId, ChannelIntegrationConfiguration configuration, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_QUEUE));
        List<LedgerEntry> rows = ledger.findHeld(integrationId, bounded + 1);
        List<EmailHeldMailView.Item> items = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), bounded); i++) {
            items.add(item(rows.get(i)));
        }
        return new EmailHeldMailView.Queue(integrationId, configuration == null ? null : configuration.getName(), items, rows.size() > bounded);
    }

    /** How the queue shows a row. */
    public static EmailHeldMailView.Item item(LedgerEntry row) {
        boolean aligned = EmailReviewState.alignedPass(row.authVerdict());
        return new EmailHeldMailView.Item(row.id(), row.integrationId(), row.recipient(), row.authVerdict(), EmailReviewState.reasons(row.review()),
                row.createdAt(), row.nextAttemptAt(), aligned, !aligned);
    }

    /**
     * The row {@code rowId} when it is a mail of {@code integrationId} still
     * waiting for review; empty otherwise (another integration's row is reported
     * like an unknown one).
     */
    public Optional<LedgerEntry> heldRow(String integrationId, String rowId) {
        if (rowId == null || rowId.isBlank()) {
            return Optional.empty();
        }
        return ledger.findById(rowId.trim()).filter(row -> integrationId.equals(row.integrationId()) && row.kind() == Kind.TRIGGER
                && row.direction() == Direction.IN && row.state() == State.HELD);
    }

    /** Why a preview could not be shown. */
    public static final class PreviewUnavailable extends Exception {
        public PreviewUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Re-reads the held mail live. Never cached, never stored.
     *
     * @throws PreviewUnavailable
     *             the mailbox could not be read, the mail is gone, or what carries
     *             its Message-ID is not the mail that was held
     */
    public EmailHeldMailView.Preview preview(String integrationId, ChannelIntegrationConfiguration configuration, LedgerEntry row)
            throws PreviewUnavailable {
        EmailChannelSettings settings = EmailChannelSettings.from(configuration);
        byte[] raw;
        try (MailTransport.MailboxSession session = transport.open(integrationId, settings)) {
            Optional<MailTransport.FoundMessage> found = session.findByMessageId(row.messageId(), settings, settings.maxMessageBytes(),
                    row.sourceRef(),
                    row.contentHash());
            if (found.isEmpty() || row.contentHash() == null || !row.contentHash().equalsIgnoreCase(found.get().sha256())) {
                throw new PreviewUnavailable("The held mail is no longer in the mailbox (or what carries its Message-ID is another mail).", null);
            }
            raw = found.get().raw();
        } catch (MailboxException e) {
            LOGGER.infof("Email integration %s: the preview of held mail %s could not be read (%s)", sanitize(integrationId), row.id(),
                    e.getCategory());
            throw new PreviewUnavailable("The mailbox could not be read: " + e.getCategory() + ".", e);
        }
        InboundMail mail;
        try {
            // Quoted history stripped and hidden content removed, as for a turn.
            mail = parser.parse(raw, MailLimits.defaults().withMaxRawBytes(settings.maxMessageBytes()), true);
        } catch (MailParseException e) {
            throw new PreviewUnavailable("The held mail cannot be read: " + e.getReason() + ".", e);
        }
        String text = mail.text() == null ? "" : mail.text();
        boolean cut = text.length() > PREVIEW_CHARS;
        if (cut) {
            int end = Character.isHighSurrogate(text.charAt(PREVIEW_CHARS - 1)) ? PREVIEW_CHARS - 1 : PREVIEW_CHARS;
            text = text.substring(0, end);
        }
        List<String> attachments = new ArrayList<>();
        for (var attachment : mail.attachments()) {
            if (attachments.size() >= PREVIEW_MAX_ATTACHMENTS) {
                break;
            }
            attachments.add(attachment.filename() == null ? "(unnamed)" : attachment.filename());
        }
        return new EmailHeldMailView.Preview(item(row), mail.fromName(), mail.subject(), text, cut, attachments);
    }

    /**
     * Releases a held mail for its next poll ({@code runOnce} /
     * {@code runAndAllow}): {@code HELD → RECEIVED}, due now, remembering which
     * checks held it so the run skips exactly those. Audited.
     *
     * @return whether this call released it (false: decided or expired already)
     */
    public boolean release(LedgerEntry row, String decision, String note, Instant now) {
        List<String> reasons = EmailReviewState.reasons(row.review());
        if (!ledger.transitionReview(row.id(), State.HELD, State.RECEIVED, EmailReviewState.released(decision, reasons), null, null, now,
                "review_released", now)) {
            return false;
        }
        audit.heldMailDecided(row.integrationId(), row.id(), decision, reasons, row.authVerdict(), row.recipient(), cleanNote(note));
        LOGGER.infof("Email integration %s: held mail %s released (%s); it runs at the next poll", sanitize(row.integrationId()), row.id(), decision);
        return true;
    }

    /**
     * Discards a held mail: {@code HELD → REFUSED}, reason
     * {@code review_discarded}. Audited.
     *
     * @return whether this call discarded it
     */
    public boolean discard(LedgerEntry row, String note, Instant now) {
        List<String> reasons = EmailReviewState.reasons(row.review());
        if (!ledger.transitionReview(row.id(), State.HELD, State.REFUSED, EmailReviewState.DISCARDED + EmailReviewState.join(reasons), null, null,
                null, EmailReviewState.REVIEW_DISCARDED, now)) {
            return false;
        }
        audit.heldMailDecided(row.integrationId(), row.id(), EmailReviewState.DISCARD, reasons, row.authVerdict(), row.recipient(), cleanNote(note));
        LOGGER.infof("Email integration %s: held mail %s discarded", sanitize(row.integrationId()), row.id());
        return true;
    }

    /** The note, trimmed, single-line and capped; null for none. */
    public static String cleanNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < note.length() && out.length() < MAX_NOTE; i++) {
            char c = note.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().trim();
    }
}
