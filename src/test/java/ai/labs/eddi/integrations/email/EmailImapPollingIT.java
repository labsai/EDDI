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
import ai.labs.eddi.engine.memory.model.ConversationState;
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
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.Flags;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The trigger-mode IMAP channel end to end against GreenMail (implicit TLS on
 * loopback): Angus Mail through {@link SafeSocketConnector}'s pinned sockets
 * with certificate and host-name checks on, a BASIC login from a connection,
 * the real parser, processor, ledger semantics and poller. Only the turn itself
 * is a mock. Loopback is reached through the test seam
 * {@link SafeSocketTestSupport}; the production resolver refuses it.
 */
class EmailImapPollingIT {

    private static final String ID = "a1b2c3d4e5f60718293a4b5c";
    private static final String MAILBOX = "invoices@contoso.com";
    private static final String PASSWORD = "fixturefixture";

    private static GreenMail greenMail;

    private GreenMailUser user;
    private InMemoryEmailMessageLedger ledger;
    private InMemoryEmailMailboxStateStore state;
    private IConversationService conversations;
    private EmailIntegrationDirectory directory;
    private EmailMailboxPoller poller;
    private List<InputData> turns;
    private List<ConversationResponseHandler> handlers;

    @BeforeAll
    static void startGreenMail() throws Exception {
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY, TestTls.keystore().toString());
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_PASSWORD_PROPERTY, TestTls.PASSWORD);
        greenMail = new GreenMail(new ServerSetup[]{new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)});
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
        state = new InMemoryEmailMailboxStateStore();
        turns = new ArrayList<>();
        handlers = new ArrayList<>();
        conversations = mock(IConversationService.class);
        AtomicInteger counter = new AtomicInteger();
        when(conversations.startConversation(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new ConversationResult("conv-" + counter.incrementAndGet(), URI.create("eddi://c")));
        when(conversations.getConversationState(anyString())).thenReturn(ConversationState.READY);
        doAnswer(invocation -> {
            turns.add(invocation.getArgument(4));
            handlers.add(invocation.getArgument(6));
            return null;
        }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        SenderAuthenticator authenticator = mock(SenderAuthenticator.class);
        when(authenticator.authenticate(any(), any(), any(), any()))
                .thenReturn(new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "supplier.com"));
        Instance<IAttachmentStore> attachments = mock(Instance.class);
        EmailInboundProcessor processor = new EmailInboundProcessor(ledger, state, new MimeMessageParser(new HtmlToMarkdownConverter()),
                authenticator,
                conversations, mock(IGroupConversationService.class), new EmailInboundProcessorTest.FakeUserConversations(),
                new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()), attachments, mock(EmailOwnerNotifier.class),
                new SimpleMeterRegistry(), Duration.ofMinutes(30), Duration.ofDays(30));

        ConnectionResolver resolver = mock(ConnectionResolver.class);
        when(resolver.authTypeOf("${connection:mailbox}")).thenReturn(Optional.of(AuthType.BASIC));
        when(resolver.resolveBasicLogin(eq("${connection:mailbox}"), eq(URI.create("imaps://imap.contoso.com"))))
                .thenReturn(new ConnectionResolver.BasicLogin(MAILBOX, PASSWORD));
        Instance<ConnectionResolver> resolverInstance = mock(Instance.class);
        when(resolverInstance.isResolvable()).thenReturn(true);
        when(resolverInstance.get()).thenReturn(resolver);
        int port = greenMail.getImaps().getPort();
        var transport = new ImapMailTransport(new SafeSocketConnector(Optional.empty()),
                (host, ignored) -> SafeSocketTestSupport.loopback("localhost", port, SafeSocketConnector.Protocol.IMAP,
                        SafeSocketConnector.TlsMode.IMPLICIT),
                resolverInstance);

        directory = mock(EmailIntegrationDirectory.class);
        when(directory.otherAddresses(ID)).thenReturn(Set.of());
        configure(Map.of());
        poller = new EmailMailboxPoller(directory, processor, state, transport, mock(EmailPollScheduler.class), mock(EmailOwnerNotifier.class),
                new SimpleMeterRegistry(), 50, Duration.ofMinutes(2));
    }

    private void configure(Map<String, String> extra) {
        ChannelIntegrationConfiguration configuration = EmailInboundProcessorTest.integration(extra);
        configuration.getPlatformConfig().put("host", "imap.contoso.com");
        configuration.getPlatformConfig().put("address", MAILBOX);
        when(directory.find(ID))
                .thenReturn(Optional.of(new EmailIntegration(ID, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
    }

    private void deliver(EmailTestMail mail) throws Exception {
        user.deliver(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mail.bytes())));
    }

    private EmailMailboxPoller.PollReport poll() throws Exception {
        return TestTls.trustingDefault(() -> poller.poll(ID));
    }

    private List<StoredMessage> inbox() throws Exception {
        MailFolder inbox = greenMail.getManagers().getImapHostManager().getInbox(user);
        return inbox.getMessages();
    }

    @Test
    @DisplayName("mail already in the folder is left alone; new mail starts a turn and is flagged, never marked \\Seen")
    void newMailStartsTurn() throws Exception {
        deliver(EmailTestMail.mail().subject("old mail"));
        var first = poll();
        assertFalse(first.failed(), first.message());
        assertTrue(turns.isEmpty(), "pre-existing mail is not processed");

        EmailTestMail mail = EmailTestMail.mail().subject("Invoice 4711");
        deliver(mail);
        var second = poll();
        assertEquals("1 processed, 1 marked, 0 deferred", second.message());

        assertFalse(second.failed(), second.message());
        assertEquals(1, turns.size());
        assertTrue(turns.get(0).getInput().contains("Invoice 4711"));
        LedgerEntry row = ledger.findByMessage(ID, Direction.IN, mail.messageId()).orElseThrow();
        assertEquals(State.TURN_SUBMITTED, row.state());
        StoredMessage stored = inbox().get(1);
        Flags flags = stored.getFlags();
        assertTrue(flags.contains(MailTransport.PROCESSED_KEYWORD), "keyword set after the ledger write");
        assertFalse(flags.contains(Flags.Flag.SEEN), "BODY.PEEK: EDDI never marks mail read");
        assertFalse(flags.contains(Flags.Flag.DELETED), "never deleted");
        assertFalse(inbox().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD), "the old mail is untouched");

        poll();
        assertEquals(1, turns.size(), "a second poll runs nothing again");
    }

    @Test
    @DisplayName("move:<folder> moves processed mail out of the polled folder, flagged, never deleted")
    void moveProcessed() throws Exception {
        configure(Map.of("processedAction", "move:EDDI-Processed"));
        poll();
        deliver(EmailTestMail.mail());

        poll();

        assertEquals(1, turns.size());
        MailFolder processed = greenMail.getManagers().getImapHostManager().getFolder(user, "EDDI-Processed");
        // GreenMail speaks MOVE (RFC 6851), so the move must have happened.
        assertTrue(processed != null && processed.getMessages().size() == 1, "moved into EDDI-Processed");
        assertTrue(inbox().isEmpty(), "moved out of INBOX, nothing left behind");
        assertTrue(processed.getMessages().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD));
        assertFalse(processed.getMessages().get(0).getFlags().contains(Flags.Flag.DELETED));
    }

    @Test
    @DisplayName("an oversize mail is refused from RFC822.SIZE and flagged; no turn")
    void oversize() throws Exception {
        configure(Map.of("maxMessageBytes", "2048"));
        poll();
        deliver(EmailTestMail.mail().body("x".repeat(10_000)));

        poll();

        assertTrue(turns.isEmpty());
        assertTrue(ledger.all().stream().anyMatch(r -> r.state() == State.REFUSED && "too_large".equals(r.lastError())));
        assertTrue(inbox().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD));
    }

    @Test
    @DisplayName("a crash between the ledger write and the mark: the next poll marks the mail and does not run the turn twice")
    void crashBetweenLedgerAndMark() throws Exception {
        poll();
        EmailTestMail mail = EmailTestMail.mail();
        deliver(mail);
        Instant now = Instant.now();
        // What the crashed poll left behind: the claimed row, an unmarked mail, an old
        // cursor.
        ledger.put(new LedgerEntry("crashed", ID, Kind.TRIGGER, Direction.IN, mail.messageId(), "conv-0", "imap:INBOX:1:1",
                EmailInboundProcessor.inboundKey(ID, mail.messageId()), "alice@supplier.com", State.TURN_SUBMITTED, 1, now.plusSeconds(600), null,
                null, null, now, now, now.plus(Duration.ofDays(30))));

        poll();

        assertTrue(turns.isEmpty(), "the turn already ran on the crashed node");
        assertTrue(inbox().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD));
    }

    private LedgerEntry crashedRow(EmailTestMail mail, State state, String conversationId, Instant deadline) {
        Instant now = Instant.now();
        LedgerEntry row = new LedgerEntry("crashed-" + state, ID, Kind.TRIGGER, Direction.IN, mail.messageId(), conversationId, "imap:INBOX:1:1",
                EmailInboundProcessor.inboundKey(ID, mail.messageId()), "alice@supplier.com", state, 1, deadline, null, null, null, now, now,
                now.plus(Duration.ofDays(30)));
        ledger.put(row);
        return row;
    }

    @Test
    @DisplayName("a crash after the claim, before any side effect: the stale claim is given back and the unmarked mail runs once")
    void crashAfterClaimBeforeSideEffects() throws Exception {
        poll();
        EmailTestMail mail = EmailTestMail.mail();
        deliver(mail);
        crashedRow(mail, State.CLAIMED, null, Instant.now().minusSeconds(1));

        poll();

        assertEquals(1, turns.size(), "nothing had left EDDI, so the mail is processed again");
        assertTrue(inbox().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD));
        poll();
        assertEquals(1, turns.size(), "and only once");
    }

    @Test
    @DisplayName("a live claim is left unmarked; once the sweep gives it back, the next poll fetches it from the mailbox and runs it")
    void sweepRequeuesClaimForNextPoll() throws Exception {
        poll();
        EmailTestMail mail = EmailTestMail.mail();
        deliver(mail);
        Instant deadline = Instant.now().plusSeconds(600);
        crashedRow(mail, State.CLAIMED, null, deadline);

        poll();
        assertTrue(turns.isEmpty());
        assertFalse(inbox().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD), "a claimed mail is never marked");

        EmailOwnerNotifier notifier = mock(EmailOwnerNotifier.class);
        var sweeper = new EmailInboundSweeper(ledger, conversations, directory, mock(EmailPollScheduler.class), notifier, new SimpleMeterRegistry(),
                Duration.ofMinutes(30));
        assertEquals(1, sweeper.resolveStranded(deadline.plusSeconds(1)));
        poll();

        assertEquals(1, turns.size());
        assertTrue(inbox().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD));
    }

    @Test
    @DisplayName("a crash while starting the conversation (no conversation recorded): marked, never run again, REFUSED with a lost notice")
    void crashWhileStartingConversation() throws Exception {
        poll();
        EmailTestMail mail = EmailTestMail.mail();
        deliver(mail);
        LedgerEntry row = crashedRow(mail, State.TURN_SUBMITTED, null, Instant.now().plusSeconds(600));

        poll();
        assertTrue(turns.isEmpty(), "it may have partly run: never again");
        assertTrue(inbox().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD));

        EmailOwnerNotifier notifier = mock(EmailOwnerNotifier.class);
        var sweeper = new EmailInboundSweeper(ledger, conversations, directory, mock(EmailPollScheduler.class), notifier, new SimpleMeterRegistry(),
                Duration.ofMinutes(30));
        sweeper.resolveStranded(Instant.now().plusSeconds(601));

        assertEquals(State.REFUSED, ledger.findById(row.id()).orElseThrow().state());
        verify(notifier).notifyOnce(eq(ID), any(), any(), eq(EmailOwnerNotifier.Reason.LOST), any());
    }

    @Test
    @DisplayName("a changed UIDVALIDITY rescans the folder; processed mail is not run again, missed mail is")
    void uidValidityChange() throws Exception {
        poll();
        EmailTestMail processed = EmailTestMail.mail().subject("first");
        deliver(processed);
        poll();
        assertEquals(1, turns.size());
        // Clear the keyword so only the ledger can recognise it, and pretend the folder
        // was rebuilt.
        inbox().get(0).setFlags(new Flags(MailTransport.PROCESSED_KEYWORD), false);
        EmailTestMail missed = EmailTestMail.mail().subject("second");
        deliver(missed);
        var cursor = state.read(ID).orElseThrow();
        state.saveCursor(ID, "INBOX", cursor.uidValidity() + 1, 1_000_000, cursor.since(), Instant.now());

        poll();

        assertEquals(2, turns.size(), "only the mail the ledger does not know runs");
        assertTrue(turns.get(1).getInput().contains("second"));
        assertTrue(inbox().get(0).getFlags().contains(MailTransport.PROCESSED_KEYWORD), "the duplicate is flagged again");
        assertEquals(cursor.uidValidity(), state.read(ID).orElseThrow().uidValidity(), "the cursor follows the folder's UIDVALIDITY");
    }

    @Test
    @DisplayName("a wrong password is a categorised, backed-off failure, with no secret in the report")
    void loginFailure() throws Exception {
        greenMail.setUser(MAILBOX, MAILBOX, "another-password");

        var report = poll();

        assertTrue(report.failed());
        assertTrue(report.message().startsWith("AUTHENTICATION"), report.message());
        assertFalse(report.message().contains(PASSWORD));
        assertEquals(1, state.read(ID).orElseThrow().failures());
    }
}
