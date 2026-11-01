/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static ai.labs.eddi.engine.mail.MailFixtures.parse;
import static ai.labs.eddi.engine.mail.MailFixtures.parser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainTextMailRendererTest {

    private static final MailAddress EDDI = new MailAddress("support@example.org", "Example Support");
    private static final MailAddress ALICE = new MailAddress("alice@example.com", "Alice Example");

    private static OutboundMail notification(String subject, String text) {
        return new OutboundMail(EDDI, List.of(ALICE), List.of(), subject, text, null, null, List.of(), OutboundMail.Kind.AUTO_GENERATED,
                Instant.parse("2026-10-24T12:00:00Z"));
    }

    private static MimeMessage reparse(byte[] mime) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime));
    }

    @Nested
    @DisplayName("headers")
    class Headers {

        @Test
        @DisplayName("text/plain UTF-8 flowed, Auto-Submitted, X-Auto-Response-Suppress, random EDDI Message-ID")
        void notificationHeaders() throws Exception {
            var rendered = PlainTextMailRenderer.render(notification("Approval needed", "An agent is waiting for you."));
            MimeMessage message = reparse(rendered.mime());

            assertTrue(rendered.messageId().matches("<eddi\\.[0-9a-f-]{36}@example\\.org>"), rendered.messageId());
            assertEquals(rendered.messageId(), message.getMessageID());
            assertTrue(message.getContentType().toLowerCase().contains("text/plain"));
            assertTrue(message.getContentType().toLowerCase().contains("charset=utf-8"));
            assertTrue(message.getContentType().toLowerCase().contains("format=flowed"));
            assertEquals("auto-generated", message.getHeader("Auto-Submitted", null));
            assertEquals("All", message.getHeader("X-Auto-Response-Suppress", null));
            assertEquals("Approval needed", message.getSubject());
            assertEquals("support@example.org", ((InternetAddress) message.getFrom()[0]).getAddress());
        }

        @Test
        @DisplayName("two ids from the generator never repeat")
        void idsAreRandom() {
            assertFalse(MessageIds.generate("example.org").equals(MessageIds.generate("example.org")));
        }

        @Test
        @DisplayName("a caller-supplied Message-ID (recorded in a ledger first) is kept")
        void suppliedMessageId() throws Exception {
            var mail = new OutboundMail(EDDI, List.of(ALICE), List.of(), "s", "t", "<eddi.fixed@example.org>", null, List.of(),
                    OutboundMail.Kind.AUTO_GENERATED, null);

            assertEquals("<eddi.fixed@example.org>", reparse(PlainTextMailRenderer.render(mail).mime()).getMessageID());
        }

        @Test
        @DisplayName("a non-ASCII subject and display name are RFC 2047 encoded and survive a round trip")
        void encodedSubject() throws Exception {
            var mail = new OutboundMail(new MailAddress("support@example.org", "Kundendienst Müller"), List.of(ALICE), List.of(),
                    "Rückfrage — Bestellung", "Grüße", null, null, List.of(), OutboundMail.Kind.AUTO_REPLIED, null);

            byte[] mime = PlainTextMailRenderer.render(mail).mime();
            String headers = new String(mime, StandardCharsets.US_ASCII);

            assertTrue(headers.contains("=?UTF-8?"), "headers must be 7-bit");
            InboundMail parsed = parser().parse(mime, MailLimits.defaults(), false);
            assertEquals("Rückfrage — Bestellung", parsed.subject());
            assertEquals("Kundendienst Müller", parsed.fromName());
            assertEquals("Grüße", parsed.text());
            assertEquals("auto-replied", parsed.loopSignals().autoSubmitted());
        }
    }

    @Nested
    @DisplayName("replies")
    class Replies {

        @Test
        @DisplayName("a reply threads: In-Reply-To, References ending in the original, Re: without stacking, to the From")
        void reply() throws Exception {
            InboundMail original = parse("outlook-desktop-reply.eml");
            var reply = OutboundMail.replyTo(original, EDDI, "Noted, Thursday it is.");

            var rendered = PlainTextMailRenderer.render(reply);
            MimeMessage message = reparse(rendered.mime());

            assertEquals(original.messageId(), message.getHeader("In-Reply-To", null));
            String references = message.getHeader("References", " ");
            assertTrue(references.startsWith("<root-1@example.org>"), references);
            assertTrue(references.strip().endsWith(original.messageId()), references);
            assertEquals("Re: Order 4711 delivery date", message.getSubject());
            assertEquals("bob.builder@example.com", ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0])
                    .getAddress());
            assertEquals("auto-replied", message.getHeader("Auto-Submitted", null));
        }

        @Test
        @DisplayName("Reply-To and Cc of the original are never recipients by default")
        void replyIgnoresReplyTo() throws Exception {
            InboundMail original = parser().parse(MailFixtures.message("""
                    From: Alice <alice@example.com>
                    Reply-To: attacker@example.net
                    Cc: bystander@example.org
                    Message-ID: <q@example.com>
                    Subject: Hi

                    Hello
                    """), MailLimits.defaults(), true);

            var reply = OutboundMail.replyTo(original, EDDI, "Hi Alice");

            assertEquals(List.of("alice@example.com"), reply.to().stream().map(MailAddress::address).toList());
            assertTrue(reply.cc().isEmpty());
        }

        @Test
        @DisplayName("a synthetic id is never written into In-Reply-To")
        void syntheticIdNotThreaded() throws Exception {
            InboundMail original = parse("missing-message-id.eml");

            var reply = OutboundMail.replyTo(original, EDDI, "Hi");

            assertNull(reply.inReplyTo());
            assertTrue(reply.references().isEmpty());
            PlainTextMailRenderer.render(reply);
        }

        @Test
        @DisplayName("a reply to a message with no usable From is refused")
        void noFrom() throws Exception {
            InboundMail original = parser().parse(MailFixtures.message("From: a@example.com, b@example.com\nMessage-ID: <n@example.com>\n\nx"),
                    MailLimits.defaults(), true);

            assertThrows(IllegalArgumentException.class, () -> OutboundMail.replyTo(original, EDDI, "Hi"));
        }

        @Test
        @DisplayName("Re: prefixes in several languages do not stack")
        void replySubject() {
            assertEquals("Re: Order", PlainTextMailRenderer.replySubject("Re: RE: AW: Order"));
            assertEquals("Re: Order", PlainTextMailRenderer.replySubject("Re[2]: Order"));
            assertEquals("Re: Order", PlainTextMailRenderer.replySubject("SV: Antw: Order"));
            assertEquals("Re: Fwd: Order", PlainTextMailRenderer.replySubject("Fwd: Order"));
            assertEquals("Re: Reunion", PlainTextMailRenderer.replySubject("Reunion"));
            assertEquals("Re:", PlainTextMailRenderer.replySubject(null));
        }

        @Test
        @DisplayName("the References chain is capped, keeping the root and the most recent")
        void referencesCapped() throws Exception {
            List<String> references = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                references.add("<r" + i + "@example.com>");
            }
            var mail = new OutboundMail(EDDI, List.of(ALICE), List.of(), "s", "t", null, "<r59@example.com>", references,
                    OutboundMail.Kind.AUTO_REPLIED, null);

            String header = reparse(PlainTextMailRenderer.render(mail).mime()).getHeader("References", " ");
            List<String> ids = MessageIds.parseAll(header, 1000);

            assertEquals(PlainTextMailRenderer.MAX_REFERENCES, ids.size());
            assertEquals("<r0@example.com>", ids.get(0));
            assertEquals("<r59@example.com>", ids.get(ids.size() - 1));
            for (String line : new String(PlainTextMailRenderer.render(mail).mime(), StandardCharsets.US_ASCII).split("\r\n")) {
                assertTrue(line.length() <= 998, "a header line over 998 octets is not RFC 5322");
            }
        }
    }

    @Nested
    @DisplayName("header injection")
    class Injection {

        @ParameterizedTest
        @ValueSource(strings = {"Hello\r\nBcc: victim@example.net", "Hello\nBcc: x@example.net", "Hello\rX: y", "a\u0000b", "a" + (char) 0x2028 + "b",
                "a\u0085b"})
        @DisplayName("a subject with a line break or control is refused")
        void subject(String subject) {
            assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(notification(subject, "body")));
        }

        @Test
        @DisplayName("a display name with a line break is refused")
        void displayName() {
            var mail = new OutboundMail(EDDI, List.of(new MailAddress("alice@example.com", "Alice\r\nBcc: victim@example.net")), List.of(), "s",
                    "t", null, null, List.of(), OutboundMail.Kind.AUTO_GENERATED, null);

            assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(mail));
        }

        @Test
        @DisplayName("an address with a line break or that is not an address is refused")
        void address() {
            for (String address : List.of("alice@example.com\r\nBcc: v@example.net", "alice@example.com>, v@example.net", "not-an-address")) {
                var mail = new OutboundMail(EDDI, List.of(new MailAddress(address, null)), List.of(), "s", "t", null, null, List.of(),
                        OutboundMail.Kind.AUTO_GENERATED, null);
                assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(mail), address);
            }
        }

        @Test
        @DisplayName("a non-ASCII recipient needs SMTPUTF8 and is refused")
        void nonAsciiAddress() {
            var mail = new OutboundMail(EDDI, List.of(new MailAddress("jürgen@example.de", null)), List.of(), "s", "t", null, null, List.of(),
                    OutboundMail.Kind.AUTO_GENERATED, null);

            assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(mail));
        }

        @Test
        @DisplayName("message ids with line breaks or no brackets are refused")
        void messageIds() {
            for (String id : List.of("<a@example.com>\r\nBcc: v@example.net", "a@example.com", "<a b@example.com>")) {
                var asReference = new OutboundMail(EDDI, List.of(ALICE), List.of(), "s", "t", null, null, List.of(id), OutboundMail.Kind.AUTO_REPLIED,
                        null);
                var asInReplyTo = new OutboundMail(EDDI, List.of(ALICE), List.of(), "s", "t", null, id, List.of(), OutboundMail.Kind.AUTO_REPLIED,
                        null);
                var asMessageId = new OutboundMail(EDDI, List.of(ALICE), List.of(), "s", "t", id, null, List.of(), OutboundMail.Kind.AUTO_REPLIED,
                        null);
                assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(asReference), id);
                assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(asInReplyTo), id);
                assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(asMessageId), id);
            }
        }

        @Test
        @DisplayName("no recipient, no sender: refused")
        void missingParties() {
            assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(new OutboundMail(EDDI, List.of(), List.of(), "s", "t",
                    null, null, List.of(), OutboundMail.Kind.AUTO_GENERATED, null)));
            assertThrows(IllegalArgumentException.class, () -> PlainTextMailRenderer.render(new OutboundMail(null, List.of(ALICE), List.of(), "s",
                    "t", null, null, List.of(), OutboundMail.Kind.AUTO_GENERATED, null)));
        }

        @Test
        @DisplayName("a body line that looks like a header stays in the body")
        void bodyCannotInjectHeaders() throws Exception {
            byte[] mime = PlainTextMailRenderer.render(notification("s", "\r\nBcc: victim@example.net\r\n\r\nhello")).mime();

            MimeMessage message = reparse(mime);
            assertNull(message.getHeader("Bcc"));
        }
    }

    @Nested
    @DisplayName("format=flowed")
    class Flowed {

        @Test
        @DisplayName("long lines are soft-wrapped at 78 and reflow to the original")
        void wrapsAndReflows() throws Exception {
            String paragraph = "This is a long paragraph of agent output that goes well beyond the seventy-eight character line "
                    + "limit and must therefore be wrapped with soft line breaks so that it reflows on a phone.";

            byte[] mime = PlainTextMailRenderer.render(notification("s", paragraph + "\n\n-- \nSupport")).mime();
            InboundMail parsed = parser().parse(mime, MailLimits.defaults(), false);

            assertEquals(paragraph + "\n\n-- \nSupport", parsed.text());
        }

        @Test
        @DisplayName("lines starting with '>', 'From ' or a space are space-stuffed and survive")
        void spaceStuffing() {
            String encoded = FormatFlowed.encode(">not a quote\nFrom here\n indented");

            assertEquals(" >not a quote\r\n From here\r\n  indented", encoded);
            assertEquals(">not a quote\nFrom here\n indented", FormatFlowed.decode(encoded, false));
        }

        @Test
        @DisplayName("trailing spaces of hard lines are removed so they do not read as soft breaks")
        void trailingSpacesRemoved() {
            assertEquals("a\r\nb", FormatFlowed.encode("a   \nb"));
        }

        @Test
        @DisplayName("a word longer than a line is not split")
        void longWord() {
            String url = "https://example.org/" + "x".repeat(120);

            assertEquals(url, FormatFlowed.encode(url));
        }

        @Test
        @DisplayName("NUL and other control characters are removed from the body")
        void bodyControls() throws Exception {
            byte[] mime = PlainTextMailRenderer.render(notification("s", "a\u0000b\u0007c")).mime();

            assertEquals("abc", parser().parse(mime, MailLimits.defaults(), false).text());
        }
    }
}
