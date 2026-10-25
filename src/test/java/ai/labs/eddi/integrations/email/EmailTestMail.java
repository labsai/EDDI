/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.integrations.email.transport.MailTransport.MailboxMessage;
import ai.labs.eddi.integrations.email.transport.MailboxException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Builds raw test messages and a fake polled message around them. */
final class EmailTestMail {

    private final List<String> headers = new ArrayList<>();
    private String from = "alice@supplier.com";
    private String to = "invoices@contoso.com";
    private String subject = "Invoice 4711";
    private String body = "Please find the invoice attached.";
    private String messageId = "<" + UUID.randomUUID() + "@supplier.com>";
    private final List<String[]> attachments = new ArrayList<>();

    static EmailTestMail mail() {
        return new EmailTestMail();
    }

    EmailTestMail from(String value) {
        this.from = value;
        return this;
    }

    EmailTestMail to(String value) {
        this.to = value;
        return this;
    }

    EmailTestMail subject(String value) {
        this.subject = value;
        return this;
    }

    EmailTestMail body(String value) {
        this.body = value;
        return this;
    }

    EmailTestMail messageId(String value) {
        this.messageId = value;
        return this;
    }

    EmailTestMail header(String line) {
        headers.add(line);
        return this;
    }

    /** filename, content type, raw bytes as ISO-8859-1 text. */
    EmailTestMail attachment(String filename, String contentType, String content) {
        attachments.add(new String[]{filename, contentType, content});
        return this;
    }

    String messageId() {
        return messageId;
    }

    byte[] bytes() {
        StringBuilder out = new StringBuilder();
        out.append("From: ").append(from).append("\r\n");
        out.append("To: ").append(to).append("\r\n");
        out.append("Subject: ").append(subject).append("\r\n");
        out.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n");
        out.append("Message-ID: ").append(messageId).append("\r\n");
        for (String header : headers) {
            out.append(header).append("\r\n");
        }
        out.append("MIME-Version: 1.0\r\n");
        if (attachments.isEmpty()) {
            out.append("Content-Type: text/plain; charset=utf-8\r\n\r\n").append(body).append("\r\n");
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }
        String boundary = "b-" + UUID.randomUUID();
        out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
        out.append("--").append(boundary).append("\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n").append(body).append("\r\n");
        for (String[] attachment : attachments) {
            out.append("--").append(boundary).append("\r\nContent-Type: ").append(attachment[1]).append("\r\n");
            out.append("Content-Disposition: attachment; filename=\"").append(attachment[0]).append("\"\r\n");
            out.append("Content-Transfer-Encoding: base64\r\n\r\n");
            out.append(Base64.getMimeEncoder().encodeToString(attachment[2].getBytes(StandardCharsets.ISO_8859_1))).append("\r\n");
        }
        out.append("--").append(boundary).append("--\r\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A polled message around these bytes. */
    Polled polled(long uid) {
        return new Polled(uid, bytes(), messageId, Instant.now(), false);
    }

    /** A fake {@link MailboxMessage} that counts its body fetches. */
    static final class Polled implements MailboxMessage {
        final long uid;
        final byte[] raw;
        final String envelopeId;
        final Instant internalDate;
        boolean processed;
        final AtomicInteger fetches = new AtomicInteger();
        long reportedSize = -1;

        Polled(long uid, byte[] raw, String envelopeId, Instant internalDate, boolean processed) {
            this.uid = uid;
            this.raw = raw;
            this.envelopeId = envelopeId;
            this.internalDate = internalDate;
            this.processed = processed;
        }

        @Override
        public long uid() {
            return uid;
        }

        @Override
        public String envelopeMessageId() {
            return envelopeId;
        }

        @Override
        public long size() {
            return reportedSize >= 0 ? reportedSize : raw.length;
        }

        @Override
        public Instant internalDate() {
            return internalDate;
        }

        @Override
        public boolean alreadyProcessed() {
            return processed;
        }

        @Override
        public String sourceRef() {
            return "imap:INBOX:1:" + uid;
        }

        @Override
        public byte[] fetchRaw(long maxBytes) throws MailboxException {
            fetches.incrementAndGet();
            if (raw.length > maxBytes) {
                throw new MailboxException(MailboxException.Category.TOO_LARGE, "too large", null);
            }
            return raw.clone();
        }
    }
}
