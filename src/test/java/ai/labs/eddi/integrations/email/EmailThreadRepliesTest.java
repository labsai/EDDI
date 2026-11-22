/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MailLimits;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.mail.SenderAuthenticator;
import ai.labs.eddi.engine.mail.SignedHeaders;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ReservedPrincipals;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Replies to the thread (email v2 plan §9) through the inbound processor and
 * the reply sender, with only the conversation engine and the SMTP relay as
 * fakes: the memory-less thread identity, the participant guard on every path
 * that finds a conversation, the audience the agent is shown, and the loop
 * between two integrations copied on one thread.
 */
class EmailThreadRepliesTest {

    static final String INTEGRATION = EmailInboundProcessorTest.INTEGRATION;
    static final String MAILBOX = "invoices@contoso.com";
    static final String ALICE = "alice@supplier.com";
    static final String BOB = "bob@supplier.com";
    static final String MALLORY = "mallory@supplier.com";

    InMemoryEmailMessageLedger ledger;
    IConversationService conversations;
    SenderAuthenticator authenticator;
    MailAuthentication verdict;
    List<String> sayConversations;
    List<InputData> inputs;
    List<ConversationResponseHandler> handlers;
    List<List<String>> envelopes;
    List<byte[]> sentMime;
    Map<String, String> userIds;
    Map<String, List<ConversationOutput>> outputs;
    AtomicInteger counter;
    EmailReplySender replies;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ledger = new InMemoryEmailMessageLedger();
        verdict = null;
        authenticator = mock(SenderAuthenticator.class);
        when(authenticator.authenticate(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> verdict != null ? verdict : new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "supplier.com"));
        conversations = mock(IConversationService.class);
        counter = new AtomicInteger();
        outputs = new HashMap<>();
        userIds = new HashMap<>();
        when(conversations.startConversation(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            String id = "conv-" + counter.incrementAndGet();
            userIds.put(id, invocation.getArgument(2));
            outputs.put(id, new ArrayList<>());
            return new ConversationResult(id, URI.create("eddi://c"));
        });
        when(conversations.getConversationState(anyString())).thenReturn(ConversationState.READY);
        sayConversations = new ArrayList<>();
        inputs = new ArrayList<>();
        handlers = new ArrayList<>();
        doAnswer(invocation -> {
            sayConversations.add(invocation.getArgument(0));
            inputs.add(invocation.getArgument(4));
            handlers.add(invocation.getArgument(6));
            return null;
        }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));
        envelopes = new ArrayList<>();
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
            public String send(String integrationId, EmailChannelSettings settings, byte[] mime, List<String> envelope) throws MailSendException {
                envelopes.add(List.copyOf(envelope));
                sentMime.add(mime);
                return "250 ok";
            }
        };
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        var configuration = configuration(Map.of());
        when(directory.find(INTEGRATION))
                .thenReturn(
                        Optional.of(new EmailIntegration(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
        replies = new EmailReplySender(ledger, new InMemoryEmailSuppressionStore(), directory, conversations, transport,
                new EmailReplyComposer(new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build())),
                mock(EmailOwnerNotifier.class), mock(Instance.class), mock(GdprPseudonymizer.class), new SimpleMeterRegistry(), Runnable::run,
                Duration.ofDays(30), 3, Duration.ofMinutes(1));
    }

    @SuppressWarnings("unchecked")
    EmailInboundProcessor processor(IUserConversationStore mappings) {
        return new EmailInboundProcessor(ledger, new InMemoryEmailMailboxStateStore(), new MimeMessageParser(new HtmlToMarkdownConverter()),
                authenticator, conversations, mock(IGroupConversationService.class), mappings,
                new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()), mock(Instance.class),
                mock(EmailOwnerNotifier.class), replies, mock(Instance.class), mock(GdprPseudonymizer.class), new SimpleMeterRegistry(),
                Duration.ofMinutes(30), Duration.ofDays(30));
    }

    EmailInboundProcessor processor() {
        return processor(new EmailInboundProcessorTest.FakeUserConversations());
    }

    static ChannelIntegrationConfiguration configuration(Map<String, String> extra) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "conversation", "smtpHost", "smtp.contoso.com", "actWhen", "addressed",
                "replyAudience", "thread", "replyAllowedRecipients", "*@supplier.com"));
        pc.putAll(extra);
        return EmailInboundProcessorTest.integration(pc);
    }

    static IntegrationContext context(Map<String, String> extra) {
        var configuration = configuration(extra);
        return new IntegrationContext(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()), Set.of());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> mailContext(int turn) {
        return (Map<String, Object>) inputs.get(turn).getContext().get("mail").getValue();
    }

    /**
     * Completes turn {@code turn} as the engine would, under the conversation's own
     * user id.
     */
    void complete(int turn, String answer) {
        String conversationId = sayConversations.get(turn);
        ConversationOutput step = new ConversationOutput();
        step.put("output", List.of(answer));
        step.put("context", new HashMap<>(Map.of("mail", mailContext(turn))));
        List<ConversationOutput> all = outputs.computeIfAbsent(conversationId, id -> new ArrayList<>());
        all.add(step);
        ConversationMemorySnapshot memory = new ConversationMemorySnapshot();
        memory.setConversationId(conversationId);
        memory.setConversationState(ConversationState.READY);
        memory.setUserId(userIds.get(conversationId));
        memory.getConversationOutputs().addAll(all);
        for (int i = 0; i < memory.getConversationOutputs().size(); i++) {
            memory.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
        }
        SimpleConversationMemorySnapshot snapshot = ConversationMemoryUtilities.convertSimpleConversationMemory(memory, true, false);
        handlers.get(turn).onComplete(snapshot);
    }

    String sentReplyId(int index) {
        return ledger.all().stream().filter(r -> r.kind() == Kind.REPLY && r.state() == State.SENT).toList().get(index).messageId();
    }

    /**
     * Alice writes to the mailbox with Bob (internal) and Eve (external) in Cc;
     * EDDI answers.
     */
    EmailTestMail startThread(EmailInboundProcessor processor) {
        EmailTestMail first = EmailTestMail.mail().to(MAILBOX).header("Cc: " + BOB + ", eve@external.com");
        processor.process(context(Map.of()), first.polled(1), Instant.now());
        complete(0, "It ships Monday.");
        return first;
    }

    @Test
    @DisplayName("the thread's memory-less identity; the agent is told who will read; Bob is copied, Eve is not")
    void copiesTheThread() {
        EmailTestMail first = startThread(processor());

        String userId = userIds.get("conv-1");
        assertEquals(EmailUserIdentity.thread(INTEGRATION, first.messageId()), userId);
        assertTrue(ReservedPrincipals.hasNoPersistentMemory(userId), "colleagues read the answer: no long-term memory");
        assertEquals(Map.of("to", ALICE, "cc", List.of(BOB)), mailContext(0).get("audience"));
        assertEquals(List.of(List.of(ALICE, BOB)), envelopes);
    }

    @Test
    @DisplayName("Bob, copied on EDDI's reply, continues the same conversation")
    void copiedColleagueContinues() {
        EmailInboundProcessor processor = processor();
        EmailTestMail first = startThread(processor);
        String eddiId = sentReplyId(0);

        EmailTestMail fromBob = EmailTestMail.mail().from(BOB).to(MAILBOX).header("In-Reply-To: " + eddiId)
                .header("References: " + first.messageId() + " " + eddiId);
        processor.process(context(Map.of()), fromBob.polled(2), Instant.now());

        assertEquals("conv-1", sayConversations.get(1));
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("hijack guard, reply path: a non-participant answering EDDI's reply gets a conversation of their own")
    void hijackThroughEddisReply() {
        EmailInboundProcessor processor = processor();
        EmailTestMail first = startThread(processor);
        String eddiId = sentReplyId(0);

        EmailTestMail hijack = EmailTestMail.mail().from(MALLORY).to(MAILBOX).header("In-Reply-To: " + eddiId)
                .header("References: " + first.messageId() + " " + eddiId);
        processor.process(context(Map.of()), hijack.polled(2), Instant.now());

        assertNotEquals("conv-1", sayConversations.get(1), "a Message-ID is no secret");
        assertEquals(EmailUserIdentity.threadForSender(INTEGRATION, first.messageId(), MALLORY), userIds.get(sayConversations.get(1)));
    }

    @Test
    @DisplayName("hijack guard, thread key: spoofed References naming another sender's thread do not continue it")
    void hijackThroughSpoofedReferences() {
        EmailInboundProcessor processor = processor();
        EmailTestMail first = startThread(processor);

        // Not a reply to anything EDDI sent: only the shared thread key could find
        // Alice's conversation.
        EmailTestMail spoofed = EmailTestMail.mail().from(MALLORY).to(MAILBOX).header("In-Reply-To: <elsewhere@supplier.com>")
                .header("References: " + first.messageId() + " <elsewhere@supplier.com>");
        processor.process(context(Map.of()), spoofed.polled(2), Instant.now());

        assertNotEquals("conv-1", sayConversations.get(1));
        assertEquals(EmailUserIdentity.threadForSender(INTEGRATION, first.messageId(), MALLORY), userIds.get(sayConversations.get(1)));
        assertEquals(Map.of("to", MALLORY, "cc", List.of()), mailContext(1).get("audience"), "and nobody is copied on their answer");
    }

    @Test
    @DisplayName("hijack guard, start race: a mail that loses the race for a thread's conversation to another sender waits")
    void hijackThroughTheStartRace() {
        Instant now = Instant.now();
        ledger.put(new LedgerEntry("winner-mail", INTEGRATION, Kind.TRIGGER, Direction.IN, "<winner@supplier.com>", "conv-winner", "imap:INBOX:1:0",
                "in:winner", BOB, State.TURN_SUBMITTED, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
        var racing = new EmailInboundProcessorTest.FakeUserConversations() {
            @Override
            public void createUserConversation(UserConversation mapping) throws IResourceStore.ResourceAlreadyExistsException {
                // Another node mapped the thread first, for Bob's mail.
                mappings.put(mapping.getIntent() + "|" + mapping.getUserId(), new UserConversation(mapping.getIntent(), mapping.getUserId(),
                        mapping.getEnvironment(), mapping.getAgentId(), "conv-winner"));
                throw new IResourceStore.ResourceAlreadyExistsException("exists");
            }
        };
        EmailTestMail fromAlice = EmailTestMail.mail().to(MAILBOX);

        processor(racing).process(context(Map.of()), fromAlice.polled(1), now);

        assertTrue(sayConversations.isEmpty(), "never joins Bob's conversation");
        LedgerEntry row = ledger.findByMessage(INTEGRATION, Direction.IN, fromAlice.messageId()).orElseThrow();
        assertEquals(State.RECEIVED, row.state());
        assertEquals("conversation_busy", row.lastError());
        verify(conversations).endConversation(eq("conv-1"), anyString());
    }

    @Test
    @DisplayName("a per-sender conversation started before the switch to thread keeps answering its sender alone")
    void conversationFromBeforeTheSwitch() throws Exception {
        Instant now = Instant.now();
        String perSender = EmailUserIdentity.perSender(INTEGRATION, ALICE);
        ledger.put(new LedgerEntry("old-in", INTEGRATION, Kind.TRIGGER, Direction.IN, "<old@supplier.com>", "conv-old", "imap:INBOX:1:0",
                "in:old", ALICE, State.DONE, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
        ledger.put(new LedgerEntry("old-out", INTEGRATION, Kind.REPLY, Direction.OUT, "<eddi.old@contoso.com>", "conv-old", "old-in|reply|1",
                "reply:old", ALICE, State.SENT, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
        SimpleConversationMemorySnapshot old = new SimpleConversationMemorySnapshot();
        old.setConversationId("conv-old");
        old.setAgentId(EmailInboundProcessorTest.AGENT);
        old.setUserId(perSender);
        when(conversations.readConversation(eq("conv-old"), any(), any(), anyList())).thenReturn(old);
        userIds.put("conv-old", perSender);

        EmailTestMail answer = EmailTestMail.mail().to(MAILBOX).header("Cc: " + BOB).header("In-Reply-To: <eddi.old@contoso.com>")
                .header("References: <old@supplier.com> <eddi.old@contoso.com>");
        processor().process(context(Map.of()), answer.polled(1), now);
        complete(0, "Still Monday.");

        assertEquals("conv-old", sayConversations.get(0));
        assertEquals(Map.of("to", ALICE, "cc", List.of()), mailContext(0).get("audience"), "that conversation carries Alice's memory");
        assertEquals(List.of(List.of(ALICE)), envelopes);
    }

    @Test
    @DisplayName("signed view: an unsigned Cc prepended to a replayed signed mail is never copied")
    void unsignedCcNotCopied() {
        verdict = new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "supplier.com", "verified", false, List.of(),
                new SignedHeaders(true, Set.of("from", "date", "message-id", "subject", "to", "cc"), List.of(new MailAddress(MAILBOX, null)),
                        List.of(new MailAddress(BOB, null)), "sig-" + System.nanoTime()));
        EmailTestMail replay = EmailTestMail.mail().to(MAILBOX).header("Cc: ceo@supplier.com").header("Cc: " + BOB);

        processor().process(context(Map.of()), replay.polled(1), Instant.now());
        complete(0, "Done.");

        assertEquals(Map.of("to", ALICE, "cc", List.of(BOB)), mailContext(0).get("audience"));
        assertEquals(List.of(List.of(ALICE, BOB)), envelopes, "the CEO was added by whoever replayed the mail");
    }

    @Test
    @DisplayName("replyAudience sender (the default) is unchanged: a per-sender identity, the sender alone")
    void senderAudienceUnchanged() {
        EmailTestMail first = EmailTestMail.mail().to(MAILBOX).header("Cc: " + BOB);
        processor().process(context(Map.of("replyAudience", "sender", "replyAllowedRecipients", "")), first.polled(1), Instant.now());
        complete(0, "It ships Monday.");

        assertEquals(EmailUserIdentity.perSender(INTEGRATION, ALICE), userIds.get("conv-1"));
        assertEquals(List.of(List.of(ALICE)), envelopes);
    }

    @Test
    @DisplayName("loops: two integrations copied on one thread answer once each — the other's reply is refused as automated")
    void twoIntegrationsDoNotLoop() throws Exception {
        EmailInboundProcessor processor = processor();
        startThread(processor);
        byte[] reply = sentMime.get(0);
        String other = "b1b2c3d4e5f60718293a4b5c";
        var configuration = EmailInboundProcessorTest.integration(Map.of("mode", "conversation", "smtpHost", "smtp.contoso.com", "address",
                "orders@contoso.com", "actWhen", "addressed", "replyAudience", "thread", "replyAllowedRecipients", "*@supplier.com,*@contoso.com"));
        var otherContext = new IntegrationContext(other, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()), Set.of());
        InboundMail parsed = new MimeMessageParser(new HtmlToMarkdownConverter()).parse(reply, MailLimits.defaults(), true);

        processor.process(otherContext, new EmailTestMail.Polled(7, reply, parsed.messageId(), Instant.now(), false), Instant.now());

        LedgerEntry row = ledger.findByMessage(other, Direction.IN, parsed.messageId()).orElseThrow();
        assertEquals(State.REFUSED, row.state());
        assertTrue(row.lastError().startsWith("loop:"), row.lastError());
        assertEquals(1, envelopes.size(), "the first integration's one reply, nothing more");
    }
}
