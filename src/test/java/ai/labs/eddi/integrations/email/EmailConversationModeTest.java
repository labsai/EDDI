/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.mail.SenderAuthenticator;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.Disposition;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.IntegrationContext;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailSuppressionStore;
import ai.labs.eddi.integrations.email.reply.EmailReplyComposer;
import ai.labs.eddi.integrations.email.reply.EmailReplySender;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Conversation mode through the inbound processor and the reply sender, with
 * only the conversation engine and the SMTP relay as fakes: the thread maps to
 * one conversation through EDDI's own Message-IDs, the hijack guard, bounces,
 * and the stranded-turn resubmission.
 */
class EmailConversationModeTest {

    static final String INTEGRATION = EmailInboundProcessorTest.INTEGRATION;
    static final String ALICE = "alice@supplier.com";

    InMemoryEmailMessageLedger ledger;
    InMemoryEmailSuppressionStore suppressions;
    InMemoryEmailMailboxStateStore stateStore;
    IConversationService conversations;
    EmailInboundProcessor processor;
    Map<String, MailAuthentication> verdicts;
    List<String> sayConversations;
    List<InputData> inputs;
    List<ConversationResponseHandler> handlers;
    List<Boolean> currentStepOnly;
    List<Boolean> detailed;
    List<String> sent;
    List<byte[]> sentMime;
    AtomicInteger counter;
    Map<String, List<ConversationOutput>> outputs;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ledger = new InMemoryEmailMessageLedger();
        suppressions = new InMemoryEmailSuppressionStore();
        verdicts = new HashMap<>();
        SenderAuthenticator authenticator = mock(SenderAuthenticator.class);
        when(authenticator.authenticate(any(), any(), any(), any())).thenAnswer(invocation -> {
            InboundMail parsed = invocation.getArgument(1);
            return verdicts.getOrDefault(parsed.fromAddress(), new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "supplier.com"));
        });
        conversations = mock(IConversationService.class);
        counter = new AtomicInteger();
        outputs = new HashMap<>();
        when(conversations.startConversation(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            String id = "conv-" + counter.incrementAndGet();
            List<ConversationOutput> start = new ArrayList<>();
            ConversationOutput greeting = new ConversationOutput();
            greeting.put("output", List.of("Hello from the order desk."));
            start.add(greeting);
            outputs.put(id, start);
            return new ConversationResult(id, URI.create("eddi://c"));
        });
        when(conversations.getConversationState(anyString())).thenReturn(ConversationState.READY);
        sayConversations = new ArrayList<>();
        inputs = new ArrayList<>();
        handlers = new ArrayList<>();
        currentStepOnly = new ArrayList<>();
        detailed = new ArrayList<>();
        doAnswer(invocation -> {
            sayConversations.add(invocation.getArgument(0));
            currentStepOnly.add(invocation.getArgument(2));
            detailed.add(invocation.getArgument(1));
            inputs.add(invocation.getArgument(4));
            handlers.add(invocation.getArgument(6));
            return null;
        }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));
        Instance<IAttachmentStore> attachments = mock(Instance.class);
        stateStore = new InMemoryEmailMailboxStateStore();
        processor = new EmailInboundProcessor(ledger, stateStore, new MimeMessageParser(new HtmlToMarkdownConverter()),
                authenticator, conversations, mock(IGroupConversationService.class), new EmailInboundProcessorTest.FakeUserConversations(),
                new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()), attachments, mock(EmailOwnerNotifier.class),
                new SimpleMeterRegistry(), Duration.ofMinutes(30), Duration.ofDays(30));

        sent = new ArrayList<>();
        sentMime = new ArrayList<>();
        MailTransport transport = new MailTransport() {
            @Override
            public String id() {
                return "imap";
            }

            @Override
            public MailboxSession open(String integrationId, EmailChannelSettings settings) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String send(String integrationId, EmailChannelSettings settings, byte[] mime, String recipient) throws MailSendException {
                sent.add(recipient);
                sentMime.add(mime);
                return "250 ok";
            }
        };
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        var configuration = configuration(Map.of());
        when(directory.find(INTEGRATION))
                .thenReturn(
                        Optional.of(new EmailIntegration(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
        processor.replies = new EmailReplySender(ledger, suppressions, directory, conversations, transport,
                new EmailReplyComposer(new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build())),
                mock(EmailOwnerNotifier.class),
                null, new SimpleMeterRegistry(), Runnable::run, Duration.ofDays(30), 3, Duration.ofMinutes(1));
    }

    static ChannelIntegrationConfiguration configuration(Map<String, String> extra) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "conversation", "smtpHost", "smtp.contoso.com"));
        pc.putAll(extra);
        return EmailInboundProcessorTest.integration(pc);
    }

    IntegrationContext context(Map<String, String> extra) {
        var configuration = configuration(extra);
        return new IntegrationContext(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()), Set.of());
    }

    /**
     * Completes the latest turn the way the engine would: the step carries the
     * turn's context (the mail map) and the agent's answer, and the snapshot handed
     * to the callback goes through the real projection the turn asked for.
     */
    @SuppressWarnings("unchecked")
    void complete(int turn, String answer) {
        String conversationId = sayConversations.get(turn);
        ConversationOutput step = new ConversationOutput();
        step.put("output", List.of(answer));
        step.put("context", new HashMap<>(Map.of("mail", inputs.get(turn).getContext().get("mail").getValue())));
        List<ConversationOutput> all = outputs.computeIfAbsent(conversationId, id -> new ArrayList<>());
        all.add(step);
        ConversationMemorySnapshot memory = new ConversationMemorySnapshot();
        memory.setConversationId(conversationId);
        memory.setConversationState(ConversationState.READY);
        memory.setUserId(EmailUserIdentity.perSender(INTEGRATION, ALICE));
        memory.getConversationOutputs().addAll(all);
        for (int i = 0; i < memory.getConversationOutputs().size(); i++) {
            memory.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
        }
        SimpleConversationMemorySnapshot snapshot = ConversationMemoryUtilities.convertSimpleConversationMemory(memory,
                Boolean.TRUE.equals(detailed.get(turn)), Boolean.TRUE.equals(currentStepOnly.get(turn)));
        handlers.get(turn).onComplete(snapshot);
    }

    LedgerEntry inbound(EmailTestMail mail) {
        return ledger.findByMessage(INTEGRATION, Direction.IN, mail.messageId()).orElseThrow();
    }

    String replyId(int index) {
        return ledger.all().stream().filter(r -> r.kind() == Kind.REPLY && r.state() == State.SENT).toList().get(index).messageId();
    }

    @Test
    @DisplayName("a mail runs a turn with all outputs; its reply goes to the sender, threaded, and the mail's row ends DONE")
    void replyToMail() throws Exception {
        EmailTestMail mail = EmailTestMail.mail().to("invoices@contoso.com").subject("Order 4711");

        assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), Instant.now()));
        assertEquals(List.of(false), currentStepOnly, "conversation mode asks for every output");
        assertEquals(List.of(true), detailed, "and for the detailed projection, the only one that keeps each step's context");
        assertEquals(inbound(mail).idempotencyKey(), inputs.get(0).getIdempotencyKey(), "the turn carries the mail's ledger key");
        complete(0, "It ships Monday.");

        assertEquals(List.of(ALICE), sent);
        MimeMessage reply = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(sentMime.get(0)));
        assertEquals("Re: Order 4711", reply.getSubject());
        assertEquals(mail.messageId(), reply.getHeader("In-Reply-To")[0]);
        assertEquals("auto-replied", reply.getHeader("Auto-Submitted")[0]);
        String text = reply.getContent().toString();
        assertTrue(text.contains("Hello from the order desk.") && text.contains("It ships Monday."), text);
        assertEquals(State.DONE, inbound(mail).state());
    }

    @Test
    @DisplayName("answering EDDI's reply (In-Reply-To only) continues the conversation; a third party with that id starts their own")
    void threadAndHijackGuard() throws Exception {
        EmailTestMail first = EmailTestMail.mail();
        processor.process(context(Map.of()), first.polled(1), Instant.now());
        complete(0, "Which size?");
        String eddiId = replyId(0);

        EmailTestMail answer = EmailTestMail.mail().subject("Re: Invoice 4711").header("In-Reply-To: " + eddiId);
        processor.process(context(Map.of()), answer.polled(2), Instant.now());
        assertEquals("conv-1", sayConversations.get(1), "the same conversation");
        verify(conversations).startConversation(any(), anyString(), eq(EmailUserIdentity.perSender(INTEGRATION, ALICE)), any());

        EmailTestMail hijack = EmailTestMail.mail().from("mallory@supplier.com").header("In-Reply-To: " + eddiId).header("References: " + eddiId);
        processor.process(context(Map.of()), hijack.polled(3), Instant.now());
        assertNotEquals("conv-1", sayConversations.get(2), "Message-IDs are not secrets: a different sender gets their own conversation");
        assertEquals(2, counter.get());
    }

    @Test
    @DisplayName("only the integration's own replies thread a mail: an owner notice's Message-ID does not, even naming a conversation")
    void onlyRepliesThread() throws Exception {
        processor.process(context(Map.of()), EmailTestMail.mail().polled(1), Instant.now());
        Instant now = Instant.now();
        ledger.put(new LedgerEntry("notice-1", INTEGRATION, Kind.NOTICE, Direction.OUT, "<eddi.notice@contoso.com>", "conv-1", "budget",
                "notice:1", ALICE, State.SENT, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));

        processor.process(context(Map.of()), EmailTestMail.mail().header("In-Reply-To: <eddi.notice@contoso.com>").polled(2), now);

        assertNotEquals("conv-1", sayConversations.get(1));
    }

    @Test
    @DisplayName("a long thread: its first sender, and an address a late reply went to, may still continue it")
    void participantsInLongThread() {
        Instant start = Instant.now().minus(Duration.ofDays(2));
        ledger.put(new LedgerEntry("first", INTEGRATION, Kind.TRIGGER, Direction.IN, "<first@supplier.com>", "conv-long", "imap:INBOX:1:0",
                "in:first", ALICE, State.DONE, 1, null, null, null, null, start, start, start.plus(Duration.ofDays(30))));
        for (int i = 1; i <= 600; i++) {
            Instant at = start.plusSeconds(i);
            ledger.put(new LedgerEntry("r-" + i, INTEGRATION, Kind.REPLY, Direction.OUT, "<eddi.r" + i + "@contoso.com>", "conv-long", "r|" + i,
                    "reply:" + i, ALICE, State.SENT, 1, at, null, null, null, at, at, at.plus(Duration.ofDays(30))));
        }
        Instant late = start.plusSeconds(601);
        ledger.put(new LedgerEntry("r-carol", INTEGRATION, Kind.REPLY, Direction.OUT, "<eddi.carol@contoso.com>", "conv-long", "r|carol",
                "reply:carol", "carol@supplier.com", State.SENT, 1, late, null, null, null, late, late, late.plus(Duration.ofDays(30))));
        var any = context(Map.of("honorReplyTo", "true", "threadParticipants", "anyAuthenticatedParticipant")).settings();

        assertTrue(processor.participantOf("conv-long", any, ALICE), "the original sender");
        assertTrue(processor.participantOf("conv-long", any, "carol@supplier.com"), "a reply went to her after 600 rows");
        assertFalse(processor.participantOf("conv-long", any, "dave@supplier.com"), "nobody else");
    }

    @Test
    @DisplayName("anyAuthenticatedParticipant admits an address EDDI's reply went to (honorReplyTo), and still nobody else")
    void participants() throws Exception {
        Map<String, String> extra = Map.of("honorReplyTo", "true", "threadParticipants", "anyAuthenticatedParticipant");
        EmailTestMail first = EmailTestMail.mail().header("Reply-To: carol@supplier.com");
        processor.process(context(extra), first.polled(1), Instant.now());
        complete(0, "Noted.");
        assertEquals(List.of("carol@supplier.com"), sent, "the reply went to the Reply-To");
        String eddiId = replyId(0);

        processor.process(context(extra), EmailTestMail.mail().from("carol@supplier.com").header("In-Reply-To: " + eddiId).polled(2), Instant.now());
        assertEquals("conv-1", sayConversations.get(1));
        processor.process(context(extra), EmailTestMail.mail().from("dave@supplier.com").header("In-Reply-To: " + eddiId).polled(3), Instant.now());
        assertNotEquals("conv-1", sayConversations.get(2));
        processor.process(context(Map.of()), EmailTestMail.mail().from("carol@supplier.com").header("In-Reply-To: " + eddiId).polled(4),
                Instant.now());
        assertNotEquals("conv-1", sayConversations.get(3), "originalSenderOnly admits only the sender who started it");
    }

    @Test
    @DisplayName("an unauthenticated sender never runs a turn in conversation mode, so never gets a reply")
    void unauthenticated() throws Exception {
        verdicts.put("eve@forged.example", new MailAuthentication(MailAuthentication.Verdict.FAIL, null));

        processor.process(context(Map.of()), EmailTestMail.mail().from("eve@forged.example").polled(1), Instant.now());

        assertTrue(inputs.isEmpty());
        assertTrue(sent.isEmpty());
        assertTrue(ledger.all().stream().anyMatch(r -> r.state() == State.REFUSED && r.lastError().startsWith("unauthenticated")));
    }

    @Test
    @DisplayName("a hard bounce of a reply suppresses the address: the next reply to it is not sent")
    void bounceSuppresses() throws Exception {
        processor.process(context(Map.of()), EmailTestMail.mail().polled(1), Instant.now());
        complete(0, "First answer.");
        String eddiId = replyId(0);

        processor.process(context(Map.of()), dsn(eddiId, ALICE, 2), Instant.now());
        assertTrue(suppressions.isSuppressed(INTEGRATION, ALICE));
        assertEquals(1, inputs.size(), "a report never runs a turn");

        processor.process(context(Map.of()), EmailTestMail.mail().polled(3), Instant.now());
        complete(1, "Second answer.");
        assertEquals(1, sent.size(), "never to a suppressed address");
        assertTrue(ledger.all().stream().anyMatch(r -> r.kind() == Kind.REPLY && r.state() == State.SUPPRESSED));
    }

    @Test
    @DisplayName("a reply that cannot be queued still counts the turn's cost, and leaves the row for the stranded sweep")
    void costWhenReplyCannotBeQueued() throws Exception {
        EmailReplySender failing = mock(EmailReplySender.class);
        when(failing.onTurnCompleted(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("ledger unavailable"));
        processor.replies = failing;
        EmailTestMail mail = EmailTestMail.mail();
        processor.process(context(Map.of()), mail.polled(1), Instant.now());

        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationCost(0.4);
        handlers.get(0).onComplete(snapshot);

        assertEquals(0.4, stateStore.read(INTEGRATION).orElseThrow().spentUsd(), 1e-9, "the turn's cost counts against the day");
        assertEquals(State.TURN_SUBMITTED, inbound(mail).state(), "the sweep rebuilds the reply");
    }

    @Test
    @DisplayName("a row handed back with its conversation is run in that conversation, without starting another")
    void resubmission() throws Exception {
        EmailTestMail mail = EmailTestMail.mail();
        Instant now = Instant.now();
        ledger.put(new LedgerEntry("row-r", INTEGRATION, Kind.TRIGGER, Direction.IN, mail.messageId(), "conv-7", "imap:INBOX:1:1",
                EmailInboundProcessor.inboundKey(INTEGRATION, mail.messageId()), ALICE, State.RECEIVED, 1, now, null, null,
                EmailInboundSweeper.RESUBMIT, now, now, now.plus(Duration.ofDays(30))));
        assertEquals(1, processor.pendingResubmissions(INTEGRATION, now.plusSeconds(1)).size());

        assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), now.plusSeconds(1)));

        assertEquals(List.of("conv-7"), sayConversations);
        verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
        assertEquals(State.TURN_SUBMITTED, inbound(mail).state());
        assertTrue(processor.pendingResubmissions(INTEGRATION, now.plusSeconds(2)).isEmpty(), "once");
    }

    @Test
    @DisplayName("older due resubmissions of a deleted integration never crowd out this integration's")
    void resubmissionScopedToIntegration() {
        Instant now = Instant.now();
        for (int i = 0; i < EmailInboundSweeper.BATCH + 10; i++) {
            Instant old = now.minus(Duration.ofDays(1)).plusSeconds(i);
            ledger.put(new LedgerEntry("orphan-" + i, "deleted-integration", Kind.TRIGGER, Direction.IN, "<orphan." + i + "@supplier.com>",
                    "conv-o" + i, "imap:INBOX:1:" + i, "in:orphan:" + i, ALICE, State.RECEIVED, 1, old, null, null, EmailInboundSweeper.RESUBMIT,
                    old, old, old.plus(Duration.ofDays(30))));
        }
        ledger.put(new LedgerEntry("row-mine", INTEGRATION, Kind.TRIGGER, Direction.IN, "<mine@supplier.com>", "conv-7", "imap:INBOX:1:1",
                "in:mine", ALICE, State.RECEIVED, 1, now, null, null, EmailInboundSweeper.RESUBMIT, now, now, now.plus(Duration.ofDays(30))));

        assertEquals(List.of("row-mine"), processor.pendingResubmissions(INTEGRATION, now.plusSeconds(1)).stream().map(LedgerEntry::id).toList());
    }

    @Test
    @DisplayName("trigger mode never touches the reply sender, and asks only for the current step")
    void triggerModeSendsNothing() throws Exception {
        EmailReplySender replies = mock(EmailReplySender.class);
        processor.replies = replies;
        var configuration = EmailInboundProcessorTest.integration(Map.of());
        var trigger = new IntegrationContext(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()), Set.of());

        processor.process(trigger, EmailTestMail.mail().polled(1), Instant.now());
        complete(0, "an answer that must not be mailed");

        assertEquals(List.of(true), currentStepOnly);
        verifyNoInteractions(replies);
        assertTrue(sent.isEmpty());
    }

    static EmailTestMail.Polled dsn(String originalMessageId, String recipient, long uid) {
        String body = """
                --DSN
                Content-Type: text/plain; charset=us-ascii

                Your message could not be delivered.

                --DSN
                Content-Type: message/delivery-status

                Reporting-MTA: dns; mx.supplier.com

                Final-Recipient: rfc822; %s
                Action: failed
                Status: 5.1.1

                --DSN
                Content-Type: text/rfc822-headers

                From: invoices@contoso.com
                To: %s
                Message-ID: %s

                --DSN--""".formatted(recipient, recipient, originalMessageId).replace("\n", "\r\n");
        String messageId = "<dsn." + System.nanoTime() + "@mx.supplier.com>";
        byte[] raw = ("From: MAILER-DAEMON@mx.supplier.com\r\nTo: invoices@contoso.com\r\nSubject: Undelivered Mail\r\n" + "Message-ID: " + messageId
                + "\r\nMIME-Version: 1.0\r\n" + "Content-Type: multipart/report; report-type=delivery-status; boundary=\"DSN\"\r\n\r\n" + body
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        return new EmailTestMail.Polled(uid, raw, messageId, Instant.now(), false);
    }
}
