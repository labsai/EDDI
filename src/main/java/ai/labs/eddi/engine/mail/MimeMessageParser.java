/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimePartDataSource;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParseException;
import jakarta.mail.util.SharedByteArrayInputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Raw RFC 5322 bytes → {@link InboundMail}, within {@link MailLimits}.
 * <p>
 * The one parser of the mail core (email plan §6): every transport hands it the
 * same raw bytes, so there is one place where hostile MIME is bounded. It uses
 * Angus Mail (Jakarta Mail) for the grammar and keeps the walk — and so every
 * limit — in its own hands:
 * <ul>
 * <li><b>Size first.</b> The raw size and the header block are checked before
 * Jakarta Mail sees a byte.</li>
 * <li><b>Structure before materialisation.</b> A multipart's delimiters are
 * counted in the raw bytes before Jakarta Mail builds a part object for each,
 * so a few kilobytes declaring a million empty parts are refused, not
 * allocated. Depth is checked on the way down, so nesting cannot overflow the
 * stack. A multipart with a transfer encoding (forbidden by RFC 2045, and the
 * one way to hide delimiters from that count) is skipped.</li>
 * <li><b>Nothing is expanded.</b> {@code message/rfc822} parts and archives are
 * attachments, never walked into.</li>
 * <li><b>{@code text/plain} preferred</b>; HTML only as a fallback, converted
 * by {@link HtmlToMarkdownConverter#convertMail} with its hidden-content
 * pass.</li>
 * <li><b>Never throws for bad text.</b> Unknown charsets, broken encoded words,
 * unparseable dates and invalid addresses become {@link MailAnomaly anomalies},
 * not exceptions. Only a breached limit or bytes that are not a message at all
 * refuse the message.</li>
 * </ul>
 * Thread-safe and stateless: one instance serves every mailbox.
 */
@ApplicationScoped
public class MimeMessageParser {

    /** Bytes of a delivery-status part read for bounce parsing. */
    private static final int MAX_DELIVERY_STATUS_BYTES = 64 * 1024;

    private static final int MAX_HEADER_VALUE_LENGTH = 2000;
    private static final int MAX_SUBJECT_LENGTH = 1000;
    private static final int MAX_FILENAME_LENGTH = 255;

    /**
     * Lenient where the wild is lenient, never where it would cost a limit.
     * {@code allowutf8} reads RFC 6532 headers as UTF-8; unknown transfer encodings
     * yield the raw bytes instead of an exception; file names are decoded here, not
     * by the library, so an unknown charset does not throw.
     */
    private static final Session SESSION = Session.getInstance(sessionProperties());

    private final HtmlToMarkdownConverter htmlConverter;

    @Inject
    public MimeMessageParser(HtmlToMarkdownConverter htmlConverter) {
        this.htmlConverter = htmlConverter;
    }

    private static Properties sessionProperties() {
        Properties properties = new Properties();
        properties.setProperty("mail.mime.allowutf8", "true");
        properties.setProperty("mail.mime.ignoreunknownencoding", "true");
        properties.setProperty("mail.mime.decodefilename", "false");
        properties.setProperty("mail.mime.decodeparameters", "true");
        properties.setProperty("mail.mime.parameters.strict", "false");
        properties.setProperty("mail.mime.address.strict", "false");
        properties.setProperty("mail.mime.base64.ignoreerrors", "true");
        properties.setProperty("mail.mime.multipart.allowempty", "true");
        properties.setProperty("mail.mime.multipart.ignoremissingendboundary", "true");
        return properties;
    }

    /**
     * Parses one message.
     *
     * @param raw
     *            the RFC 5322 bytes, as the transport delivered them
     * @param limits
     *            what the parse may spend
     * @param stripQuotedHistory
     *            whether to remove recognised quoted history from the text
     *            ({@link QuoteStripper})
     * @throws MailParseException
     *             when a limit is breached or the bytes are not a message
     */
    public InboundMail parse(byte[] raw, MailLimits limits, boolean stripQuotedHistory) throws MailParseException {
        MailLimits bounds = limits == null ? MailLimits.defaults() : limits;
        if (raw == null || raw.length == 0) {
            throw new MailParseException(MailParseException.Reason.MALFORMED, "The message is empty.");
        }
        if (raw.length > bounds.maxRawBytes()) {
            throw new MailParseException(MailParseException.Reason.TOO_LARGE,
                    "The message is " + raw.length + " bytes, over the limit of " + bounds.maxRawBytes() + ".");
        }
        requireHeaderBlockWithinLimit(raw, bounds.maxHeaderBytes());

        MimeMessage message;
        try {
            message = new MimeMessage(SESSION, new SharedByteArrayInputStream(raw));
        } catch (MessagingException e) {
            throw new MailParseException(MailParseException.Reason.MALFORMED, "The message headers cannot be parsed.", e);
        }

        Set<MailAnomaly> anomalies = EnumSet.noneOf(MailAnomaly.class);
        for (String singleton : List.of("Message-ID", "Subject", "Date", "In-Reply-To", "References")) {
            String[] values = headers(message, singleton);
            if (values != null && values.length > 1) {
                anomalies.add(MailAnomaly.DUPLICATE_SINGLETON_HEADER);
            }
        }

        String messageId = firstHeader(message, "Message-ID");
        boolean synthetic = false;
        String id = messageId == null ? null : MessageIds.parseFirst(messageId).orElse(null);
        if (id == null) {
            anomalies.add(messageId == null ? MailAnomaly.MISSING_MESSAGE_ID : MailAnomaly.INVALID_MESSAGE_ID);
            id = "sha256:" + sha256(raw);
            synthetic = true;
        }
        String inReplyTo = MessageIds.parseFirst(firstHeader(message, "In-Reply-To")).orElse(null);
        List<String> references = MessageIds.parseAll(firstHeader(message, "References"), MessageIds.MAX_PARSED_REFERENCES);

        MailAddress from = parseFrom(message, anomalies);
        List<MailAddress> replyTo = parseAddressList(message, "Reply-To", anomalies);
        List<MailAddress> to = parseAddressList(message, "To", anomalies);
        List<MailAddress> cc = parseAddressList(message, "Cc", anomalies);
        String subject = cleanSubject(firstHeader(message, "Subject"));
        Instant date = parseDate(message, anomalies);

        Walk walk = new Walk(bounds, anomalies, htmlConverter);
        try {
            walk.visit(message, 0, walk.root);
        } catch (RuntimeException e) {
            // Hostile MIME must end in a refusal the caller records, never in an
            // exception type it does not expect from a parser.
            throw new MailParseException(MailParseException.Reason.MALFORMED, "The message structure cannot be read.", e);
        }

        LoopSignals signals = new LoopSignals(signal(message, "Auto-Submitted"), signal(message, "X-Autoreply"),
                signal(message, "X-Autorespond"), signal(message, "Precedence"), signal(message, "List-Id"),
                signal(message, "List-Unsubscribe"), signal(message, "Return-Path"), walk.reportType);

        // A forward's "history" is the content being passed on, and Outlook marks it
        // exactly as it marks a reply's — so a forward is never stripped.
        Body body = body(walk.root, bounds, stripQuotedHistory && !QuoteStripper.isForwardSubject(subject));
        DeliveryReport report = walk.reportType == null || walk.deliveryRecipients == null
                ? null
                : new DeliveryReport(walk.reportType, walk.reportingMta, walk.originalMessageId, walk.deliveryRecipients);

        return new InboundMail(id, synthetic, inReplyTo, references, from, replyTo, to, cc, subject, date, body.text(), body.source(),
                body.quotedRemoved(), body.truncated(), walk.root.attachments, signals, report, MailAuthentication.notEvaluated(), anomalies,
                raw.length);
    }

    // ------------------------------------------------------------- headers

    /**
     * Refuses a header block over the limit before Jakarta Mail reads it into
     * memory as a list of header objects.
     */
    private static void requireHeaderBlockWithinLimit(byte[] raw, int maxHeaderBytes) throws MailParseException {
        int scanLimit = (int) Math.min(raw.length, (long) maxHeaderBytes + 4);
        for (int i = 0; i < scanLimit - 1; i++) {
            if (raw[i] == '\n' && (raw[i + 1] == '\n' || (raw[i + 1] == '\r' && i + 2 < raw.length && raw[i + 2] == '\n'))) {
                if (i > maxHeaderBytes) {
                    break;
                }
                return;
            }
        }
        if (raw.length > maxHeaderBytes) {
            throw new MailParseException(MailParseException.Reason.HEADERS_TOO_LARGE,
                    "The message's header block is larger than " + maxHeaderBytes + " bytes.");
        }
    }

    private static String[] headers(Part part, String name) {
        try {
            return part.getHeader(name);
        } catch (MessagingException e) {
            return null;
        }
    }

    private static String firstHeader(Part part, String name) {
        String[] values = headers(part, name);
        if (values == null || values.length == 0 || values[0] == null) {
            return null;
        }
        String unfolded = MimeUtility.unfold(values[0]).strip();
        return unfolded.length() > MAX_HEADER_VALUE_LENGTH * 8 ? unfolded.substring(0, MAX_HEADER_VALUE_LENGTH * 8) : unfolded;
    }

    private static String signal(Part part, String name) {
        String value = firstHeader(part, name);
        if (value == null) {
            return null;
        }
        String cleaned = stripControls(value).strip();
        return cleaned.length() > MAX_HEADER_VALUE_LENGTH ? cleaned.substring(0, MAX_HEADER_VALUE_LENGTH) : cleaned;
    }

    private static MailAddress parseFrom(MimeMessage message, Set<MailAnomaly> anomalies) {
        String[] values = headers(message, "From");
        if (values == null || values.length == 0) {
            anomalies.add(MailAnomaly.MISSING_FROM);
            return null;
        }
        if (values.length > 1) {
            anomalies.add(MailAnomaly.MULTIPLE_FROM);
            return null;
        }
        AddressNormalizer.Parsed parsed = AddressNormalizer.parseList(MimeUtility.unfold(values[0]));
        if (parsed.addresses().size() > 1 || (parsed.invalid() > 0 && !parsed.addresses().isEmpty())) {
            // RFC 5322 allows several authors (with a Sender), DMARC does not: whose
            // domain would be authenticated? Ambiguity is not an identity — and an
            // unparseable entry beside a valid one is still a second author a mail
            // client may show.
            anomalies.add(MailAnomaly.MULTIPLE_FROM);
            return null;
        }
        if (parsed.addresses().isEmpty()) {
            anomalies.add(MailAnomaly.MISSING_FROM);
            return null;
        }
        return parsed.addresses().get(0);
    }

    private static List<MailAddress> parseAddressList(MimeMessage message, String name, Set<MailAnomaly> anomalies) {
        String[] values = headers(message, name);
        if (values == null) {
            return List.of();
        }
        List<MailAddress> addresses = new ArrayList<>();
        for (String value : values) {
            AddressNormalizer.Parsed parsed = AddressNormalizer.parseList(MimeUtility.unfold(value));
            addresses.addAll(parsed.addresses());
            if (parsed.invalid() > 0) {
                anomalies.add(MailAnomaly.INVALID_ADDRESS_SKIPPED);
            }
        }
        return addresses;
    }

    private static String cleanSubject(String raw) {
        if (raw == null) {
            return "";
        }
        String decoded = stripControls(EncodedWords.decode(raw)).replaceAll("\\s{2,}", " ").strip();
        return decoded.length() > MAX_SUBJECT_LENGTH ? truncate(decoded, MAX_SUBJECT_LENGTH) : decoded;
    }

    private static Instant parseDate(MimeMessage message, Set<MailAnomaly> anomalies) {
        if (firstHeader(message, "Date") == null) {
            return null;
        }
        try {
            Date sent = message.getSentDate();
            if (sent == null) {
                anomalies.add(MailAnomaly.UNPARSEABLE_DATE);
                return null;
            }
            return sent.toInstant();
        } catch (MessagingException | RuntimeException e) {
            anomalies.add(MailAnomaly.UNPARSEABLE_DATE);
            return null;
        }
    }

    // ---------------------------------------------------------------- walk

    /** What one subtree contributed. */
    private static final class Collected {
        final List<String> plain = new ArrayList<>();
        final List<String> html = new ArrayList<>();
        final List<MailAttachment> attachments = new ArrayList<>();

        void addAll(Collected other) {
            plain.addAll(other.plain);
            html.addAll(other.html);
            attachments.addAll(other.attachments);
        }

        boolean hasPlainText() {
            return plain.stream().anyMatch(text -> !text.isBlank());
        }
    }

    /** One parse's walk over the MIME tree, with the counters every limit reads. */
    private static final class Walk {
        final MailLimits limits;
        final Set<MailAnomaly> anomalies;
        final Collected root = new Collected();
        int parts;
        int attachmentCount;
        String reportType;
        String reportingMta;
        String originalMessageId;
        List<DeliveryReport.Recipient> deliveryRecipients;

        final HtmlToMarkdownConverter htmlConverter;

        Walk(MailLimits limits, Set<MailAnomaly> anomalies, HtmlToMarkdownConverter htmlConverter) {
            this.limits = limits;
            this.anomalies = anomalies;
            this.htmlConverter = htmlConverter;
        }

        void visit(MimePart part, int depth, Collected into) throws MailParseException {
            if (depth > limits.maxMimeDepth()) {
                throw new MailParseException(MailParseException.Reason.TOO_DEEP,
                        "The message nests MIME parts deeper than " + limits.maxMimeDepth() + " levels.");
            }
            if (++parts > limits.maxParts()) {
                throw tooManyParts();
            }
            ContentType type = contentType(part);
            String baseType = type.getBaseType().toLowerCase(Locale.ROOT);
            if (baseType.startsWith("multipart/")) {
                visitMultipart(part, type, baseType, depth, into);
            } else {
                visitLeaf(part, type, baseType, into);
            }
        }

        private MailParseException tooManyParts() {
            return new MailParseException(MailParseException.Reason.TOO_MANY_PARTS,
                    "The message has more than " + limits.maxParts() + " MIME parts.");
        }

        private ContentType contentType(MimePart part) {
            String raw = firstHeader(part, "Content-Type");
            if (raw == null) {
                return new ContentType("text", "plain", null);
            }
            try {
                return new ContentType(raw);
            } catch (ParseException e) {
                anomalies.add(MailAnomaly.MALFORMED_CONTENT_TYPE);
                return new ContentType("text", "plain", null);
            }
        }

        private void visitMultipart(MimePart part, ContentType type, String baseType, int depth, Collected into) throws MailParseException {
            String encoding = transferEncoding(part);
            if (encoding != null && !encoding.equals("7bit") && !encoding.equals("8bit") && !encoding.equals("binary")) {
                // RFC 2045 §6.4 forbids it, and a base64 multipart would hide its
                // delimiters from the count below.
                anomalies.add(MailAnomaly.MALFORMED_PART);
                return;
            }
            String boundary = type.getParameter("boundary");
            if (boundary == null || boundary.isEmpty() || boundary.length() > 200) {
                anomalies.add(MailAnomaly.MALFORMED_PART);
                return;
            }
            int remaining = limits.maxParts() - parts;
            try (InputStream rawContent = rawStream(part)) {
                // Delimiters include the closing one, so N parts show N + 1.
                if (countDelimiters(rawContent, boundary, remaining + 2) - 1 > remaining) {
                    // Worded apart from the walk's own refusal so a test can tell
                    // that this bound fired before any part object existed.
                    throw new MailParseException(MailParseException.Reason.TOO_MANY_PARTS,
                            "The message declares more than " + limits.maxParts() + " MIME parts.");
                }
            } catch (IOException | MessagingException e) {
                anomalies.add(MailAnomaly.MALFORMED_PART);
                return;
            }
            MimeMultipart multipart;
            int count;
            try {
                multipart = new MimeMultipart(new MimePartDataSource(part));
                count = multipart.getCount();
            } catch (MessagingException | RuntimeException e) {
                anomalies.add(MailAnomaly.MALFORMED_PART);
                return;
            }
            if ("multipart/report".equals(baseType)) {
                String report = type.getParameter("report-type");
                reportType = report == null ? "unknown" : report.strip().toLowerCase(Locale.ROOT);
            }
            if ("multipart/alternative".equals(baseType)) {
                visitAlternative(multipart, count, depth, into);
                return;
            }
            for (int i = 0; i < count; i++) {
                MimePart child = bodyPart(multipart, i);
                if (child != null) {
                    visit(child, depth + 1, into);
                }
            }
        }

        /**
         * One rendering of the content is read, not all of them: the plain-text one
         * when it says anything, else the first with HTML. Attachments of the other
         * renderings (inline images of the HTML) go with them.
         */
        private void visitAlternative(MimeMultipart multipart, int count, int depth, Collected into) throws MailParseException {
            List<Collected> alternatives = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                MimePart child = bodyPart(multipart, i);
                if (child == null) {
                    continue;
                }
                Collected alternative = new Collected();
                visit(child, depth + 1, alternative);
                alternatives.add(alternative);
            }
            Collected chosen = alternatives.stream().filter(Collected::hasPlainText).findFirst()
                    .orElse(alternatives.stream().filter(a -> !a.html.isEmpty()).findFirst()
                            .orElse(alternatives.isEmpty() ? null : alternatives.get(0)));
            Collected plainSide = alternatives.stream().filter(Collected::hasPlainText).findFirst().orElse(null);
            Collected htmlSide = alternatives.stream().filter(a -> !a.html.isEmpty()).findFirst().orElse(null);
            if (plainSide != null && htmlSide != null && plainSide != htmlSide) {
                // The agent reads the plain part; a person checking the mail sees the
                // HTML. Compare what each audience gets — the HTML as rendered, hidden
                // content removed.
                String visible = htmlConverter.convertMail(String.join("\n", htmlSide.html), Integer.MAX_VALUE);
                if (AlternativeComparison.diverge(String.join("\n\n", plainSide.plain), visible)) {
                    anomalies.add(MailAnomaly.ALTERNATIVES_DIVERGE);
                }
            }
            if (chosen != null) {
                into.addAll(chosen);
            }
        }

        private MimePart bodyPart(MimeMultipart multipart, int index) {
            try {
                return (MimePart) multipart.getBodyPart(index);
            } catch (MessagingException | RuntimeException e) {
                anomalies.add(MailAnomaly.MALFORMED_PART);
                return null;
            }
        }

        private void visitLeaf(MimePart part, ContentType type, String baseType, Collected into) throws MailParseException {
            String disposition = disposition(part);
            String filename = filename(part);
            boolean isBodyType = "text/plain".equals(baseType) || "text/html".equals(baseType);
            if (isBodyType && !"attachment".equals(disposition) && filename == null) {
                byte[] bytes = readBounded(part, limits.maxRawBytes());
                if (bytes == null) {
                    anomalies.add(MailAnomaly.MALFORMED_PART);
                    return;
                }
                MailCharsets.Decoded decoded = MailCharsets.decode(bytes, type.getParameter("charset"));
                if (decoded.charsetUnknown()) {
                    anomalies.add(MailAnomaly.UNKNOWN_CHARSET);
                }
                if ("text/plain".equals(baseType)) {
                    String text = decoded.text();
                    if ("flowed".equalsIgnoreCase(type.getParameter("format"))) {
                        text = FormatFlowed.decode(text, "yes".equalsIgnoreCase(type.getParameter("delsp")));
                    }
                    into.plain.add(text);
                } else {
                    into.html.add(decoded.text());
                }
                return;
            }
            if ("message/delivery-status".equals(baseType) || "message/global-delivery-status".equals(baseType)) {
                readDeliveryStatus(part);
                return;
            }
            if (reportType != null && ("message/rfc822".equals(baseType) || "text/rfc822-headers".equals(baseType)
                    || "message/global".equals(baseType) || "message/global-headers".equals(baseType))) {
                readOriginalMessageId(part);
            }
            attachment(part, baseType, disposition, filename, into);
        }

        private void attachment(MimePart part, String baseType, String disposition, String filename, Collected into)
                throws MailParseException {
            if (++attachmentCount > limits.maxAttachments()) {
                throw new MailParseException(MailParseException.Reason.TOO_MANY_ATTACHMENTS,
                        "The message has more than " + limits.maxAttachments() + " attachments.");
            }
            byte[] bytes = readBounded(part, limits.maxAttachmentBytes());
            String contentId = contentId(part);
            boolean inline = "inline".equals(disposition);
            boolean archive = AttachmentClassifier.isArchive(filename, baseType);
            if (bytes == null) {
                boolean executable = AttachmentClassifier.isExecutable(filename, baseType, null);
                into.attachments.add(new MailAttachment(filename, baseType, -1, contentId, inline, executable, archive,
                        MailAttachment.SkipReason.TOO_LARGE, null));
                return;
            }
            boolean executable = AttachmentClassifier.isExecutable(filename, baseType, Arrays.copyOf(bytes, Math.min(bytes.length, 8)));
            if (executable) {
                into.attachments.add(new MailAttachment(filename, baseType, bytes.length, contentId, inline, true, archive,
                        MailAttachment.SkipReason.EXECUTABLE, null));
                return;
            }
            into.attachments.add(new MailAttachment(filename, baseType, bytes.length, contentId, inline, false, archive, null,
                    new AttachmentContent(bytes)));
        }

        private void readDeliveryStatus(MimePart part) {
            byte[] bytes = readBounded(part, MAX_DELIVERY_STATUS_BYTES);
            if (bytes == null) {
                anomalies.add(MailAnomaly.MALFORMED_PART);
                return;
            }
            List<List<String[]>> blocks = DeliveryStatusFields.parse(new String(bytes, StandardCharsets.UTF_8));
            List<DeliveryReport.Recipient> recipients = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                List<String[]> fields = blocks.get(i);
                if (i == 0) {
                    String mta = DeliveryStatusFields.value(fields, "reporting-mta");
                    reportingMta = mta == null ? null : DeliveryStatusFields.afterType(mta);
                    if (DeliveryStatusFields.value(fields, "final-recipient") == null) {
                        continue;
                    }
                }
                String finalRecipient = DeliveryStatusFields.value(fields, "final-recipient");
                if (finalRecipient == null) {
                    continue;
                }
                String address = DeliveryStatusFields.afterType(finalRecipient);
                recipients.add(new DeliveryReport.Recipient(AddressNormalizer.tryNormalize(address).orElse(capped(address, 254)),
                        lower(DeliveryStatusFields.value(fields, "action")), firstToken(DeliveryStatusFields.value(fields, "status")),
                        capped(DeliveryStatusFields.value(fields, "diagnostic-code"), 500)));
            }
            deliveryRecipients = recipients;
        }

        private void readOriginalMessageId(MimePart part) {
            if (originalMessageId != null) {
                return;
            }
            try (InputStream in = part.getInputStream()) {
                InternetHeaders headers = new InternetHeaders(in, true);
                String value = headers.getHeader("Message-ID", null);
                originalMessageId = value == null ? null : MessageIds.parseFirst(MimeUtility.unfold(value)).orElse(null);
            } catch (IOException | MessagingException | RuntimeException e) {
                anomalies.add(MailAnomaly.MALFORMED_PART);
            }
        }

        private String disposition(MimePart part) {
            try {
                String disposition = part.getDisposition();
                return disposition == null ? null : disposition.strip().toLowerCase(Locale.ROOT);
            } catch (MessagingException | RuntimeException e) {
                return null;
            }
        }

        private static String filename(MimePart part) {
            String name;
            try {
                name = part.getFileName();
            } catch (MessagingException | RuntimeException e) {
                return null;
            }
            return cleanFilename(name);
        }

        private static String contentId(MimePart part) {
            String value = firstHeader(part, "Content-ID");
            if (value == null) {
                return null;
            }
            String id = stripControls(value).strip();
            if (id.startsWith("<") && id.endsWith(">")) {
                id = id.substring(1, id.length() - 1);
            }
            return id.isEmpty() ? null : capped(id, MessageIds.MAX_ID_LENGTH);
        }

        /**
         * The decoded content, or null when it is over {@code max} bytes or unreadable.
         */
        private byte[] readBounded(MimePart part, long max) {
            try (InputStream in = part.getInputStream()) {
                return readAtMost(in, max);
            } catch (IOException | MessagingException | RuntimeException e) {
                return null;
            }
        }
    }

    private static String transferEncoding(MimePart part) {
        String value = firstHeader(part, "Content-Transfer-Encoding");
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private static InputStream rawStream(MimePart part) throws MessagingException {
        if (part instanceof MimeMessage message) {
            return message.getRawInputStream();
        }
        return ((MimeBodyPart) part).getRawInputStream();
    }

    /**
     * Counts multipart delimiter lines ({@code --boundary} at the start of a line)
     * in a part's raw content, stopping once {@code stopAfter} is reached.
     */
    static int countDelimiters(InputStream in, String boundary, int stopAfter) throws IOException {
        byte[] pattern = ("\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int[] failure = failureFunction(pattern);
        int count = 0;
        // Start as if a newline preceded the content, so a delimiter on the first
        // line counts.
        int matched = 1;
        // A full match only counts when the line ends there (or "--" closes it):
        // "--b1" is a prefix of "--b10", which is a different boundary.
        boolean pending = false;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            for (int i = 0; i < read; i++) {
                byte b = buffer[i];
                if (pending) {
                    pending = false;
                    if ((b == '\r' || b == '\n' || b == ' ' || b == '\t' || b == '-') && ++count >= stopAfter) {
                        return count;
                    }
                }
                while (matched > 0 && pattern[matched] != b) {
                    matched = failure[matched - 1];
                }
                if (pattern[matched] == b) {
                    matched++;
                }
                if (matched == pattern.length) {
                    pending = true;
                    matched = failure[matched - 1];
                }
            }
        }
        return pending ? count + 1 : count;
    }

    private static int[] failureFunction(byte[] pattern) {
        int[] failure = new int[pattern.length];
        int k = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (k > 0 && pattern[k] != pattern[i]) {
                k = failure[k - 1];
            }
            if (pattern[k] == pattern[i]) {
                k++;
            }
            failure[i] = k;
        }
        return failure;
    }

    /** Reads at most {@code max} bytes; null when there are more. */
    static byte[] readAtMost(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > max) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- body

    private record Body(String text, InboundMail.BodySource source, boolean quotedRemoved, boolean truncated) {
    }

    private Body body(Collected collected, MailLimits limits, boolean stripQuotedHistory) {
        String text;
        InboundMail.BodySource source;
        boolean quotedRemoved = false;
        if (collected.hasPlainText()) {
            text = normalizeText(String.join("\n\n", collected.plain.stream().filter(t -> !t.isBlank()).toList()));
            source = InboundMail.BodySource.TEXT_PLAIN;
            if (stripQuotedHistory) {
                QuoteStripper.Result stripped = QuoteStripper.stripText(text);
                text = stripped.content();
                quotedRemoved = stripped.removed();
            }
        } else if (!collected.html.isEmpty()) {
            Document doc = Jsoup.parse(String.join("\n", collected.html));
            if (stripQuotedHistory) {
                quotedRemoved = QuoteStripper.stripHtml(doc);
            }
            // The converter's own cap would append a notice; the cut is made below,
            // once, the same way for both sources.
            text = normalizeText(htmlConverter.convertMail(doc, Integer.MAX_VALUE));
            source = InboundMail.BodySource.HTML;
        } else {
            return new Body("", InboundMail.BodySource.NONE, false, false);
        }
        boolean truncated = text.length() > limits.maxOutputCharacters();
        return new Body(truncated ? truncate(text, limits.maxOutputCharacters()) : text, source, quotedRemoved, truncated);
    }

    /**
     * Line breaks to {@code \n}; C0 controls (but tab and newline), DEL and the
     * bidirectional override characters removed — the last can make text read
     * differently from what it says.
     */
    static String normalizeText(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(unified.length());
        for (int i = 0; i < unified.length(); i++) {
            char c = unified.charAt(i);
            boolean control = (c < 0x20 && c != '\n' && c != '\t') || c == 0x7F;
            boolean bidiOverride = (c >= (char) 0x202A && c <= (char) 0x202E) || (c >= (char) 0x2066 && c <= (char) 0x2069);
            if (!control && !bidiOverride) {
                out.append(c);
            }
        }
        return out.toString().strip();
    }

    private static String stripControls(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\r' || c == '\n' || c == (char) 0x2028 || c == (char) 0x2029) {
                out.append(' ');
            } else if (!Character.isISOControl(c) && !(c >= (char) 0x202A && c <= (char) 0x202E) && !(c >= (char) 0x2066 && c <= (char) 0x2069)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A file name with no path, no controls, decoded, capped; null when nothing is
     * left.
     */
    static String cleanFilename(String name) {
        if (name == null) {
            return null;
        }
        String decoded = stripControls(EncodedWords.decode(name)).strip();
        int slash = Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\'));
        String base = slash >= 0 ? decoded.substring(slash + 1) : decoded;
        base = base.strip();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            return null;
        }
        return base.length() > MAX_FILENAME_LENGTH ? truncate(base, MAX_FILENAME_LENGTH) : base;
    }

    private static String truncate(String text, int length) {
        int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }

    private static String capped(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = stripControls(value).strip();
        return cleaned.length() > max ? truncate(cleaned, max) : cleaned;
    }

    private static String lower(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String firstToken(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        int space = stripped.indexOf(' ');
        return capped(space < 0 ? stripped : stripped.substring(0, space), 32);
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
