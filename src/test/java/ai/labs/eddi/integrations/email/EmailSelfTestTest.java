/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailSelfTestResult;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.mail.DmarcAlignment;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MailDnsResolver;
import ai.labs.eddi.engine.mail.MailLimits;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.mail.SenderAuthenticator;
import ai.labs.eddi.engine.mail.SmimeSigner;
import ai.labs.eddi.engine.mail.SmimeTestCertificates;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.Disposition;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.IntegrationContext;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.integrations.email.reply.EmailReplySender;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sending-health self-test (email channel v3 plan §6.5): its mail comes
 * back to the mailbox, is recognised before the own-address refusal, never
 * starts a turn, and its token works once.
 */
class EmailSelfTestTest {

    static final String INTEGRATION = "a1b2c3d4e5f60718293a4b5c";
    static final String ADDRESS = "invoices@contoso.com";

    InMemoryEmailMessageLedger ledger;
    RecordingTransport transport;
    DmarcAlignment dmarc;
    EmailSelfTest selfTest;
    Instant now;

    /** Captures the self-test mail instead of sending it. */
    static final class RecordingTransport implements MailTransport {
        final List<byte[]> sent = new ArrayList<>();
        MailSendException failure;

        @Override
        public String id() {
            return "imap";
        }

        @Override
        public MailboxSession open(String integrationId, EmailChannelSettings settings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Sent sendSelfTest(String integrationId, EmailChannelSettings settings, byte[] mime) throws MailSendException {
            if (failure != null) {
                throw failure;
            }
            sent.add(mime);
            return new Sent("250 ok", false);
        }
    }

    static ChannelIntegrationConfiguration integration(Map<String, String> extra) {
        return EmailInboundProcessorTest.integration(extra);
    }

    static EmailChannelSettings settings(Map<String, String> extra) {
        return EmailChannelSettings.from(integration(extra));
    }

    static final Map<String, String> WITH_SMTP = Map.of("smtpHost", "smtp.contoso.com");

