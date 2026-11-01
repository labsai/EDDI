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
        List<String> references = new ArrayList<>(original.references());
        String inReplyTo = null;
        if (!original.syntheticMessageId()) {
            inReplyTo = original.messageId();
            references.remove(inReplyTo);
            references.add(inReplyTo);
        }
        return new OutboundMail(from, List.of(original.from()), List.of(), PlainTextMailRenderer.replySubject(original.subject()), text,
                null, inReplyTo, references, Kind.AUTO_REPLIED, null);
    }

    /** No body, subject or addresses: this record ends up in log lines. */
    @Override
    public String toString() {
        return "OutboundMail[kind=" + kind + ", recipients=" + (to.size() + cc.size()) + "]";
    }
}
