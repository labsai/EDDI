/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A message EDDI is about to send: a notification or a reply (email plan §15,
 * §16).
 * <p>
 * Plain data; {@link PlainTextMailRenderer} validates it and turns it into
 * MIME. Who may be a recipient is decided by the caller's policy, not here —
 * the plan's rule is that the model never chooses one — and
 * {@link #replyTo(InboundMail, MailAddress, String)} encodes the
 * conversation-mode default: the reply goes to the original {@code From}, never
 * its {@code Reply-To} or {@code Cc}.
 *
 * @param from
 *            the sending mailbox
 * @param to
 *            recipients, at least one
 * @param cc
 *            copy recipients, usually none
 * @param subject
 *            the subject, unencoded
 * @param text
 *            the body, plain text
 * @param messageId
 *            the Message-ID to use, or null to have the renderer mint one — a
 *            caller that records the id in a ledger before sending passes its
 *            own
 * @param inReplyTo
 *            the id being answered, or null
 * @param references
 *            the thread's ids, oldest first; the renderer caps the chain
 * @param kind
 *            reply or notification, which decides {@code Auto-Submitted}
 * @param date
 *            the Date header, or null for now
 */
public record OutboundMail(MailAddress from, List<MailAddress> to, List<MailAddress> cc, String subject, String text, String messageId,
        String inReplyTo, List<String> references, Kind kind, Instant date) {

    /** What kind of automated mail this is — RFC 3834's two values. */
    public enum Kind {
        /** An answer to a message: {@code Auto-Submitted: auto-replied}. */
        AUTO_REPLIED("auto-replied"),
        /**
         * Mail EDDI starts itself, e.g. a HITL notification: {@code auto-generated}.
         */
        AUTO_GENERATED("auto-generated");

        private final String headerValue;

        Kind(String headerValue) {
            this.headerValue = headerValue;
        }

        /** The {@code Auto-Submitted} value. */
        public String headerValue() {
            return headerValue;
        }
    }

    public OutboundMail {
        Objects.requireNonNull(kind, "kind");
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        references = references == null ? List.of() : List.copyOf(references);
        subject = subject == null ? "" : subject;
        text = text == null ? "" : text;
    }

    /**
     * A reply to {@code original}, threaded: to its {@code From}, with {@code Re:}
     * on the subject, {@code In-Reply-To} its id and its id appended to
     * {@code References}. A synthetic id (the original had none) is not an RFC id
     * and is never written into a header.
     *
     * @throws IllegalArgumentException
     *             when the original has no usable From
     */
    public static OutboundMail replyTo(InboundMail original, MailAddress from, String text) {
        if (original.from() == null) {
            throw new IllegalArgumentException("The original message has no single valid From address to reply to.");
        }
        return replyInThread(from, original.from(), original.subject(), original.syntheticMessageId() ? null : original.messageId(),
                original.references(), text, null);
    }

    /**
     * A threaded reply from the facts a ledger and a conversation keep about the
     * original — for a reply rebuilt after the parsed message is gone. To exactly
     * one recipient, never a copy: who that is was decided by the caller's policy.
     *
     * @param originalMessageId
     *            the RFC id being answered, or null when the original had none (a
     *            synthetic id is never written into a header)
     * @param originalReferences
     *            the original's References, oldest first
     * @param messageId
     *            the reply's own Message-ID, or null to have the renderer mint one
     */
    public static OutboundMail replyInThread(MailAddress from, MailAddress to, String originalSubject, String originalMessageId,
                                             List<String> originalReferences, String text, String messageId) {
        if (to == null) {
            throw new IllegalArgumentException("A reply needs its one recipient.");
        }
        List<String> references = new ArrayList<>(originalReferences == null ? List.of() : originalReferences);
        String inReplyTo = null;
        if (originalMessageId != null && !originalMessageId.isBlank()) {
            inReplyTo = originalMessageId;
            references.remove(inReplyTo);
            references.add(inReplyTo);
        }
        return new OutboundMail(from, List.of(to), List.of(), PlainTextMailRenderer.replySubject(originalSubject), text, messageId, inReplyTo,
                references, Kind.AUTO_REPLIED, null);
    }

    /** No body, subject or addresses: this record ends up in log lines. */
    @Override
    public String toString() {
        return "OutboundMail[kind=" + kind + ", recipients=" + (to.size() + cc.size()) + "]";
    }
}