    @BeforeEach
    void setUp() {
        ledger = new InMemoryEmailMessageLedger();
        transport = new RecordingTransport();
        dmarc = mock(DmarcAlignment.class);
        when(dmarc.policy(anyString(), any())).thenReturn(new DmarcAlignment.Policy(DmarcAlignment.Mode.RELAXED, "contoso.com", false));
        now = Instant.now();
        selfTest = new EmailSelfTest(ledger, transport, dmarc, mock(MailDnsResolver.class), new SimpleMeterRegistry(), Duration.ofDays(30),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("starting")
    class Starting {

        @Test
        @DisplayName("sends one mail to the mailbox itself, with the token, and records an open SELF_TEST row")
        void sends() {
            EmailSelfTestResult result = selfTest.start(INTEGRATION, settings(WITH_SMTP));
            assertEquals(EmailSelfTestResult.WAITING, result.status());
            assertNotNull(result.token());
            assertEquals(1, transport.sent.size());
            String mime = new String(transport.sent.get(0), StandardCharsets.UTF_8);
            assertTrue(mime.contains(EmailSelfTest.TOKEN_HEADER + ": " + result.token()), mime);
            var row = ledger.findByIdempotencyKey("self-test:" + EmailSelfTest.hash(result.token())).orElseThrow();
            assertEquals(Kind.SELF_TEST, row.kind());
            assertEquals(State.SENT, row.state());
            assertEquals(ADDRESS, row.recipient());
            assertFalse(row.sourceRef().contains(result.token()), "only the hash is stored");
        }

        @Test
        @DisplayName("needs an SMTP relay")
        void needsSmtp() {
            var e = assertThrows(EmailSelfTest.Refused.class, () -> selfTest.start(INTEGRATION, settings(Map.of())));
            assertFalse(e.rateLimited());
            assertTrue(transport.sent.isEmpty());
        }

        @Test
        @DisplayName("at most five per integration per hour")
        void rateLimited() {
            for (int i = 0; i < EmailSelfTest.MAX_PER_HOUR; i++) {
                selfTest.start(INTEGRATION, settings(WITH_SMTP));
            }
            var e = assertThrows(EmailSelfTest.Refused.class, () -> selfTest.start(INTEGRATION, settings(WITH_SMTP)));
            assertTrue(e.rateLimited());
            assertEquals(EmailSelfTest.MAX_PER_HOUR, transport.sent.size());
        }

        @Test
        @DisplayName("the limit is per integration and cluster-wide: a second node sharing the ledger gets no extra slot")
        void rateLimitIsPerIntegrationAcrossNodes() {
            var otherNode = new EmailSelfTest(ledger, transport, dmarc, mock(MailDnsResolver.class), new SimpleMeterRegistry(), Duration.ofDays(30),
                    Clock.fixed(now, ZoneOffset.UTC));
            for (int i = 0; i < EmailSelfTest.MAX_PER_HOUR; i++) {
                (i % 2 == 0 ? selfTest : otherNode).start(INTEGRATION, settings(WITH_SMTP));
            }
            assertThrows(EmailSelfTest.Refused.class, () -> otherNode.start(INTEGRATION, settings(WITH_SMTP)));
            assertThrows(EmailSelfTest.Refused.class, () -> selfTest.start(INTEGRATION, settings(WITH_SMTP)));
            // Another integration on the same address has its own slots.
            assertEquals(EmailSelfTestResult.WAITING, selfTest.start("another-integration", settings(WITH_SMTP)).status());
            // The next hour has new ones.
            var nextHour = new EmailSelfTest(ledger, transport, dmarc, mock(MailDnsResolver.class), new SimpleMeterRegistry(), Duration.ofDays(30),
                    Clock.fixed(now.plus(Duration.ofHours(1)), ZoneOffset.UTC));
            assertEquals(EmailSelfTestResult.WAITING, nextHour.start(INTEGRATION, settings(WITH_SMTP)).status());
        }

        @Test
        @DisplayName("a slot is claimed atomically: the last one goes to exactly one of two racing starts")
        void lastSlotGoesToOne() throws Exception {
            for (int i = 0; i < EmailSelfTest.MAX_PER_HOUR - 1; i++) {
                assertTrue(selfTest.claimSlot(INTEGRATION, now));
            }
            var pool = Executors.newFixedThreadPool(8);
            try {
                var gate = new CountDownLatch(1);
                List<Future<Boolean>> claims = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    claims.add(pool.submit(() -> {
                        gate.await();
                        return selfTest.claimSlot(INTEGRATION, now);
                    }));
                }
                gate.countDown();
                int won = 0;
                for (var claim : claims) {
                    won += claim.get() ? 1 : 0;
                }
                assertEquals(1, won);
            } finally {
                pool.shutdownNow();
            }
            var slot = ledger.findByIdempotencyKey(EmailSelfTest.SLOT_PREFIX + INTEGRATION + ":"
                    + Math.floorDiv(now.getEpochSecond(), 3600) + ":0").orElseThrow();
            assertEquals(State.DONE, slot.state(), "terminal");
            assertNull(slot.recipient(), "no address");
        }

        @Test
        @DisplayName("a relay failure is reported, not thrown")
        void sendFailure() {
            transport.failure = new MailSendException(MailSendException.Category.AUTHENTICATION, false, "The relay rejected the SMTP credential.",
                    null);
            var result = selfTest.start(INTEGRATION, settings(WITH_SMTP));
            assertEquals(EmailSelfTestResult.SEND_FAILED, result.status());
            assertTrue(result.findings().get(0).text().contains("rejected the SMTP credential"));
        }
    }

    @Nested
    @DisplayName("the mail coming back through the processor")
    class ThroughTheProcessor {

        IConversationService conversations;
        EmailInboundProcessor processor;
        MailAuthentication verdict;
        SimpleMeterRegistry inboundMeters;

        double inbound(String outcome) {
            var counter = inboundMeters.find(EmailMetrics.INBOUND).tag("outcome", outcome).counter();
            return counter == null ? 0 : counter.count();
        }

        @BeforeEach
        @SuppressWarnings("unchecked")
        void processor() {
            conversations = mock(IConversationService.class);
            SenderAuthenticator authenticator = mock(SenderAuthenticator.class);
            verdict = new MailAuthentication(MailAuthentication.Verdict.PASS_UNALIGNED, "contoso-com.20230601.gappssmtp.com");
            when(authenticator.authenticate(any(), any(), any(), any())).thenAnswer(invocation -> verdict);
            inboundMeters = new SimpleMeterRegistry();
            processor = new EmailInboundProcessor(ledger, new InMemoryEmailMailboxStateStore(), new MimeMessageParser(new HtmlToMarkdownConverter()),
                    authenticator, conversations, mock(IGroupConversationService.class), new EmailInboundProcessorTest.FakeUserConversations(),
                    new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()), mock(Instance.class),
                    mock(EmailOwnerNotifier.class), mock(EmailReplySender.class), mock(Instance.class), mock(GdprPseudonymizer.class),
                    inboundMeters, Duration.ofMinutes(30), Duration.ofDays(30));
            Instance<EmailSelfTest> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(true);
            when(instance.get()).thenReturn(selfTest);
            processor.selfTest = instance;
        }

        IntegrationContext context() {
            var configuration = integration(WITH_SMTP);
            return new IntegrationContext(INTEGRATION, configuration, EmailChannelSettings.from(configuration), Set.of());
        }

        EmailTestMail.Polled polled(byte[] raw, long uid) {
            String messageId;
            try {
                messageId = new MimeMessage(null, new ByteArrayInputStream(raw)).getMessageID();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new EmailTestMail.Polled(uid, raw, messageId, now, false);
        }

        @Test
        @DisplayName("is recorded and settled as self_test, and never starts a turn")
        void recognisedNeverATurn() {
            var started = selfTest.start(INTEGRATION, settings(WITH_SMTP));
            byte[] mail = transport.sent.get(0);

            assertEquals(Disposition.SETTLED, processor.process(context(), polled(mail, 1), now.plusSeconds(60)));

            verifyNoInteractions(conversations);
            var inbound = ledger.findByIdempotencyKey(EmailInboundProcessor.inboundKey(INTEGRATION,
                    ledger.findByIdempotencyKey("self-test:" + EmailSelfTest.hash(started.token())).orElseThrow().messageId())).orElseThrow();
            assertEquals(Direction.IN, inbound.direction());
            assertEquals(State.REFUSED, inbound.state());
            assertEquals(EmailSelfTest.SETTLED_REASON, inbound.lastError());

            var result = selfTest.read(INTEGRATION, started.token()).orElseThrow();
            assertEquals(EmailSelfTestResult.DONE, result.status());
            assertEquals("pass-unaligned", result.checks().dkim());
            assertEquals("contoso-com.20230601.gappssmtp.com", result.checks().signingDomain());
            assertFalse(result.checks().aligned());
            assertTrue(result.findings().stream().anyMatch(f -> f.code().equals("dkim.unaligned")
                    && f.text().contains("signs as contoso-com.20230601.gappssmtp.com, not contoso.com")));
            assertTrue(result.findings().stream().anyMatch(f -> f.code().equals("dmarc.present")));
            assertNull(result.token(), "the token is shown only when the test starts");
            // Settled by the self-test branch itself, not by the loop guard behind it.
            assertEquals(1.0, inbound(EmailMetrics.SELF_TEST));
            assertEquals(0.0, inbound(EmailMetrics.LOOP));
        }

        @Test
        @DisplayName("a temporary DNS failure does not defer the test mail past its 15 minutes: it is recorded at once")
        void temporaryVerdictStillRecorded() {
            verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null, "dns lookup failed", true, List.of());
            var started = selfTest.start(INTEGRATION, settings(WITH_SMTP));

            assertEquals(Disposition.SETTLED, processor.process(context(), polled(transport.sent.get(0), 1), now.plusSeconds(60)));

            verifyNoInteractions(conversations);
            var result = selfTest.read(INTEGRATION, started.token()).orElseThrow();
            assertEquals(EmailSelfTestResult.DONE, result.status());
            assertTrue(result.checks().dnsTemporary());
            assertEquals("dkim.dns-temporary", result.findings().get(0).code());
        }

        @Test
        @DisplayName("a replayed token does nothing: the second mail is refused like any own-address mail")
        void replayRefused() {
            var started = selfTest.start(INTEGRATION, settings(WITH_SMTP));
            assertEquals(Disposition.SETTLED, processor.process(context(), polled(transport.sent.get(0), 1), now.plusSeconds(60)));

            EmailTestMail replay = EmailTestMail.mail().from(ADDRESS).to(ADDRESS).header(EmailSelfTest.TOKEN_HEADER + ": " + started.token());
            assertEquals(Disposition.SETTLED, processor.process(context(), replay.polled(2), now.plusSeconds(120)));

            verifyNoInteractions(conversations);
            var row = ledger.findByMessage(INTEGRATION, Direction.IN, replay.messageId()).orElseThrow();
            assertEquals(State.REFUSED, row.state());
            assertEquals("loop:own_address", row.lastError());
        }

        @Test
        @DisplayName("an expired token is not recognised")
        void expired() {
            var started = selfTest.start(INTEGRATION, settings(WITH_SMTP));
            assertEquals(Disposition.SETTLED,
                    processor.process(context(), polled(transport.sent.get(0), 1), now.plus(EmailSelfTest.VALIDITY).plusSeconds(1)));
            verifyNoInteractions(conversations);
            var test = ledger.findByIdempotencyKey("self-test:" + EmailSelfTest.hash(started.token())).orElseThrow();
            assertEquals(State.SENT, test.state(), "not closed by a late mail");
            var inbound = ledger.findByMessage(INTEGRATION, Direction.IN, test.messageId()).orElseThrow();
            assertTrue(inbound.lastError().startsWith("loop:"), inbound.lastError());
            var later = new EmailSelfTest(ledger, transport, dmarc, mock(MailDnsResolver.class), new SimpleMeterRegistry(), Duration.ofDays(30),
                    Clock.fixed(now.plus(EmailSelfTest.VALIDITY).plusSeconds(1), ZoneOffset.UTC));
            assertEquals(EmailSelfTestResult.EXPIRED, later.read(INTEGRATION, started.token()).orElseThrow().status());
        }

        @Test
        @DisplayName("the token of another integration is not recognised")
        void otherIntegration() throws Exception {
            var started = selfTest.start("another-integration", settings(WITH_SMTP));
            assertFalse(selfTest.recognize(INTEGRATION, settings(WITH_SMTP),
                    new MimeMessageParser(new HtmlToMarkdownConverter()).parse(transport.sent.get(0), MailLimits.defaults(), true),
                    transport.sent.get(0), verdict, now));
            assertTrue(selfTest.read(INTEGRATION, started.token()).isEmpty(), "and cannot be read through it");
        }

        @Test
        @DisplayName("the right token with another Message-ID is not recognised, and the test stays open")
        void otherMessageIdRefused() throws Exception {
            var started = selfTest.start(INTEGRATION, settings(WITH_SMTP));
            String sent = new String(transport.sent.get(0), StandardCharsets.ISO_8859_1);
            String original = new MimeMessage(null, new ByteArrayInputStream(transport.sent.get(0))).getMessageID();
            byte[] forged = sent.replace(original, "<forged.1@contoso.com>").getBytes(StandardCharsets.ISO_8859_1);
            var parser = new MimeMessageParser(new HtmlToMarkdownConverter());
            assertFalse(selfTest.recognize(INTEGRATION, settings(WITH_SMTP), parser.parse(forged, MailLimits.defaults(), true), forged, verdict,
                    now.plusSeconds(30)));
            assertEquals(EmailSelfTestResult.WAITING, selfTest.read(INTEGRATION, started.token()).orElseThrow().status());
            // The genuine mail still closes it.
            assertTrue(selfTest.recognize(INTEGRATION, settings(WITH_SMTP), parser.parse(transport.sent.get(0), MailLimits.defaults(), true),
                    transport.sent.get(0), verdict, now.plusSeconds(40)));
        }

        @Test
        @DisplayName("a token in the body only is not recognised, even under the right Message-ID")
        void bodyTokenRefused() throws Exception {
            var started = selfTest.start(INTEGRATION, settings(WITH_SMTP));
            String sent = new String(transport.sent.get(0), StandardCharsets.ISO_8859_1);
            assertFalse(sent.contains("token: " + started.token()), "the body does not carry the token");
            String headerLine = EmailSelfTest.TOKEN_HEADER + ": " + started.token() + "\r\n";
            assertTrue(sent.contains(headerLine), sent);
            byte[] bodyOnly = (sent.replace(headerLine, "") + "Self-test token: " + started.token() + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
            var parsed = new MimeMessageParser(new HtmlToMarkdownConverter()).parse(bodyOnly, MailLimits.defaults(), true);
            assertTrue(parsed.text().contains(started.token()), "the token is in the parsed body");
            assertFalse(selfTest.recognize(INTEGRATION, settings(WITH_SMTP), parsed, bodyOnly, verdict, now.plusSeconds(30)));
            assertEquals(EmailSelfTestResult.WAITING, selfTest.read(INTEGRATION, started.token()).orElseThrow().status());
        }

        @Test
        @DisplayName("a token in a mail from someone else is not recognised")
        void foreignSender() throws Exception {
            var started = selfTest.start(INTEGRATION, settings(WITH_SMTP));
            EmailTestMail forged = EmailTestMail.mail().from("mallory@attacker.example").header(EmailSelfTest.TOKEN_HEADER + ": " + started.token());
            processor.process(context(), forged.polled(3), now.plusSeconds(30));
            assertEquals(EmailSelfTestResult.WAITING, selfTest.read(INTEGRATION, started.token()).orElseThrow().status());
            verify(conversations, never()).say(anyString(), any(), any(), any(), any(), any(Boolean.class), any());
        }
    }

    @Nested
    @DisplayName("what the result says")
    class Findings {

        static EmailSelfTestResult.Checks checks(String dkim, boolean m365, String smime) {
            return new EmailSelfTestResult.Checks(dkim, "contoso.com", "aligned-pass".equals(dkim), false, smime, false, "absent", null, m365,
                    "contoso.com");
        }

        @Test
        @DisplayName("Microsoft 365 without a signature is inconclusive")
        void microsoftInconclusive() {
            var findings = EmailSelfTestResult.findings(checks("none", true, "not_signed"));
            assertEquals("inconclusive", findings.get(0).level());
            assertEquals("dkim.inconclusive", findings.get(0).code());
        }

        @Test
        @DisplayName("elsewhere a missing signature is a problem")
        void missingElsewhere() {
            assertEquals("dkim.missing", EmailSelfTestResult.findings(checks("none", false, "not_signed")).get(0).code());
        }

        @Test
        @DisplayName("an aligned pass and a verified S/MIME signature are ok; a missing DMARC record is advice")
        void allGood() {
            var findings = EmailSelfTestResult.findings(checks("aligned-pass", false, "verified"));
            assertEquals(List.of("dkim.aligned", "smime.verified", "dmarc.absent"),
                    findings.stream().map(EmailSelfTestResult.Finding::code).toList());
            assertEquals("warning", findings.get(2).level());
        }

        @Test
        @DisplayName("the result survives its ledger encoding")
        void encoding() {
            var checks = new EmailSelfTestResult.Checks("pass-unaligned", "x.gappssmtp.com", false, true, "wrong_signer", true, "present",
                    "contoso.com", false, "contoso.com");
            assertEquals(checks, EmailSelfTest.decode(EmailSelfTest.encode(checks)));
        }

        @Test
        @DisplayName("an S/MIME-signed test mail is reported verified for the mailbox's own certificate")
        void smimeVerified() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            byte[] mail = EmailSelfTest.render(settings(WITH_SMTP), "<eddi.1@contoso.com>", EmailSelfTest.newToken(), now);
            byte[] signed = SmimeSigner.sign(mail, issued.identity());
            var checks = selfTest.evaluate(settings(WITH_SMTP), signed,
                    new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "contoso.com"), now);
            // The test CA is in no public trust store: verified, but not trusted.
            assertEquals("verified_untrusted_root", checks.smime());
            assertTrue(EmailSelfTestResult.findings(checks).stream()
                    .anyMatch(f -> f.code().equals("smime.untrusted-root") && f.level().equals("warning")));
            var other = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid("ceo@contoso.com"));
            assertEquals("wrong_signer", selfTest.evaluate(settings(WITH_SMTP), SmimeSigner.sign(mail, other.identity()), null, now).smime());
        }

        @Test
        @DisplayName("a verified signature over leaf and intermediate without the root is untrusted-root; a broken chain is a problem")
        void smimeChainFoldedIn() throws Exception {
            byte[] mail = EmailSelfTest.render(settings(WITH_SMTP), "<eddi.2@contoso.com>", EmailSelfTest.newToken(), now);
            var bundle = SmimeTestCertificates.issueUnderIntermediate(SmimeTestCertificates.Leaf.valid(ADDRESS));
            assertEquals("verified_untrusted_root",
                    selfTest.evaluate(settings(WITH_SMTP), SmimeSigner.sign(mail, bundle.identity()), null, now).smime());
            var forged = SmimeTestCertificates.issueForgedUnderIntermediate(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var checks = selfTest.evaluate(settings(WITH_SMTP), SmimeSigner.sign(mail, forged.identity()), null, now);
            assertEquals("chain_broken", checks.smime());
            assertTrue(EmailSelfTestResult.findings(checks).stream()
                    .anyMatch(f -> f.code().equals("smime.chain-broken") && f.level().equals("problem")));
        }
    }
}
