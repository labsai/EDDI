/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.mail.SenderAuthenticator;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.Disposition;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.IntegrationContext;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailInboundProcessorTest {

    static final String INTEGRATION = "a1b2c3d4e5f60718293a4b5c";
    static final String AGENT = "0a1b2c3d4e5f60718293a4b5";

    InMemoryEmailMessageLedger ledger;
    InMemoryEmailMailboxStateStore stateStore;
    SenderAuthenticator authenticator;
    IConversationService conversations;
    IGroupConversationService groups;
    FakeUserConversations userConversations;
    IAttachmentStore attachmentStore;
    EmailOwnerNotifier ownerNotifier;
    EmailInboundProcessor processor;
    List<ConversationResponseHandler> handlers;
    List<InputData> inputs;
    List<String> sayConversations;
    AtomicInteger conversationCounter;
    MailAuthentication verdict;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ledger = new InMemoryEmailMessageLedger();
        stateStore = new InMemoryEmailMailboxStateStore();
        authenticator = mock(SenderAuthenticator.class);
        verdict = new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "supplier.com");
        when(authenticator.authenticate(any(), any(), any(), any())).thenAnswer(invocation -> verdict);
        conversations = mock(IConversationService.class);
        conversationCounter = new AtomicInteger();
        when(conversations.startConversation(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new ConversationResult("conv-" + conversationCounter.incrementAndGet(), URI.create("eddi://c")));
        when(conversations.getConversationState(anyString())).thenReturn(ConversationState.READY);
        handlers = new ArrayList<>();
        inputs = new ArrayList<>();
        sayConversations = new ArrayList<>();
        doAnswer(invocation -> {
            sayConversations.add(invocation.getArgument(0));
            inputs.add(invocation.getArgument(4));
            handlers.add(invocation.getArgument(6));
            return null;
        }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));
        groups = mock(IGroupConversationService.class);
        userConversations = new FakeUserConversations();
        attachmentStore = mock(IAttachmentStore.class);
        AtomicInteger stored = new AtomicInteger();
        when(attachmentStore.store(any(), anyString(), any(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> new IAttachmentStore.Attachment(
                        "ref-" + stored.incrementAndGet(), invocation.getArgument(2), invocation.getArgument(1), 1, invocation.getArgument(3)));
        Instance<IAttachmentStore> attachments = mock(Instance.class);
        when(attachments.isResolvable()).thenReturn(true);
        when(attachments.get()).thenReturn(attachmentStore);
        ownerNotifier = mock(EmailOwnerNotifier.class);
        processor = new EmailInboundProcessor(ledger, stateStore, new MimeMessageParser(new HtmlToMarkdownConverter()), authenticator, conversations,
                groups, userConversations, new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()), attachments,
                ownerNotifier, new SimpleMeterRegistry(), Duration.ofMinutes(30), Duration.ofDays(30));
    }

    static ChannelIntegrationConfiguration integration(Map<String, String> extra, ChannelTarget... targets) {
        Map<String, String> pc = new HashMap<>();
        pc.put("mode", "trigger");
        pc.put("transport", "imap");
        pc.put("connection", "mailbox");
        pc.put("host", "imap.contoso.com");
        pc.put("address", "invoices@contoso.com");
        pc.putAll(extra);
        var configuration = new ChannelIntegrationConfiguration();
        configuration.setName("Invoices");
        configuration.setChannelType("email");
        configuration.setPlatformConfig(pc);
        List<ChannelTarget> list = new ArrayList<>(List.of(targets));
        if (list.isEmpty()) {
            list.add(target("intake", AGENT));
        }
        configuration.setTargets(list);
        configuration.setDefaultTargetName(list.get(0).getName());
        return configuration;
    }

    static ChannelTarget target(String name, String agentId, String... triggers) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId(agentId);
        target.setTriggers(List.of(triggers));
        return target;
    }

    IntegrationContext context(Map<String, String> extra, ChannelTarget... targets) {
        var configuration = integration(extra, targets);
        return new IntegrationContext(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()),
                Set.of("orders@contoso.com"));
    }

    LedgerEntry row(EmailTestMail mail) {
        return ledger.findByMessage(INTEGRATION, Direction.IN, mail.messageId()).orElseThrow();
    }

    static SimpleConversationMemorySnapshot snapshot(ConversationState state, double cost) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(state);
        snapshot.setConversationCost(cost);
        return snapshot;
    }

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        @DisplayName("an authenticated mail starts one turn as email:<integration>:<address>, recorded RECEIVED -> TURN_SUBMITTED -> DONE")
        void authenticatedTurn() throws Exception {
            EmailTestMail mail = EmailTestMail.mail();
            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), Instant.now()));

            verify(conversations).startConversation(eq(Deployment.Environment.production), eq(AGENT),
                    eq("email:" + INTEGRATION + ":alice@supplier.com"), any());
            assertEquals(1, inputs.size());
            assertTrue(inputs.get(0).getInput().contains("Invoice 4711"));
            assertTrue(inputs.get(0).getInput().contains("Please find the invoice attached."));
            assertTrue(inputs.get(0).getInput().contains("untrusted third-party content"), "the default template frames the mail");
            Context mailContext = inputs.get(0).getContext().get("mail");
            @SuppressWarnings("unchecked")
            Map<String, Object> mailMap = (Map<String, Object>) mailContext.getValue();
            assertEquals("alice@supplier.com", mailMap.get("from"));
            assertEquals("aligned-pass", mailMap.get("authentication"));
            assertFalse(mailMap.containsKey("text"), "the body reaches the turn through the input, not twice");

            LedgerEntry submitted = row(mail);
            assertEquals(State.TURN_SUBMITTED, submitted.state());
            assertEquals("conv-1", submitted.conversationId());
            assertEquals("alice@supplier.com", submitted.recipient());

            handlers.get(0).onComplete(snapshot(ConversationState.READY, 0.25));
            assertEquals(State.DONE, row(mail).state());
            assertEquals(0.25, stateStore.read(INTEGRATION).orElseThrow().spentUsd(), 1e-9, "the turn's cost counts against the day");
        }

        @Test
        @DisplayName("the same mail polled again is settled without a second turn, and without a second download")
        void duplicate() throws Exception {
            EmailTestMail mail = EmailTestMail.mail();
            processor.process(context(Map.of()), mail.polled(1), Instant.now());
            EmailTestMail.Polled again = mail.polled(2);

            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), again, Instant.now()));
            assertEquals(0, again.fetches.get());
            verify(conversations, times(1)).startConversation(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a crash after the claim (row TURN_SUBMITTED, mailbox not marked) never runs the turn again")
        void crashAfterClaim() throws Exception {
            EmailTestMail mail = EmailTestMail.mail();
            Instant now = Instant.now();
            ledger.put(new LedgerEntry("row-1", INTEGRATION, Kind.TRIGGER, Direction.IN, mail.messageId(), null, "imap:INBOX:1:1",
                    EmailInboundProcessor.inboundKey(INTEGRATION, mail.messageId()), "alice@supplier.com", State.TURN_SUBMITTED, 1,
                    now.plusSeconds(600), null, null, null, now, now, now.plus(Duration.ofDays(30))));
            EmailTestMail.Polled polled = new EmailTestMail.Polled(1, mail.bytes(), null, now, false);

            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), polled, now));
            verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a crash after the claim but before the point of no return (stale CLAIMED): the mail is processed again, once")
        void staleClaimIsRequeued() throws Exception {
            EmailTestMail mail = EmailTestMail.mail();
            Instant now = Instant.now();
            ledger.put(new LedgerEntry("row-1", INTEGRATION, Kind.TRIGGER, Direction.IN, mail.messageId(), null, "imap:INBOX:1:1",
                    EmailInboundProcessor.inboundKey(INTEGRATION, mail.messageId()), "alice@supplier.com", State.CLAIMED, 1,
                    now.minusSeconds(1), null, null, null, now, now, now.plus(Duration.ofDays(30))));

            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), now));

            verify(conversations, times(1)).startConversation(any(), anyString(), anyString(), any());
            assertEquals(1, inputs.size());
            assertEquals(State.TURN_SUBMITTED, row(mail).state());
            assertEquals(2, row(mail).attempts(), "the new claim is counted");
        }

        @Test
        @DisplayName("a live claim of another poll is left alone: not downloaded, not marked, no turn")
        void liveClaimIsLeftAlone() throws Exception {
            EmailTestMail mail = EmailTestMail.mail();
            Instant now = Instant.now();
            ledger.put(new LedgerEntry("row-1", INTEGRATION, Kind.TRIGGER, Direction.IN, mail.messageId(), null, "imap:INBOX:1:1",
                    EmailInboundProcessor.inboundKey(INTEGRATION, mail.messageId()), "alice@supplier.com", State.CLAIMED, 1,
                    now.plusSeconds(600), null, null, null, now, now, now.plus(Duration.ofDays(30))));
            EmailTestMail.Polled polled = mail.polled(1);

            assertEquals(Disposition.DEFERRED, processor.process(context(Map.of()), polled, now));
            assertEquals(0, polled.fetches.get());
            verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("routing: the plus-tag wins, then a subject word, then the default")
        void routing() throws Exception {
            String billing = "0b0b0b0b0b0b0b0b0b0b0b0b";
            String urgent = "0c0c0c0c0c0c0c0c0c0c0c0c";
            var ctx = context(Map.of(), target("intake", AGENT), target("billing", billing, "billing"), target("urgent", urgent, "urgent"));

            processor.process(ctx, EmailTestMail.mail().to("invoices+billing@contoso.com").subject("urgent invoice").polled(1), Instant.now());
            processor.process(ctx, EmailTestMail.mail().subject("URGENT: invoice").polled(2), Instant.now());
            processor.process(ctx, EmailTestMail.mail().subject("hello").polled(3), Instant.now());

            ArgumentCaptor<String> agents = ArgumentCaptor.forClass(String.class);
            verify(conversations, times(3)).startConversation(any(), agents.capture(), anyString(), any());
            assertEquals(List.of(billing, urgent, AGENT), agents.getAllValues());
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("a temporary verdict leaves the mail for the next poll — not refused, not marked, no turn")
        void temporaryDefers() throws Exception {
            verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null, "dns", true, List.of());
            EmailTestMail mail = EmailTestMail.mail();
            Instant now = Instant.now();

            assertEquals(Disposition.DEFERRED, processor.process(context(Map.of()), mail.polled(1), now));
            assertEquals(State.RECEIVED, row(mail).state());
            assertEquals("auth_temporary", row(mail).lastError());
            verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());

            EmailTestMail.Polled again = mail.polled(1);
            assertEquals(Disposition.DEFERRED, processor.process(context(Map.of()), again, now.plusSeconds(60)), "not due yet");
            assertEquals(0, again.fetches.get(), "a mail that is not due is not downloaded again");

            verdict = new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "supplier.com");
            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), now.plus(Duration.ofMinutes(6))));
            verify(conversations).startConversation(any(), eq(AGENT), eq("email:" + INTEGRATION + ":alice@supplier.com"), any());
        }

        @Test
        @DisplayName("requireAuthentication (the default) refuses an unauthenticated sender, and nothing is answered")
        void unauthenticatedRefused() throws Exception {
            verdict = new MailAuthentication(MailAuthentication.Verdict.NONE, null);
            EmailTestMail mail = EmailTestMail.mail();

            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), Instant.now()));
            assertEquals(State.REFUSED, row(mail).state());
            assertEquals("unauthenticated:none", row(mail).lastError());
            verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("without requireAuthentication an unauthenticated sender runs under a one-off email-anon id, never correlated")
        void unauthenticatedAnonymous() throws Exception {
            verdict = new MailAuthentication(MailAuthentication.Verdict.PASS_UNALIGNED, "bulkmailer.example");
            var ctx = context(Map.of("requireAuthentication", "false", "correlateThreads", "true"));
            String root = "<root@supplier.com>";

            processor.process(ctx, EmailTestMail.mail().messageId(root).polled(1), Instant.now());
            processor.process(ctx, EmailTestMail.mail().header("References: " + root).polled(2), Instant.now());

            ArgumentCaptor<String> users = ArgumentCaptor.forClass(String.class);
            verify(conversations, times(2)).startConversation(any(), anyString(), users.capture(), any());
            assertTrue(users.getAllValues().get(0).startsWith("email-anon:"));
            assertTrue(users.getAllValues().get(1).startsWith("email-anon:"));
            assertNotEquals(users.getAllValues().get(0), users.getAllValues().get(1), "one-off ids");
            assertTrue(userConversations.mappings.isEmpty(), "an unauthenticated mail never maps a thread");
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("loop: an auto-reply is refused, counted and never answered")
        void autoReply() throws Exception {
            EmailTestMail mail = EmailTestMail.mail().header("Auto-Submitted: auto-replied");
            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), Instant.now()));
            assertEquals("loop:auto_submitted", row(mail).lastError());
            verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("loop: another integration's address, and a reply to mail EDDI sent")
        void otherIntegrationAndReplyToEddi() throws Exception {
            EmailTestMail fromOrders = EmailTestMail.mail().from("orders@contoso.com");
            processor.process(context(Map.of()), fromOrders.polled(1), Instant.now());
            assertEquals("loop:other_integration", row(fromOrders).lastError());

            Instant now = Instant.now();
            ledger.insertIfAbsent(new LedgerEntry("n-1", "system", Kind.NOTIFY, Direction.OUT, "<eddi.123@contoso.com>", "c", "p", "notify:x",
                    "approver@contoso.com", State.SENT, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(1))));
            EmailTestMail reply = EmailTestMail.mail().header("In-Reply-To: <eddi.123@contoso.com>");
            processor.process(context(Map.of()), reply.polled(2), now);
            assertEquals("loop:refers_to_eddi_mail", row(reply).lastError());
            verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a refusal is audited by reason category, with the sender only as domain and hash")
        @SuppressWarnings("unchecked")
        void refusalAudited() {
            AuditLedgerService audit = mock(AuditLedgerService.class);
            when(audit.isEnabled()).thenReturn(true);
            Instance<AuditLedgerService> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(true);
            when(instance.get()).thenReturn(audit);
            processor.auditLedger = instance;

            processor.process(context(Map.of("senderAllowlist", "*@other.com")), EmailTestMail.mail().polled(1), Instant.now());

            ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).submit(entry.capture());
            assertEquals("email.inbound", entry.getValue().taskId());
            assertEquals("blocked", entry.getValue().output().get("reason"));
            assertEquals("supplier.com", entry.getValue().output().get("senderDomain"));
            assertFalse(entry.getValue().output().toString().contains("alice@"), "no address in the write-once audit ledger");
        }

        @Test
        @DisplayName("the integration's own address is always refused")
        void ownAddress() throws Exception {
            EmailTestMail mail = EmailTestMail.mail().from("invoices@contoso.com");
            processor.process(context(Map.of()), mail.polled(1), Instant.now());
            assertEquals(State.REFUSED, row(mail).state());
            assertTrue(row(mail).lastError().startsWith("loop:own_address"));
        }

        @Test
        @DisplayName("allowlist and blocklist")
        void lists() throws Exception {
            EmailTestMail stranger = EmailTestMail.mail().from("eve@elsewhere.com");
            processor.process(context(Map.of("senderAllowlist", "*@supplier.com")), stranger.polled(1), Instant.now());
            assertEquals("blocked:not_allowlisted", row(stranger).lastError());

            EmailTestMail blocked = EmailTestMail.mail().from("spam@supplier.com");
            processor.process(context(Map.of("senderAllowlist", "*@supplier.com", "senderBlocklist", "spam@supplier.com")), blocked.polled(2),
                    Instant.now());
            assertEquals("blocked:blocklist", row(blocked).lastError());

            EmailTestMail welcome = EmailTestMail.mail().from("bob@supplier.com");
            assertEquals(Disposition.SETTLED,
                    processor.process(context(Map.of("senderAllowlist", "*@supplier.com")), welcome.polled(3), Instant.now()));
            assertEquals(State.TURN_SUBMITTED, row(welcome).state());
        }

        @Test
        @DisplayName("an oversize mail is refused from RFC822.SIZE, before it is downloaded")
        void oversize() {
            EmailTestMail mail = EmailTestMail.mail();
            EmailTestMail.Polled polled = mail.polled(1);
            polled.reportedSize = 10_000_000;

            assertEquals(Disposition.SETTLED, processor.process(context(Map.of("maxMessageBytes", "5000")), polled, Instant.now()));
            assertEquals(0, polled.fetches.get());
            assertEquals("too_large", row(mail).lastError());
        }
    }

    @Nested
    @DisplayName("budgets")
    class Budgets {

        @Test
        @DisplayName("the per-sender daily limit defers further mail to the next UTC day and notifies the owners")
        void perSender() throws Exception {
            var ctx = context(Map.of("maxTurnsPerSenderPerDay", "1"));
            processor.process(ctx, EmailTestMail.mail().polled(1), Instant.now());
            EmailTestMail second = EmailTestMail.mail();

            assertEquals(Disposition.DEFERRED, processor.process(ctx, second.polled(2), Instant.now()));
            assertEquals("throttled", row(second).lastError());
            assertTrue(row(second).nextAttemptAt().isAfter(Instant.now().plus(Duration.ofMinutes(1))));
            verify(ownerNotifier).notifyOnce(eq(INTEGRATION), any(), any(), eq(EmailOwnerNotifier.Reason.THROTTLED), any());
            verify(conversations, times(1)).startConversation(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a spent daily cost budget stops the batch and leaves the mail for later")
        void dailyBudget() throws Exception {
            Instant now = Instant.now();
            stateStore.addSpend(INTEGRATION, EmailInboundProcessor.startOfUtcDay(now).toString().substring(0, 10), 5.0, now);
            EmailTestMail mail = EmailTestMail.mail();

            assertEquals(Disposition.STOP, processor.process(context(Map.of("dailyCostBudgetUsd", "5")), mail.polled(1), now));
            assertEquals(State.RECEIVED, row(mail).state());
            verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
            verify(ownerNotifier).notifyOnce(eq(INTEGRATION), any(), any(), eq(EmailOwnerNotifier.Reason.BUDGET), any());
        }

        @Test
        @DisplayName("a tenant quota refusal gives the claim back and stops the batch")
        void quota() throws Exception {
            when(conversations.startConversation(any(), anyString(), anyString(), any())).thenThrow(new QuotaExceededException("tenant over quota"));
            EmailTestMail mail = EmailTestMail.mail();

            assertEquals(Disposition.STOP, processor.process(context(Map.of()), mail.polled(1), Instant.now()));
            assertEquals(State.RECEIVED, row(mail).state());
            assertEquals("quota", row(mail).lastError());
        }

        @Test
        @DisplayName("a turn refused after its conversation started is retried in that conversation: CONVERSATION_START runs once")
        void retryAfterStartReusesTheConversation() throws Exception {
            AtomicInteger says = new AtomicInteger();
            doAnswer(invocation -> {
                if (says.incrementAndGet() == 1) {
                    throw new QuotaExceededException("tenant over its API-call rate");
                }
                sayConversations.add(invocation.getArgument(0));
                handlers.add(invocation.getArgument(6));
                return null;
            }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(),
                    any(ConversationResponseHandler.class));
            EmailTestMail mail = EmailTestMail.mail();
            Instant now = Instant.now();

            assertEquals(Disposition.STOP, processor.process(context(Map.of()), mail.polled(1), now));
            assertEquals(State.RECEIVED, row(mail).state());
            assertEquals("conv-1", row(mail).conversationId());

            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), now.plus(Duration.ofMinutes(6))));
            verify(conversations, times(1)).startConversation(any(), anyString(), anyString(), any());
            assertEquals(List.of("conv-1"), sayConversations);
            assertEquals(State.TURN_SUBMITTED, row(mail).state());
        }

        @Test
        @DisplayName("a retry whose earlier conversation has ended since starts a new one")
        void retryAfterStartEndedConversation() throws Exception {
            AtomicInteger says = new AtomicInteger();
            doAnswer(invocation -> {
                if (says.incrementAndGet() == 1) {
                    throw new QuotaExceededException("tenant over its API-call rate");
                }
                sayConversations.add(invocation.getArgument(0));
                return null;
            }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(),
                    any(ConversationResponseHandler.class));
            EmailTestMail mail = EmailTestMail.mail();
            Instant now = Instant.now();
            processor.process(context(Map.of()), mail.polled(1), now);
            when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.ENDED);

            assertEquals(Disposition.SETTLED, processor.process(context(Map.of()), mail.polled(1), now.plus(Duration.ofMinutes(6))));
            assertEquals(List.of("conv-2"), sayConversations);
        }
    }

    @Nested
    @DisplayName("threads (correlateThreads)")
    class Threads {

        @Test
        @DisplayName("hijack guard, identityMode integration: a continuation from someone else starts their own conversation")
        void hijackSharedIdentity() throws Exception {
            var ctx = context(Map.of("correlateThreads", "true", "identityMode", "integration"));
            assertHijackGuard(ctx);
        }

        @Test
        @DisplayName("hijack guard, identityMode perSender")
        void hijackPerSender() throws Exception {
            assertHijackGuard(context(Map.of("correlateThreads", "true")));
        }

        private void assertHijackGuard(IntegrationContext ctx) throws Exception {
            String root = "<root-" + System.nanoTime() + "@supplier.com>";
            processor.process(ctx, EmailTestMail.mail().messageId(root).polled(1), Instant.now());
            handlers.get(0).onComplete(snapshot(ConversationState.READY, 0));

            // Mallory knows the Message-ID (it travels in every forward) and sends a
            // "correction".
            processor.process(ctx, EmailTestMail.mail().from("mallory@supplier.com").header("References: " + root).polled(2), Instant.now());
            // Alice really continues her thread.
            processor.process(ctx, EmailTestMail.mail().header("In-Reply-To: " + root).header("References: " + root).polled(3), Instant.now());

            assertEquals(List.of("conv-1", "conv-2", "conv-1"), sayConversations);
        }

        @Test
        @DisplayName("each mail is its own conversation by default")
        void defaultNewConversation() throws Exception {
            String root = "<r@supplier.com>";
            processor.process(context(Map.of()), EmailTestMail.mail().messageId(root).polled(1), Instant.now());
            processor.process(context(Map.of()), EmailTestMail.mail().header("References: " + root).polled(2), Instant.now());
            assertEquals(List.of("conv-1", "conv-2"), sayConversations);
        }
    }

    @Nested
    @DisplayName("attachments")
    class Attachments {

        @Test
        @DisplayName("store: allowed types reach the attachment store and the turn; executables never, whatever the allowlist")
        void executablesNeverStored() throws Exception {
            var ctx = context(
                    Map.of("attachments", "store", "attachmentMimeTypes", "application/pdf,application/octet-stream,application/x-msdownload"));
            EmailTestMail mail = EmailTestMail.mail().attachment("invoice.pdf", "application/pdf", "%PDF-1.7\n1 0 obj\n")
                    .attachment("invoice.exe", "application/octet-stream", "MZ\u0090\u0000payload");

            processor.process(ctx, mail.polled(1), Instant.now());

            verify(attachmentStore, times(1)).store(any(), eq("application/pdf"), eq("invoice.pdf"), eq("conv-1"), any(), anyString());
            verify(attachmentStore, never()).store(any(), anyString(), eq("invoice.exe"), anyString(), any(), anyString());
            Map<String, Context> context = inputs.get(0).getContext();
            assertTrue(context.containsKey("attachment_0"));
            assertFalse(context.containsKey("attachment_1"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> described = (List<Map<String, Object>>) ((Map<String, Object>) context.get("mail").getValue())
                    .get("attachments");
            assertEquals("ref-1", described.get(0).get("attachmentId"));
            assertEquals("executable", described.get(1).get("skipped"));
        }

        @Test
        @DisplayName("drop (the default) passes nothing on")
        void dropByDefault() throws Exception {
            processor.process(context(Map.of()), EmailTestMail.mail().attachment("invoice.pdf", "application/pdf", "%PDF-1.7\n").polled(1),
                    Instant.now());
            verify(attachmentStore, never()).store(any(), anyString(), any(), anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("mail is data")
    class MailIsData {

        @Test
        @DisplayName("Qute syntax in a subject or body is printed, never evaluated")
        void templateSyntaxIsData() throws Exception {
            EmailTestMail mail = EmailTestMail.mail().subject("{mail.from} {#include secrets /}")
                    .body("{config:quarkus.datasource.password} {#eval x/}");
            processor.process(context(Map.of("inputTemplate", "S={mail.subject} B={mail.text}")), mail.polled(1), Instant.now());

            String input = inputs.get(0).getInput();
            assertTrue(input.contains("S={mail.from} {#include secrets /}"), input);
            assertTrue(input.contains("B={config:quarkus.datasource.password} {#eval x/}"), input);
        }

        @Test
        @DisplayName("a template that fails to render falls back to subject and text, joined without a template")
        void brokenTemplateFallsBack() {
            String rendered = processor.renderInput("{#if}", Map.of("subject", "Hi {x}", "text", "body"));
            assertEquals("Hi {x}\n\nbody", rendered);
        }
    }

    @Test
    @DisplayName("an existing row's disposition: not due = deferred, due = process, anything past RECEIVED = settled")
    void dispositionOfRows() {
        Instant now = Instant.now();
        assertEquals(Disposition.DEFERRED, EmailInboundProcessor.dispositionOf(rowIn(State.RECEIVED, now.plusSeconds(60)), now));
        assertNull(EmailInboundProcessor.dispositionOf(rowIn(State.RECEIVED, now.minusSeconds(1)), now));
        assertNull(EmailInboundProcessor.dispositionOf(rowIn(State.RECEIVED, null), now));
        for (State state : List.of(State.TURN_SUBMITTED, State.DONE, State.REFUSED)) {
            assertEquals(Disposition.SETTLED, EmailInboundProcessor.dispositionOf(rowIn(state, null), now), state.name());
        }
    }

    private static LedgerEntry rowIn(State state, Instant next) {
        Instant now = Instant.now();
        return new LedgerEntry("x", INTEGRATION, Kind.TRIGGER, Direction.IN, "<m@x.com>", null, null, "k", null, state, 0, next, null, null, null,
                now,
                now, now.plusSeconds(60));
    }

    /** Thread mappings, as the Mongo/Postgres stores keep them. */
    static final class FakeUserConversations implements IUserConversationStore {
        final Map<String, UserConversation> mappings = new HashMap<>();

        private static String key(String intent, String userId) {
            return intent + "|" + userId;
        }

        @Override
        public UserConversation readUserConversation(String intent, String userId) {
            return mappings.get(key(intent, userId));
        }

        @Override
        public UserConversation readUserConversationByConversationId(String conversationId) {
            return mappings.values().stream().filter(m -> conversationId.equals(m.getConversationId())).findFirst().orElse(null);
        }

        @Override
        public void createUserConversation(UserConversation userConversation) throws IResourceStore.ResourceAlreadyExistsException {
            String key = key(userConversation.getIntent(), userConversation.getUserId());
            if (mappings.containsKey(key)) {
                throw new IResourceStore.ResourceAlreadyExistsException("exists");
            }
            mappings.put(key, userConversation);
        }

        @Override
        public void deleteUserConversation(String intent, String userId) {
            mappings.remove(key(intent, userId));
        }

        @Override
        public boolean deleteUserConversationIfMatches(String intent, String userId, String conversationId) {
            UserConversation existing = mappings.get(key(intent, userId));
            if (existing != null && conversationId.equals(existing.getConversationId())) {
                mappings.remove(key(intent, userId));
                return true;
            }
            return false;
        }

        @Override
        public long deleteAllForUser(String userId) {
            return 0;
        }

        @Override
        public List<UserConversation> getAllForUser(String userId) {
            return List.of();
        }
    }
}
