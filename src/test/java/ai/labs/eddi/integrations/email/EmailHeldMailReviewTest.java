/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.integrations.email.transport.MailboxException;
import ai.labs.eddi.integrations.email.transport.UidFolderSession;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The live preview of held mail (v3 plan §7.2): re-read by Message-ID, shown
 * only when it hashes as the held mail, the text cut at 4,000 characters, never
 * stored.
 */
class EmailHeldMailReviewTest {

    private final InMemoryEmailMessageLedger ledger = new InMemoryEmailMessageLedger();
    private final List<String> asked = new ArrayList<>();
    private MailTransport.FoundMessage answer;

    private final MailTransport transport = new MailTransport() {
        @Override
        public String id() {
            return "imap";
        }

        @Override
        public MailboxSession open(String integrationId, EmailChannelSettings settings) {
            return new UidFolderSession() {
                @Override
                public long uidValidity() {
                    return 1;
                }

                @Override
                public long uidNext() {
                    return 1;
                }

                @Override
                public List<MailboxMessage> messagesAfter(long lastUid, int max) {
                    return List.of();
                }

                @Override
                public Optional<FoundMessage> findByMessageId(String messageId, EmailChannelSettings s, long maxBytes, String preferredSourceRef,
                                                              String expectedSha256)
                        throws MailboxException {
                    asked.add(messageId + "|" + expectedSha256);
                    return Optional.ofNullable(answer);
                }

                @Override
                public MarkResult markProcessed(MailboxMessage message, EmailChannelSettings s) {
                    return MarkResult.NOTHING;
                }

                @Override
                public void close() {
                    // nothing
                }
            };
        }
    };

    private final EmailHeldMailReview review = new EmailHeldMailReview(ledger, transport, new MimeMessageParser(new HtmlToMarkdownConverter()),
            mock(EmailSenderListAudit.class));

    private LedgerEntry held(byte[] raw, String messageId) {
        Instant now = Instant.now();
        String hash = new MailTransport.FoundMessage(raw, now, "x").sha256();
        LedgerEntry row = new LedgerEntry("row-1", EmailInboundProcessorTest.INTEGRATION, Kind.TRIGGER, Direction.IN, messageId, null,
                "imap:INBOX:1:1", "in:1", "stranger@partner.com", State.HELD, 0, now.plus(Duration.ofDays(7)), null, null, "held:not_allowlisted",
                now,
                now, now.plus(Duration.ofDays(30)), null, hash, null, "aligned-pass", "held:not_allowlisted");
        ledger.put(row);
        return row;
    }

    @Test
    @DisplayName("the preview is the held mail re-read by its Message-ID and hash: subject, attachments, the text cut at 4,000 characters")
    void preview() throws Exception {
        EmailTestMail mail = EmailTestMail.mail().from("stranger@partner.com").subject("Quote").body("x".repeat(5000))
                .attachment("offer.pdf", "application/pdf", "%PDF-1.4");
        byte[] raw = mail.bytes();
        LedgerEntry row = held(raw, mail.messageId());
        answer = new MailTransport.FoundMessage(raw, Instant.now(), "imap:EDDI/Held:1:1");

        var preview = review.preview(EmailInboundProcessorTest.INTEGRATION, EmailInboundProcessorTest.integration(Map.of()), row);

        assertEquals("Quote", preview.subject());
        assertEquals(List.of("offer.pdf"), preview.attachments());
        assertEquals(EmailHeldMailReview.PREVIEW_CHARS, preview.text().length());
        assertTrue(preview.textTruncated());
        assertEquals(List.of(mail.messageId() + "|" + row.contentHash()), asked, "asked for exactly the held mail");
        assertTrue(preview.item().runAndAllowOffered());
    }

    @Test
    @DisplayName("another mail carrying the Message-ID, or none at all, is never shown")
    void mismatchIsRefused() {
        EmailTestMail mail = EmailTestMail.mail().from("stranger@partner.com");
        LedgerEntry row = held(mail.bytes(), mail.messageId());
        answer = new MailTransport.FoundMessage(EmailTestMail.mail().body("Something else").bytes(), Instant.now(), "imap:INBOX:1:2");
        var config = EmailInboundProcessorTest.integration(Map.of());

        assertThrows(EmailHeldMailReview.PreviewUnavailable.class, () -> review.preview(EmailInboundProcessorTest.INTEGRATION, config, row));
        answer = null;
        assertThrows(EmailHeldMailReview.PreviewUnavailable.class, () -> review.preview(EmailInboundProcessorTest.INTEGRATION, config, row));
    }

    @Test
    @DisplayName("heldRow: only a HELD trigger row of the integration named")
    void heldRow() {
        LedgerEntry row = held(EmailTestMail.mail().bytes(), "<m@x>");
        assertTrue(review.heldRow(EmailInboundProcessorTest.INTEGRATION, row.id()).isPresent());
        assertTrue(review.heldRow("ffffffffffffffffffffffff", row.id()).isEmpty());
        ledger.transitionReview(row.id(), State.HELD, State.REFUSED, "discarded:not_allowlisted", null, null, null, "review_discarded",
                Instant.now());
        assertTrue(review.heldRow(EmailInboundProcessorTest.INTEGRATION, row.id()).isEmpty());
    }
}
