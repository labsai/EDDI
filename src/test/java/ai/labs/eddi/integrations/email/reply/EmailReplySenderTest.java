/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.mail.DeliveryReport;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.EmailOwnerNotifier;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailSuppressionStore;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.CONVERSATION;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.INTEGRATION;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.MESSAGE_ID;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.NOW;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.SENDER;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.answered;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.conversationConfig;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.inbound;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.mail;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.output;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.settings;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailReplySenderTest {

    InMemoryEmailMessageLedger ledger;
    InMemoryEmailSuppressionStore suppressions;
    EmailIntegrationDirectory directory;
    IConversationService conversations;
    RecordingTransport transport;
    EmailOwnerNotifier ownerNotifier;
    AuditLedgerService audit;
    EmailReplySender sender;
    SimpleMeterRegistry registry;

    /** Records sends; fails as told. */
    static final class RecordingTransport implements MailTransport {
        final List<String> recipients = new ArrayList<>();
        final List<byte[]> messages = new ArrayList<>();
        final List<MailSendException> failures = new ArrayList<>();

        @Override
        public String id() {
            return "imap";
        }

        @Override
        public MailboxSession open(String integrationId, EmailChannelSettings settings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized String send(String integrationId, EmailChannelSettings settings, byte[] mime, String recipient)
                throws MailSendException {
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
            recipients.add(recipient);
            messages.add(mime);
            return "250 2.0.0 queued";
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ledger = new InMemoryEmailMessageLedger();
        suppressions = new InMemoryEmailSuppressionStore();
        directory = mock(EmailIntegrationDirectory.class);
        integration(Map.of());
        conversations = mock(IConversationService.class);
        transport = new RecordingTransport();
        ownerNotifier = mock(EmailOwnerNotifier.class);
        audit = mock(AuditLedgerService.class);
        when(audit.isEnabled()).thenReturn(true);
        Instance<AuditLedgerService> audits = mock(Instance.class);
        when(audits.isResolvable()).thenReturn(true);
        when(audits.get()).thenReturn(audit);
        registry = new SimpleMeterRegistry();
        sender = new EmailReplySender(ledger, suppressions, directory, conversations, transport,
                new EmailReplyComposer(new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build())), ownerNotifier, audits,
                registry, Runnable::run, Duration.ofDays(30), 3, Duration.ofMinutes(1));
    }

    private void integration(Map<String, String> extra) {
        var configuration = new ChannelIntegrationConfiguration();
        configuration.setName("Orders");
        configuration.setChannelType("email");
        configuration.setPlatformConfig(new HashMap<>(conversationConfig(extra)));
        when(directory.find(INTEGRATION))
                .thenReturn(
                        Optional.of(new EmailIntegration(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
    }

    private LedgerEntry storedInbound(State state) {
        LedgerEntry row = inbound(state);
        ledger.put(row);
        return row;
    }

    private List<LedgerEntry> replies() {
        return ledger.all().stream().filter(r -> r.kind() == Kind.REPLY).toList();
    }

    private static MimeMessage parse(byte[] mime) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime));
    }

    @Nested
    @DisplayName("the reply to a turn")
    class Reply {

        @Test
        @DisplayName("the first reply carries the start output and the mail's turn, to the sender, recorded SENT and audited without content")
        void firstReply() throws Exception {
            LedgerEntry inbound = storedInbound(State.TURN_SUBMITTED);

            var row = sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, answered("Your order ships Monday."), NOW);

            assertTrue(row.isPresent());
            assertEquals(List.of(SENDER), transport.recipients);
            String text = parse(transport.messages.get(0)).getContent().toString();
            assertTrue(text.contains("Hello, I am the order desk."), text);
            assertTrue(text.contains("Your order ships Monday."), text);
            assertTrue(text.contains(EmailChannelSettings.DEFAULT_AI_DISCLOSURE), text);
            LedgerEntry stored = ledger.findById(row.get().id()).orElseThrow();
            assertEquals(State.SENT, stored.state());
            assertEquals(SENDER, stored.recipient());
            assertEquals(inbound.id() + "|reply|1", stored.sourceRef());
            assertEquals(parse(transport.messages.get(0)).getMessageID(), stored.messageId(), "the ledger knows the Message-ID it sent");
            ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).submit(entry.capture());
            assertEquals("email.reply", entry.getValue().taskId());
            assertFalse(entry.getValue().output().toString().contains(SENDER), "the address only as a hash");
            assertFalse(entry.getValue().output().toString().contains("ships Monday"), "no body");
        }

        @Test
        @DisplayName("two completion observers of the same turn produce one reply")
        void idempotent() throws Exception {
            LedgerEntry inbound = storedInbound(State.TURN_SUBMITTED);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Optional<LedgerEntry>>> results = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, answered("Once."), NOW);
                    }));
                }
                start.countDown();
                int won = 0;
                for (var result : results) {
                    won += result.get().isPresent() ? 1 : 0;
                }
                assertEquals(1, won);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, transport.recipients.size());
            assertEquals(1, replies().size());
            assertTrue(sender.ensureFor(settings(), inbound, answered("Once."), NOW) == false, "the stranded sweep finds it done");
        }

        @Test
        @DisplayName("trigger mode, a paused or failed turn, a turn without output, an unknown step: nothing")
        void nothingToSend() {
            LedgerEntry inbound = storedInbound(State.TURN_SUBMITTED);
            var trigger = EmailChannelSettings.from(Map.of("mode", "trigger", "transport", "imap", "connection", "mailbox", "host",
                    "imap.contoso.com", "address", "support@contoso.com"));
            assertTrue(sender.onTurnCompleted(INTEGRATION, trigger, inbound, CONVERSATION, answered("x"), NOW).isEmpty());
            assertNull(registry.find("eddi.email.outbound").tag("outcome", "refused").counter(),
                    "trigger mode never reaches the recipient rules, let alone a send");
            for (ConversationState state : List.of(ConversationState.AWAITING_HUMAN, ConversationState.ERROR,
                    ConversationState.EXECUTION_INTERRUPTED)) {
                var paused = snapshot(state, EmailUserIdentity.perSender(INTEGRATION, SENDER), output("so far", mail()));
                assertTrue(sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, paused, NOW).isEmpty(), state.name());
            }
            var silent = snapshot(ConversationState.READY, EmailUserIdentity.perSender(INTEGRATION, SENDER), output(null, mail()));
            assertTrue(sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, silent, NOW).isEmpty());
            var otherMail = snapshot(ConversationState.READY, EmailUserIdentity.perSender(INTEGRATION, SENDER),
                    output("answer", mail("<other@x.example>", SENDER)));
            assertTrue(sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, otherMail, NOW).isEmpty());
            assertTrue(transport.recipients.isEmpty());
            assertTrue(replies().isEmpty());
        }

        @Test
        @DisplayName("an email-anon: conversation never gets a reply, whatever its memory says")
        void anonymousNeverAnswered() {
            LedgerEntry inbound = storedInbound(State.DONE);
            var anonymous = snapshot(ConversationState.READY, EmailUserIdentity.anonymous(), output("answer", mail()));

            assertTrue(sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, anonymous, NOW).isEmpty());
            assertTrue(transport.recipients.isEmpty());
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        @DisplayName("a suppressed address is never sent to: the row is SUPPRESSED, nothing leaves")
        void suppressed() {
            suppressions.suppress(INTEGRATION, SENDER, "bounce:5.1.1", NOW);
            LedgerEntry inbound = storedInbound(State.TURN_SUBMITTED);

            sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, answered("x"), NOW);

            assertTrue(transport.recipients.isEmpty());
            assertEquals(State.SUPPRESSED, replies().get(0).state());
            assertEquals(EmailReplySender.SUPPRESSED_RECIPIENT, replies().get(0).lastError());
            assertEquals(0, replies().get(0).attempts(), "refused when it was queued, never claimed for an attempt");
        }

        @Test
        @DisplayName("suppressed between queueing and the attempt: still not sent")
        void suppressedBeforeAttempt() {
            LedgerEntry inbound = storedInbound(State.TURN_SUBMITTED);
            List<Runnable> queued = new ArrayList<>();
            var deferred = new EmailReplySender(ledger, suppressions, directory, conversations, transport,
                    new EmailReplyComposer(new TemplatingEngine(Engine.builder().addDefaults().build())), ownerNotifier, null, null, queued::add,
                    Duration.ofDays(30), 3, Duration.ofMinutes(1));
            deferred.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, answered("x"), NOW);
            suppressions.suppress(INTEGRATION, SENDER, "bounce:5.1.1", NOW);
            queued.forEach(Runnable::run);

            assertTrue(transport.recipients.isEmpty());
            assertEquals(State.SUPPRESSED, replies().get(0).state());
        }

        @Test
        @DisplayName("maxRepliesPerThreadPerHour: the next reply in the thread is held back and the owners hear once")
        void threadBudget() {
            integration(Map.of("maxRepliesPerThreadPerHour", "2"));
            var settings = settings(Map.of("maxRepliesPerThreadPerHour", "2"));
            for (int i = 0; i < 3; i++) {
                LedgerEntry inbound = inbound("in-" + i, "<q" + i + "@supplier.com>", SENDER, State.TURN_SUBMITTED);
                ledger.put(inbound);
                var snapshot = snapshot(ConversationState.READY, EmailUserIdentity.perSender(INTEGRATION, SENDER),
                        output("answer " + i, mail("<q" + i + "@supplier.com>", SENDER)));
                sender.onTurnCompleted(INTEGRATION, settings, inbound, CONVERSATION, snapshot, NOW.plusSeconds(i));
            }

            assertEquals(2, transport.recipients.size());
            assertEquals(1, replies().stream().filter(r -> r.state() == State.SUPPRESSED).count());
            assertEquals(EmailReplySender.THREAD_BUDGET,
                    replies().stream().filter(r -> r.state() == State.SUPPRESSED).findFirst().orElseThrow().lastError());
            verify(ownerNotifier).notifyOnce(eq(INTEGRATION), any(), any(), eq(EmailOwnerNotifier.Reason.THREAD_THROTTLED), any());
        }
    }

    @Nested
    @DisplayName("retries")
    class Retries {

        @Test
        @DisplayName("a transient failure is retried from the stored conversation, with the same Message-ID")
        void retryRebuilds() throws Exception {
            LedgerEntry inbound = storedInbound(State.DONE);
            transport.failures.add(new MailSendException(MailSendException.Category.CONNECT, false, "down", null));
            var row = sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, answered("Ships Monday."), NOW).orElseThrow();
            LedgerEntry pending = ledger.findById(row.id()).orElseThrow();
            assertEquals(State.PENDING, pending.state());
            when(conversations.readConversation(CONVERSATION, true, false, List.of())).thenReturn(answered("Ships Monday."));

            sender.sweep(pending.nextAttemptAt().plusSeconds(1));

            assertEquals(List.of(SENDER), transport.recipients);
            assertEquals(row.messageId(), parse(transport.messages.get(0)).getMessageID());
            assertEquals(State.SENT, ledger.findById(row.id()).orElseThrow().state());
        }

        @Test
        @DisplayName("a permanent refusal ends FAILED and notifies the owners; so does the last attempt")
        void failed() {
            LedgerEntry inbound = storedInbound(State.DONE);
            transport.failures.add(new MailSendException(MailSendException.Category.REJECTED, true, "550", null));

            var row = sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, answered("x"), NOW).orElseThrow();

            assertEquals(State.FAILED, ledger.findById(row.id()).orElseThrow().state());
            verify(ownerNotifier).notifyOnce(eq(INTEGRATION), any(), any(), eq(EmailOwnerNotifier.Reason.REPLY_FAILED), any());
        }

        @Test
        @DisplayName("a retry re-applies the recipient rules: an integration switched to trigger mode sends nothing")
        void retryAfterModeChange() {
            LedgerEntry inbound = storedInbound(State.DONE);
            transport.failures.add(new MailSendException(MailSendException.Category.CONNECT, false, "down", null));
            var row = sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, answered("x"), NOW).orElseThrow();
            var configuration = new ChannelIntegrationConfiguration();
            configuration.setChannelType("email");
            Map<String, String> trigger = Map.of("mode", "trigger", "transport", "imap", "connection", "mailbox", "host", "imap.contoso.com",
                    "address", "support@contoso.com");
            configuration.setPlatformConfig(trigger);
            when(directory.find(INTEGRATION))
                    .thenReturn(Optional.of(new EmailIntegration(INTEGRATION, configuration, EmailChannelSettings.from(trigger))));

            sender.sweep(Instant.now().plus(Duration.ofHours(2)));

            assertTrue(transport.recipients.isEmpty());
            assertEquals(State.SUPPRESSED, ledger.findById(row.id()).orElseThrow().state());
        }
    }

    @Nested
    @DisplayName("HITL in the thread")
    class Hitl {

        private void paused(String reason) throws Exception {
            var stored = new ConversationMemorySnapshot();
            stored.setHitlPausedAt(Instant.ofEpochMilli(1_700_000_000_000L));
            stored.setHitlPauseReason(reason);
            stored.setHitlPauseType("TOOL_CALL");
            when(conversations.getConversationMemorySnapshot(CONVERSATION)).thenReturn(stored);
            when(conversations.readConversation(CONVERSATION, true, false, List.of())).thenReturn(snapshot(ConversationState.AWAITING_HUMAN,
                    EmailUserIdentity.perSender(INTEGRATION, SENDER), output("Let me check the refund.", mail())));
        }

        @Test
        @DisplayName("the pause notice holds the output so far and the pause reason — never the tool or its arguments — once")
        void pauseNotice() throws Exception {
            ledger.put(inbound(State.TURN_SUBMITTED));
            paused("A refund over 100 EUR needs a person.");

            assertTrue(sender.onPauseCommitted(CONVERSATION, "1700000000000", NOW).isPresent());
            assertTrue(sender.onPauseCommitted(CONVERSATION, "1700000000000", NOW).isEmpty(), "one notice per pause");

            String text = parse(transport.messages.get(0)).getContent().toString();
            assertTrue(text.contains("Let me check the refund."), text);
            assertTrue(text.contains(EmailReplySender.PAUSE_NOTICE.substring(0, 40)), text);
            assertTrue(text.contains("A refund over 100 EUR needs a person."), text);
            assertFalse(text.contains("TOOL_CALL"), text);
            assertEquals(List.of(SENDER), transport.recipients);
        }

        @Test
        @DisplayName("a long thread: the notice answers the latest mail, however many ledger rows came before it")
        void pauseNoticeInLongThread() throws Exception {
            for (int i = 0; i < 600; i++) {
                Instant earlier = NOW.minus(Duration.ofDays(2)).plusSeconds(i);
                ledger.put(new LedgerEntry("old-" + i, INTEGRATION, Kind.TRIGGER, Direction.IN, "<old." + i + "@supplier.com>", CONVERSATION,
                        "imap:INBOX:1:" + i, "in:old-" + i, SENDER, State.DONE, 1, null, null, null, null, earlier, earlier,
                        earlier.plus(Duration.ofDays(30))));
            }
            ledger.put(inbound(State.TURN_SUBMITTED));
            paused("A refund over 100 EUR needs a person.");

            assertTrue(sender.onPauseCommitted(CONVERSATION, "1700000000000", NOW).isPresent(), "the latest mail is found");
            assertEquals(List.of(SENDER), transport.recipients);
        }

        @Test
        @DisplayName("a store that cannot be read on a pause retry is retried later, not taken for a resolved pause")
        void pauseRetryStoreFailure() throws Exception {
            ledger.put(inbound(State.TURN_SUBMITTED));
            paused("A refund over 100 EUR needs a person.");
            transport.failures.add(new MailSendException(MailSendException.Category.CONNECT, false, "down", null));
            var row = sender.onPauseCommitted(CONVERSATION, "1700000000000", NOW).orElseThrow();
            Instant due = ledger.findById(row.id()).orElseThrow().nextAttemptAt();
            var stored = conversations.getConversationMemorySnapshot(CONVERSATION);
            doThrow(new IResourceStore.ResourceStoreException("db down")).when(conversations).getConversationMemorySnapshot(CONVERSATION);

            sender.sweep(due.plusSeconds(1));

            assertNotEquals(State.SUPPRESSED, ledger.findById(row.id()).orElseThrow().state(), "a read failure is not a resolved pause");
            assertTrue(transport.recipients.isEmpty());

            doReturn(stored).when(conversations).getConversationMemorySnapshot(CONVERSATION);
            sender.sweep(due.plus(EmailReplySender.LEASE).plusSeconds(2));

            assertEquals(State.SENT, ledger.findById(row.id()).orElseThrow().state());
            assertEquals(List.of(SENDER), transport.recipients);
        }

        @Test
        @DisplayName("a pause on a conversation that is not email, or on another pause id, sends nothing")
        void unrelatedPause() throws Exception {
            paused("x");
            assertTrue(sender.onPauseCommitted(CONVERSATION, "1700000000000", NOW).isEmpty(), "no inbound mail: not an email conversation");
            ledger.put(inbound(State.TURN_SUBMITTED));
            when(conversations.readConversation(CONVERSATION, true, false, List.of()))
                    .thenReturn(snapshot(ConversationState.READY, EmailUserIdentity.perSender(INTEGRATION, SENDER), output("x", mail())));
            assertTrue(sender.onPauseCommitted(CONVERSATION, "1700000000000", NOW).isEmpty(), "no longer paused");
            assertTrue(transport.recipients.isEmpty());
        }

        @Test
        @DisplayName("on resume: the continuation after an approval, a plain notice after a rejection — the approver never named")
        void resume() throws Exception {
            ledger.put(inbound(State.DONE));
            when(conversations.readConversation(CONVERSATION, true, false, List.of())).thenReturn(snapshot(ConversationState.READY,
                    EmailUserIdentity.perSender(INTEGRATION, SENDER), output("Refund of 120 EUR issued.", mail())));

            assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW).isPresent());
            assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW).isEmpty(), "a second observer of the event");
            assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.REJECTED, NOW).isPresent());

            assertTrue(parse(transport.messages.get(0)).getContent().toString().contains("Refund of 120 EUR issued."));
            String rejected = parse(transport.messages.get(1)).getContent().toString();
            assertTrue(rejected.contains("declined"), rejected);
            assertEquals(2, transport.recipients.size());
        }

        @Test
        @DisplayName("resume texts: error, rejection, cancellation, approval without output")
        void resumeTexts() {
            assertTrue(EmailReplySender.resumeText("APPROVED", ConversationState.ERROR, "x").contains("could not be completed"));
            assertTrue(EmailReplySender.resumeText("REJECTED", ConversationState.READY, "x").contains("declined"));
            assertTrue(EmailReplySender.resumeText("NONE", ConversationState.ENDED, "x").contains("cancelled or timed out"));
            assertEquals("A reviewer approved the pending step.", EmailReplySender.resumeText("APPROVED", ConversationState.READY, " "));
        }
    }

    @Nested
    @DisplayName("bounces")
    class Bounces {

        private LedgerEntry sentReply() {
            LedgerEntry inbound = storedInbound(State.DONE);
            return sender.onTurnCompleted(INTEGRATION, settings(), inbound, CONVERSATION, answered("x"), NOW).orElseThrow();
        }

        @Test
        @DisplayName("a permanent failure for the reply's recipient fails the row and suppresses the address")
        void hardBounce() {
            LedgerEntry reply = sentReply();
            var report = new DeliveryReport("delivery-status", "mx.supplier.com", reply.messageId(),
                    List.of(new DeliveryReport.Recipient(SENDER, "failed", "5.1.1", "user unknown")));

            assertTrue(sender.applyDeliveryReport(INTEGRATION, report, NOW));

            assertEquals(State.FAILED, ledger.findById(reply.id()).orElseThrow().state());
            assertEquals("BOUNCED: 5.1.1", ledger.findById(reply.id()).orElseThrow().lastError());
            assertTrue(suppressions.isSuppressed(INTEGRATION, SENDER));
        }

        @Test
        @DisplayName("a report for an id EDDI did not send, another recipient, another integration, or a delay suppresses nobody")
        void ignored() {
            LedgerEntry reply = sentReply();
            assertFalse(sender.applyDeliveryReport(INTEGRATION, new DeliveryReport("delivery-status", null, "<unknown@x.example>",
                    List.of(new DeliveryReport.Recipient(SENDER, "failed", "5.1.1", null))), NOW));
            sender.applyDeliveryReport(INTEGRATION, new DeliveryReport("delivery-status", null, reply.messageId(),
                    List.of(new DeliveryReport.Recipient("victim@example.org", "failed", "5.1.1", null))), NOW);
            assertFalse(sender.applyDeliveryReport("ffffffffffffffffffffffff", new DeliveryReport("delivery-status", null, reply.messageId(),
                    List.of(new DeliveryReport.Recipient(SENDER, "failed", "5.1.1", null))), NOW));
            assertTrue(sender.applyDeliveryReport(INTEGRATION, new DeliveryReport("delivery-status", null, reply.messageId(),
                    List.of(new DeliveryReport.Recipient(SENDER, "delayed", "4.4.7", null))), NOW));
            LedgerEntry notice = new LedgerEntry("notice-1", INTEGRATION, Kind.NOTICE, Direction.OUT, "<eddi.notice@contoso.com>", null, "budget",
                    "notice:1", SENDER, State.SENT, 1, null, null, null, null, NOW, NOW, NOW.plus(Duration.ofDays(30)));
            ledger.put(notice);
            assertFalse(sender.applyDeliveryReport(INTEGRATION, new DeliveryReport("delivery-status", null, notice.messageId(),
                    List.of(new DeliveryReport.Recipient(SENDER, "failed", "5.1.1", null))), NOW), "only a reply's bounce counts");

            assertFalse(suppressions.isSuppressed(INTEGRATION, "victim@example.org"));
            assertFalse(suppressions.isSuppressed(INTEGRATION, SENDER));
            assertEquals(State.SENT, ledger.findById(reply.id()).orElseThrow().state());
            assertEquals("DELAYED: 4.4.7", ledger.findById(reply.id()).orElseThrow().lastError());
        }
    }

    @Nested
    @DisplayName("groups")
    class Groups {

        @Test
        @DisplayName("the synthesis is the reply, once per mail, and is held for this node's retries")
        void synthesis() throws Exception {
            LedgerEntry inbound = storedInbound(State.DONE);
            String user = EmailUserIdentity.perSender(INTEGRATION, SENDER);

            assertTrue(sender.onGroupCompleted(INTEGRATION, settings(), inbound, null, user, mail(), "The panel recommends B.", NOW).isPresent());
            assertTrue(sender.onGroupCompleted(INTEGRATION, settings(), inbound, "gc-1", user, mail(), "The panel recommends B.", NOW).isEmpty());

            assertEquals(List.of(SENDER), transport.recipients);
            assertTrue(parse(transport.messages.get(0)).getContent().toString().contains("The panel recommends B."));
            assertTrue(sender.onGroupCompleted(INTEGRATION, settings(), inbound("in-9", "<q9@supplier.com>", SENDER, State.DONE), "gc-2",
                    EmailUserIdentity.anonymous(), mail("<q9@supplier.com>", SENDER), "x", NOW).isEmpty(), "anonymous: never");
            verify(conversations, never()).readConversation(anyString(), any(), any(), any());
            verify(audit, atLeastOnce()).submit(any());
        }
    }
}
