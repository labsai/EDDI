/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One received message, parsed and bounded, with nothing trusted.
 * <p>
 * Produced by {@link MimeMessageParser}; it knows nothing about conversations
 * or channels (email plan §6). Every field is the sender's claim except
 * {@link #authentication()}, which is the verifier's, and {@link #rawSize()}.
 * The raw bytes are deliberately not kept: a DKIM verifier receives them from
 * the transport alongside this record, and nothing else needs them — so a
 * stored or logged {@code InboundMail} never carries the whole message.
 *
 * @param messageId
 *            the first Message-ID, as {@code <id@host>}; synthetic when
 *            {@link #syntheticMessageId()}
 * @param syntheticMessageId
 *            true when the message had no usable Message-ID and
 *            {@link #messageId()} is {@code sha256:<hex of the raw bytes>} — a
 *            stable dedup key that is not an RFC id and must never be written
 *            into an In-Reply-To
 * @param inReplyTo
 *            the first id in In-Reply-To, or null
 * @param references
 *            the ids in References, oldest first, capped (first id kept)
 * @param from
 *            the one From mailbox, or null when missing, invalid or ambiguous
 *            (see {@link #anomalies()})
 * @param replyTo
 *            Reply-To mailboxes — never a reply target by default (plan §9)
 * @param to
 *            To mailboxes
 * @param cc
 *            Cc mailboxes
 * @param subject
 *            decoded subject, controls removed; empty when absent
 * @param date
 *            the Date header, or null when absent or unparseable
 * @param text
 *            the body text: {@code text/plain} when the message has it, else
 *            Markdown converted from the HTML part with hidden content removed
 * @param bodySource
 *            where {@link #text()} came from
 * @param quotedHistoryRemoved
 *            whether recognised quoted history was stripped from
 *            {@link #text()}
 * @param textTruncated
 *            whether {@link #text()} was cut at
 *            {@link MailLimits#maxOutputCharacters()}
 * @param attachments
 *            attachment metadata, with content unless skipped
 * @param loopSignals
 *            the auto-reply and list headers
 * @param deliveryReport
 *            the parsed delivery status of a bounce, or null
 * @param authentication
 *            the sender-authentication verdict;
 *            {@link MailAuthentication#notEvaluated()} until a verifier runs
 * @param anomalies
 *            what the parser worked around
 * @param rawSize
 *            size of the raw message in bytes
 */
public record InboundMail(String messageId, boolean syntheticMessageId, String inReplyTo, List<String> references, MailAddress from,
        List<MailAddress> replyTo, List<MailAddress> to, List<MailAddress> cc, String subject, Instant date, String text,
        BodySource bodySource, boolean quotedHistoryRemoved, boolean textTruncated, List<MailAttachment> attachments,
        LoopSignals loopSignals, DeliveryReport deliveryReport, MailAuthentication authentication, Set<MailAnomaly> anomalies,
        long rawSize) {

    /** Which part of the message {@link #text()} was taken from. */
    public enum BodySource {
        /** A {@code text/plain} part. */
        TEXT_PLAIN,
        /** A {@code text/html} part, converted, because there was no plain text. */
        HTML,
        /** Neither — the message has no readable body. */
        NONE
    }

    public InboundMail {
        Objects.requireNonNull(messageId, "messageId");
        references = references == null ? List.of() : List.copyOf(references);
        replyTo = replyTo == null ? List.of() : List.copyOf(replyTo);
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        subject = subject == null ? "" : subject;
        text = text == null ? "" : text;
        bodySource = bodySource == null ? BodySource.NONE : bodySource;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        loopSignals = loopSignals == null ? LoopSignals.none() : loopSignals;
        authentication = authentication == null ? MailAuthentication.notEvaluated() : authentication;
        anomalies = anomalies == null || anomalies.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(anomalies));
    }

    /** The From address, or null. */
    public String fromAddress() {
        return from == null ? null : from.address();
    }

    /** The From display name, or null. */
    public String fromName() {
        return from == null ? null : from.displayName();
    }

    /** Whether the message is a delivery status notification. */
    public boolean isDeliveryReport() {
        return deliveryReport != null;
    }

    /** This message with a verifier's verdict attached. */
    public InboundMail withAuthentication(MailAuthentication verdict) {
        return new InboundMail(messageId, syntheticMessageId, inReplyTo, references, from, replyTo, to, cc, subject, date, text, bodySource,
                quotedHistoryRemoved, textTruncated, attachments, loopSignals, deliveryReport, verdict, anomalies, rawSize);
    }

    /**
     * No subject, body, addresses or ids: this record ends up in log lines, and all
     * of those are personal data or attacker text.
     */
    @Override
    public String toString() {
        return "InboundMail[rawSize=" + rawSize + ", bodySource=" + bodySource + ", attachments=" + attachments.size() + ", anomalies="
                + anomalies + ", authentication=" + authentication.verdict() + "]";
    }
}
