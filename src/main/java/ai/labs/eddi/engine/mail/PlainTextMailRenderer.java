/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * {@link OutboundMail} → MIME bytes: {@code text/plain}, UTF-8,
 * {@code format=flowed} (email plan §15, v1).
 * <p>
 * Every header value is checked before it is written. A CR or LF in a subject,
 * a display name, an address or a message id is how one header becomes two — a
 * {@code Bcc:} nobody configured — and every one of those fields can come from
 * a received mail or a model's output. Refusing is the only safe handling:
 * "cleaning" a header-injection attempt still sends a message nobody wrote.
 * <p>
 * Every message carries {@code Auto-Submitted} ({@code auto-replied} or
 * {@code auto-generated}, RFC 3834) and {@code X-Auto-Response-Suppress: All},
 * so well-behaved auto-responders do not answer EDDI — the other half of loop
 * prevention.
 * <p>
 * Outbound DKIM signing is the submission server's job, not this class's.
 */
public final class PlainTextMailRenderer {

    /** References written into a reply: the thread root plus the most recent. */
    public static final int MAX_REFERENCES = 20;

    /** Subject characters kept; RFC 5322 caps a line at 998 octets. */
    static final int MAX_SUBJECT_LENGTH = 900;

    private static final Pattern REPLY_PREFIX = Pattern.compile("^\\s*(?:(?:re|aw|sv|antw|vs|odp)\\s*(?:\\[\\d+\\]|\\(\\d+\\))?\\s*:\\s*)+",
            Pattern.CASE_INSENSITIVE);

    private static final Session SESSION = Session.getInstance(new Properties());

    private PlainTextMailRenderer() {
    }

    /** The rendered message and the Message-ID it carries. */
    public record RenderedMail(String messageId, byte[] mime) {

        @Override
        public String toString() {
            return "RenderedMail[" + mime.length + " bytes]";
        }
    }

    /**
     * Renders one message.
     *
     * @throws IllegalArgumentException
     *             when a header field holds a line break or other control
     *             character, an address is invalid or not ASCII, a message id is
     *             malformed, or there is no recipient
     */
    public static RenderedMail render(OutboundMail mail) {
        if (mail.from() == null) {
            throw new IllegalArgumentException("An outbound mail needs a From address.");
        }
        if (mail.to().isEmpty()) {
            throw new IllegalArgumentException("An outbound mail needs at least one recipient.");
        }
        String subject = requireHeaderSafe("subject", mail.subject());
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            subject = subject.substring(0,
                    Character.isHighSurrogate(subject.charAt(MAX_SUBJECT_LENGTH - 1)) ? MAX_SUBJECT_LENGTH - 1 : MAX_SUBJECT_LENGTH);
        }
        InternetAddress from = internetAddress("from", mail.from());
        InternetAddress[] to = internetAddresses("to", mail.to());
        InternetAddress[] cc = internetAddresses("cc", mail.cc());

        String messageId = mail.messageId() == null ? MessageIds.generate(mail.from().domain()) : requireMessageId("messageId", mail.messageId());
        String inReplyTo = mail.inReplyTo() == null ? null : requireMessageId("inReplyTo", mail.inReplyTo());
        List<String> references = new ArrayList<>();
        for (String reference : mail.references()) {
            String id = requireMessageId("references", reference);
            if (!references.contains(id)) {
                references.add(id);
            }
        }
        if (inReplyTo != null && !references.contains(inReplyTo)) {
            references.add(inReplyTo);
        }
        references = MessageIds.cap(references, MAX_REFERENCES);

        String body = FormatFlowed.encode(cleanBody(mail.text()));
        Instant date = mail.date() == null ? Instant.now() : mail.date();
        try {
            MimeMessage message = new FixedIdMimeMessage(SESSION, messageId);
            message.setFrom(from);
            message.setRecipients(Message.RecipientType.TO, to);
            if (cc.length > 0) {
                message.setRecipients(Message.RecipientType.CC, cc);
            }
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setSentDate(Date.from(date));
            if (inReplyTo != null) {
                message.setHeader("In-Reply-To", inReplyTo);
            }
            if (!references.isEmpty()) {
                message.setHeader("References", MimeUtility.fold("References: ".length(), String.join(" ", references)));
            }
            message.setHeader("Auto-Submitted", mail.kind().headerValue());
            message.setHeader("X-Auto-Response-Suppress", "All");
            message.setContent(body, "text/plain; charset=UTF-8; format=flowed");
            message.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length() + 1024);
            message.writeTo(out);
            return new RenderedMail(messageId, out.toByteArray());
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException("The message could not be rendered: " + e.getMessage(), e);
        }
    }

    /**
     * {@code Re: } plus the subject without the reply prefixes it already has —
     * {@code Re:}, {@code RE[2]:}, and the German, Nordic, Dutch and Polish forms
     * ({@code AW:}, {@code SV:}, {@code Antw:}, {@code VS:}, {@code Odp:}) — so a
     * long thread does not read "Re: AW: Re: Re:".
     */
    public static String replySubject(String original) {
        String stripped = original == null ? "" : REPLY_PREFIX.matcher(original).replaceFirst("").strip();
        return stripped.isEmpty() ? "Re:" : "Re: " + stripped;
    }

    /**
     * A header value without controls, line separators or other line-break
     * look-alikes.
     */
    static String requireHeaderSafe(String field, String value) {
        if (value == null) {
            return "";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == (char) 0x2028 || c == (char) 0x2029 || c == '\u0085') {
                throw new IllegalArgumentException("The " + field + " contains a line break or control character, which could inject a header.");
            }
        }
        return value;
    }

    private static String requireMessageId(String field, String id) {
        requireHeaderSafe(field, id);
        if (!MessageIds.isValid(id)) {
            throw new IllegalArgumentException("The " + field + " value is not a valid <id@host> message id.");
        }
        return id;
    }

    private static InternetAddress[] internetAddresses(String field, List<MailAddress> addresses) {
        InternetAddress[] result = new InternetAddress[addresses.size()];
        for (int i = 0; i < addresses.size(); i++) {
            result[i] = internetAddress(field, addresses.get(i));
        }
        return result;
    }

    private static InternetAddress internetAddress(String field, MailAddress address) {
        String spec = requireHeaderSafe(field, address.address());
        String normalized = AddressNormalizer.normalize(spec);
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(normalized)) {
            throw new IllegalArgumentException("The " + field + " address is not ASCII; sending to it needs SMTPUTF8, which is not supported.");
        }
        String personal = address.displayName() == null ? null : requireHeaderSafe(field + " display name", address.displayName());
        try {
            InternetAddress internet = new InternetAddress(normalized, personal, StandardCharsets.UTF_8.name());
            internet.validate();
            return internet;
        } catch (UnsupportedEncodingException | AddressException e) {
            throw new IllegalArgumentException("The " + field + " address is invalid.", e);
        }
    }

    /** Line breaks unified; NUL and other controls but tab removed. */
    private static String cleanBody(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(unified.length());
        for (int i = 0; i < unified.length(); i++) {
            char c = unified.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A message that keeps the Message-ID it was given instead of minting one on
     * save.
     */
    private static final class FixedIdMimeMessage extends MimeMessage {
        private final String messageId;

        FixedIdMimeMessage(Session session, String messageId) {
            super(session);
            this.messageId = messageId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", messageId);
        }
    }
}
