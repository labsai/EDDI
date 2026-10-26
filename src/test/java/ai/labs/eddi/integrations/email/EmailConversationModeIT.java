/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.mail.SenderAuthenticator;
import ai.labs.eddi.engine.mail.TestTls;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SafeSocketTestSupport;
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
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.mockito.Mockito.when;

/**
 * Conversation mode end to end against GreenMail — IMAPS in, SMTPS out, both on
 * loopback through {@link SafeSocketTestSupport} with TLS verified: mail in,
 * turn, reply delivered with the threading and {@code Auto-Submitted} headers,
 * the sender's answer to EDDI's Message-ID continuing the conversation, a third
 * party with that id getting their own, a bounce suppressing the address, a
 * crash between the turn and the send rebuilt into exactly one reply, and a
 * mail whose turn never ran found again in the mailbox and run once. Only the
 * conversation engine is a fake.
 */
class EmailConversationModeIT {

    private static final String ID = "a1b2c3d4e5f60718293a4b5c";
    private static final String MAILBOX = "support@contoso.com";
    private static final String ALICE = "alice@supplier.com";
    private static final String PASSWORD = "fixturefixture";

    private static GreenMail greenMail;

    private GreenMailUser user;
    private InMemoryEmailMessageLedger ledger;
    private InMemoryEmailSuppressionStore suppressions;
    private IConversationService conversations;
    private EmailMailboxPoller poller;
    private EmailInboundSweeper sweeper;
    private final List<String> sayConversations = new ArrayList<>();
    private final List<InputData> inputs = new ArrayList<>();
    private final List<ConversationResponseHandler> handlers = new ArrayList<>();
    /** What each say asked for: returnDetailed, returnCurrentStepOnly. */
    private final List<Boolean[]> projections = new ArrayList<>();
    private final Map<String, List<ConversationOutput>> stored = new HashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    @BeforeAll
    static void startGreenMail() throws Exception {
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY, TestTls.keystore().toString());
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_PASSWORD_PROPERTY, TestTls.PASSWORD);
        greenMail = new GreenMail(new ServerSetup[]{new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS),
                new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)});
        greenMail.start();
    }

    @AfterAll
    static void stopGreenMail() {
        greenMail.stop();
        System.clearProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY);
        System.clearProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_PASSWORD_PROPERTY);
    }

    @AfterEach
    void purge() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        user = greenMail.setUser(MAILBOX, MAILBOX, PASSWORD);
        ledger = new InMemoryEmailMessageLedger();
        suppressions = new InMemoryEmailSuppressionStore();
        InMemoryEmailMailboxStateStore state = new InMemoryEmailMailboxStateStore();

        conversations = mock(IConversationService.class);
        when(conversations.startConversation(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            String id = "conv-" + counter.incrementAndGet();
            stored.put(id, new ArrayList<>());
            return new ConversationResult(id, URI.create("eddi://c"));
        });
        when(conversations.getConversationState(anyString())).thenReturn(ConversationState.READY);
        when(conversations.readConversation(anyString(), any(), any(), any()))
                .thenAnswer(invocation -> snapshot(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        doAnswer(invocation -> {
            sayConversations.add(invocation.getArgument(0));
            projections.add(new Boolean[]{invocation.getArgument(1), invocation.getArgument(2)});
            inputs.add(invocation.getArgument(4));
            handlers.add(invocation.getArgument(6));
            return null;
        }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        SenderAuthenticator authenticator = mock(SenderAuthenticator.class);
        when(authenticator.authenticate(any(), any(), any(), any()))
                .thenReturn(new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "supplier.com"));
        EmailInboundProcessor processor = new EmailInboundProcessor(ledger, state, new MimeMessageParser(new HtmlToMarkdownConverter()),
                authenticator, conversations, mock(IGroupConversationService.class), new EmailInboundProcessorTest.FakeUserConversations(),
                new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()), mock(Instance.class),
                mock(EmailOwnerNotifier.class),
                new SimpleMeterRegistry(), Duration.ofMinutes(30), Duration.ofDays(30));

        ConnectionResolver resolver = mock(ConnectionResolver.class);
        when(resolver.authTypeOf("${connection:mailbox}")).thenReturn(Optional.of(AuthType.BASIC));
        when(resolver.resolveBasicLogin(eq("${connection:mailbox}"), any())).thenReturn(new ConnectionResolver.BasicLogin(MAILBOX, PASSWORD));
        Instance<ConnectionResolver> resolverInstance = mock(Instance.class);
        when(resolverInstance.isResolvable()).thenReturn(true);
        when(resolverInstance.get()).thenReturn(resolver);
        int imapPort = greenMail.getImaps().getPort();
        int smtpPort = greenMail.getSmtps().getPort();
        var transport = new ImapMailTransport(new SafeSocketConnector(Optional.empty()),
                (host, ignored) -> SafeSocketTestSupport.loopback("localhost", imapPort, SafeSocketConnector.Protocol.IMAP,
                        SafeSocketConnector.TlsMode.IMPLICIT),
                (host, ignored) -> SafeSocketTestSupport.loopback("localhost", smtpPort, SafeSocketConnector.Protocol.SMTP,
                        SafeSocketConnector.TlsMode.IMPLICIT),
                resolverInstance);

        var configuration = EmailInboundProcessorTest.integration(Map.of("mode", "conversation", "smtpHost", "smtp.contoso.com", "smtpPort", "465",
                "host", "imap.contoso.com", "address", MAILBOX));
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        when(directory.otherAddresses(ID)).thenReturn(Set.of());
        when(directory.find(ID)).thenReturn(Optional.of(new EmailIntegration(ID, configuration,
                EmailChannelSettings.from(configuration.getPlatformConfig()))));

        EmailReplySender replies = new EmailReplySender(ledger, suppressions, directory, conversations, transport,
                new EmailReplyComposer(new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build())),
                mock(EmailOwnerNotifier.class),
                null, new SimpleMeterRegistry(), Runnable::run, Duration.ofDays(30), 3, Duration.ofMinutes(1));
        processor.replies = replies;
        poller = new EmailMailboxPoller(directory, processor, state, transport, mock(EmailPollScheduler.class), mock(EmailOwnerNotifier.class),
                new SimpleMeterRegistry(), 50, Duration.ofMinutes(2));
        sweeper = new EmailInboundSweeper(ledger, conversations, directory, mock(EmailPollScheduler.class), mock(EmailOwnerNotifier.class),
                new SimpleMeterRegistry(), Duration.ofMinutes(30));
        sweeper.replies = replies;
    }

    /**
     * The conversation as the engine would answer it: through the real snapshot
     * projection the caller asked for, so a reply path asking for the wrong one
     * (the default strips each step's context) finds nothing to answer.
     */
    private SimpleConversationMemorySnapshot snapshot(String conversationId, Boolean detailed, Boolean currentStepOnly) {
        var memory = new ConversationMemorySnapshot();
        memory.setConversationId(conversationId);
        memory.setConversationState(ConversationState.READY);
        memory.setUserId(EmailUserIdentity.perSender(ID, ALICE));
        for (ConversationOutput output : stored.getOrDefault(conversationId, List.of())) {
            ConversationOutput copy = new ConversationOutput();
            copy.putAll(output);
            memory.getConversationOutputs().add(copy);
        }
        if (memory.getConversationOutputs().isEmpty()) {
            memory.getConversationOutputs().add(new ConversationOutput());
        }
        for (int i = 0; i < memory.getConversationOutputs().size(); i++) {
            memory.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
        }
        return ConversationMemoryUtilities.convertSimpleConversationMemory(memory, Boolean.TRUE.equals(detailed),
                Boolean.TRUE.equals(currentStepOnly));
    }

    /** The engine ran turn {@code turn}: its step is stored, with the mail. */
    private SimpleConversationMemorySnapshot run(int turn, String answer) {
        String conversationId = sayConversations.get(turn);
        ConversationOutput step = new ConversationOutput();
        step.put("output", List.of(answer));
        step.put("context", new HashMap<>(Map.of("mail", inputs.get(turn).getContext().get("mail").getValue())));
        stored.computeIfAbsent(conversationId, id -> new ArrayList<>()).add(step);
        Boolean[] projection = projections.get(turn);
        return snapshot(conversationId, projection[0], projection[1]);
    }

    private void complete(int turn, String answer) throws Exception {
        var snapshot = run(turn, answer);
        TestTls.trustingDefault(() -> {
            handlers.get(turn).onComplete(snapshot);
            return null;
        });
    }

    /** The crashed node's row, its deadline passed. */
    private void pastDeadline(LedgerEntry row) {
        LedgerEntry current = ledger.findById(row.id()).orElseThrow();
        ledger.put(new LedgerEntry(current.id(), current.integrationId(), current.kind(), current.direction(), current.messageId(),
                current.conversationId(), current.sourceRef(), current.idempotencyKey(), current.recipient(), current.state(), current.attempts(),
                Instant.now().minusSeconds(1), current.claimToken(), current.providerId(), current.lastError(), current.createdAt(),
                current.updatedAt(), current.expiresAt()));
    }

    private void deliver(EmailTestMail mail) throws Exception {
        user.deliver(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mail.bytes())));
    }

    private void deliverRaw(byte[] raw) throws Exception {
        user.deliver(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw)));
    }

    private EmailMailboxPoller.PollReport poll() throws Exception {
        return TestTls.trustingDefault(() -> poller.poll(ID));
    }

    /** What reached Alice over SMTP. */
    private List<MimeMessage> aliceInbox() throws Exception {
        List<MimeMessage> received = new ArrayList<>();
        for (MimeMessage message : greenMail.getReceivedMessages()) {
            if (message.getRecipients(Message.RecipientType.TO) != null && Arrays.stream(message.getRecipients(Message.RecipientType.TO))
                    .anyMatch(a -> a.toString().contains(ALICE))) {
                received.add(message);
            }
        }
        return received;
    }

    @Test
    @DisplayName("mail in, turn, reply delivered threaded and auto-replied; the answer to EDDI's Message-ID continues it, a third party does not")
    void fullLoop() throws Exception {
        assertFalse(poll().failed());
        EmailTestMail question = EmailTestMail.mail().to(MAILBOX).subject("Order 4711").header("References: <root@supplier.com>");
        deliver(question);

        poll();
        complete(0, "Which colour?");

        List<MimeMessage> received = aliceInbox();
        assertEquals(1, received.size());
        MimeMessage reply = received.get(0);
        assertEquals("Re: Order 4711", reply.getSubject());
        assertEquals(question.messageId(), reply.getHeader("In-Reply-To")[0]);
        String references = String.join(" ", reply.getHeader("References")).replaceAll("\\s+", " ");
        assertEquals("<root@supplier.com> " + question.messageId(), references);
        assertEquals("auto-replied", reply.getHeader("Auto-Submitted")[0]);
        assertTrue(reply.getFrom()[0].toString().contains(MAILBOX), Arrays.toString(reply.getFrom()));
        assertTrue(reply.getContent().toString().contains("Which colour?"), String.valueOf(reply.getContent()));
        String eddiId = reply.getMessageID();
        assertTrue(ledger.findByMessage(ID, Direction.OUT, eddiId).isPresent(), "the ledger records the Message-ID it sent");

        deliver(EmailTestMail.mail().to(MAILBOX).subject("Re: Order 4711").header("In-Reply-To: " + eddiId));
        poll();
        assertEquals("conv-1", sayConversations.get(1), "Alice's answer continues the conversation");

        deliver(EmailTestMail.mail().from("mallory@supplier.com").to(MAILBOX).subject("Re: Order 4711").header("In-Reply-To: " + eddiId)
                .header("References: " + eddiId));
        poll();
        assertNotEquals("conv-1", sayConversations.get(2), "a third party with the Message-ID gets its own conversation");
    }

    @Test
    @DisplayName("a hard bounce of a reply suppresses the address; no further mail is sent to it")
    void bounce() throws Exception {
        poll();
        deliver(EmailTestMail.mail().to(MAILBOX));
        poll();
        complete(0, "First answer.");
        String eddiId = aliceInbox().get(0).getMessageID();

        deliverRaw(EmailConversationModeTest.dsn(eddiId, ALICE, 0).fetchRaw(1_000_000));
        poll();
        assertTrue(suppressions.isSuppressed(ID, ALICE));
        assertEquals(State.FAILED, ledger.findByMessage(ID, Direction.OUT, eddiId).orElseThrow().state());

        deliver(EmailTestMail.mail().to(MAILBOX));
        poll();
        complete(inputs.size() - 1, "Second answer.");
        assertEquals(1, aliceInbox().size(), "nothing further reached the suppressed address");
    }

    @Test
    @DisplayName("a crash between the turn's completion and the send: the stranded sweep rebuilds exactly one reply")
    void crashBeforeSend() throws Exception {
        poll();
        EmailTestMail mail = EmailTestMail.mail().to(MAILBOX);
        deliver(mail);
        poll();
        run(0, "Rebuilt from the conversation."); // the turn ran; its completion callback never did
        LedgerEntry row = ledger.findByMessage(ID, Direction.IN, mail.messageId()).orElseThrow();
        assertEquals(State.TURN_SUBMITTED, row.state());
        assertTrue(aliceInbox().isEmpty());

        pastDeadline(row);
        TestTls.trustingDefault(() -> sweeper.resolveStranded(Instant.now()));
        TestTls.trustingDefault(() -> sweeper.resolveStranded(Instant.now()));

        List<MimeMessage> received = aliceInbox();
        assertEquals(1, received.size(), "exactly one reply");
        assertTrue(received.get(0).getContent().toString().contains("Rebuilt from the conversation."));
        assertEquals(State.DONE, ledger.findById(row.id()).orElseThrow().state());
        assertEquals(1, ledger.all().stream().filter(r -> r.kind() == Kind.REPLY).count());
    }

    @Test
    @DisplayName("a mail whose turn never ran is found again in the mailbox by its Message-ID and run once, in its conversation")
    void resubmission() throws Exception {
        poll();
        EmailTestMail mail = EmailTestMail.mail().to(MAILBOX);
        deliver(mail);
        poll();
        assertEquals(1, sayConversations.size());
        // The node died after starting conv-1 and before the queued turn ran: no step.
        LedgerEntry row = ledger.findByMessage(ID, Direction.IN, mail.messageId()).orElseThrow();

        pastDeadline(row);
        sweeper.resolveStranded(Instant.now());
        assertEquals(State.RECEIVED, ledger.findById(row.id()).orElseThrow().state());

        poll();
        assertEquals(List.of("conv-1", "conv-1"), sayConversations, "run again, in the conversation started for it");
        complete(1, "Here you are.");
        poll();
        sweeper.resolveStranded(Instant.now().plus(Duration.ofHours(1)));
        assertEquals(2, sayConversations.size(), "once");
        assertEquals(1, aliceInbox().size());
        assertEquals(State.DONE, ledger.findById(row.id()).orElseThrow().state());
    }
}
