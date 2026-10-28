/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.notify;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.HitlNotifyConfig;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.PlainTextMailRenderer;
import ai.labs.eddi.engine.mail.SystemMailer;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notifier's flow with the ledger in memory and the mailer mocked: one mail
 * per pause and recipient, retries with backoff, terminal states, what the mail
 * and the audit entry contain, and what they must never contain.
 */
class HitlEmailNotifierTest {

    private static final String CONVERSATION = "conv-42";
    private static final Instant PAUSED_AT = Instant.parse("2026-10-24T10:00:00Z");
    private static final String PAUSE = HitlDecision.pauseIdOf(PAUSED_AT);
    private static final Instant NOW = PAUSED_AT.plusSeconds(1);
    private static final MailAddress FROM = new MailAddress("eddi@example.com", "EDDI");
    private static final String USER_TEXT = "please wire 10000 to my private account";

    private InMemoryEmailMessageLedger ledger;
    private SystemMailer mailer;
    private IConversationMemoryStore conversations;
    private IAgentStore agents;
    private IDocumentDescriptorStore descriptors;
    private AuditLedgerService audit;
    private GdprPseudonymizer pseudonymizer;
    private SimpleMeterRegistry meters;
    private ConversationMemorySnapshot snapshot;
    private AgentConfiguration agent;
    private ConnectionsConfig connectionsConfig;
    private final List<PlainTextMailRenderer.RenderedMail> sent = new CopyOnWriteArrayList<>();
    private List<String> allowedDomains = List.of();
    private int maxPerHour = 30;

    @BeforeEach
    void setUp() throws Exception {
        ledger = new InMemoryEmailMessageLedger();
        mailer = mock(SystemMailer.class);
        when(mailer.isEnabled()).thenReturn(true);
        when(mailer.from()).thenReturn(Optional.of(FROM));
        doAnswer(i -> {
            sent.add(i.getArgument(0));
            return new SystemMailer.SendResult("250 2.0.0 queued");
        }).when(mailer).send(any(), anyList());
        conversations = mock(IConversationMemoryStore.class);
        agents = mock(IAgentStore.class);
        descriptors = mock(IDocumentDescriptorStore.class);
        audit = mock(AuditLedgerService.class);
        when(audit.isEnabled()).thenReturn(true);
        pseudonymizer = mock(GdprPseudonymizer.class);
        // A stand-in keyed token: stable per address, and never the address itself.
        when(pseudonymizer.pseudonymFor(anyString()))
                .thenAnswer(i -> "gdpr-erased:k1:" + Integer.toHexString(i.getArgument(0, String.class).hashCode()));
        meters = new SimpleMeterRegistry();
        connectionsConfig = mock(ConnectionsConfig.class);
        when(connectionsConfig.getPublicBaseUrl()).thenReturn("https://eddi.example.com");
        when(connectionsConfig.publicBaseUrlProblem()).thenReturn(Optional.empty());

        snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(3);
        snapshot.setHitlPausedAt(PAUSED_AT);
        snapshot.setHitlPauseReason("Refunds over 500 need a manager");
        snapshot.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        snapshot.setHitlApprovalTimeout("PT2H");
        snapshot.setHitlPauseType("RULE");
        when(conversations.getConversationState(CONVERSATION)).thenReturn(ConversationState.AWAITING_HUMAN);
        when(conversations.loadConversationMemorySnapshot(CONVERSATION)).thenReturn(snapshot);

        agent = new AgentConfiguration();
        agent.setHitlConfig(new AgentConfiguration.HitlConfig());
        notifyTo("ops@example.com", "Manager@Example.com");
        when(agents.read("agent-1", 3)).thenReturn(agent);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Refund desk");
        when(descriptors.readDescriptor("agent-1", 3)).thenReturn(descriptor);
    }

    private void notifyTo(String... recipients) {
        var email = new HitlNotifyConfig.Email();
        email.setRecipients(List.of(recipients));
        var notify = new HitlNotifyConfig();
        notify.setEmail(email);
        agent.getHitlConfig().setNotify(notify);
    }

    private HitlEmailNotifier notifier(int maxAttempts) {
        @SuppressWarnings("unchecked")
        Instance<ConnectionsConfig> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(connectionsConfig);
        var notifier = new HitlEmailNotifier(mailer, ledger, conversations, agents, descriptors, instance, audit, pseudonymizer, meters,
                Duration.ofDays(30),
                maxAttempts, Duration.ofMinutes(1), Optional.of(allowedDomains), maxPerHour);
        notifier.registerGauge();
        return notifier;
    }

    private double outbound(String outcome) {
        return meters.get("eddi.email.outbound").tag("kind", "notify").tag("outcome", outcome).counter().count();
    }

    private static String body(PlainTextMailRenderer.RenderedMail mail) throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mail.mime()));
        return message.getContent().toString().replaceAll("\\s+", " ");
    }

    private static String subject(PlainTextMailRenderer.RenderedMail mail) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mail.mime())).getSubject();
    }

    @Test
    @DisplayName("a pause mails each recipient once, records SENT, counts it and audits it")
    void happyPath() throws Exception {
        notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

        assertEquals(2, sent.size());
        assertEquals(2, ledger.all().stream().filter(r -> r.state() == State.SENT).count());
        assertEquals(List.of("manager@example.com", "ops@example.com"), ledger.all().stream().map(LedgerEntry::recipient).sorted().toList());
        assertEquals(2.0, outbound("sent"));
        assertTrue(ledger.all().stream().allMatch(r -> "250 2.0.0 queued".equals(r.providerId())));
        verify(audit, times(2)).submit(any(AuditEntry.class));
    }

    @Test
    @DisplayName("the same pause observed twice — or on two pods sharing the ledger — sends once per recipient")
    void idempotent() throws Exception {
        var podA = notifier(6);
        var podB = notifier(6);

        podA.notifyPause(CONVERSATION, PAUSE, NOW);
        podB.notifyPause(CONVERSATION, PAUSE, NOW);
        podA.notifyPause(CONVERSATION, PAUSE, NOW.plusSeconds(5));

        assertEquals(2, sent.size());
        assertEquals(2, ledger.all().size());
    }

    @Test
    @DisplayName("one recipient's store failure does not cost the other recipients their notification")
    void recipientsAreIsolated() throws Exception {
        ledger = new InMemoryEmailMessageLedger() {
            @Override
            public synchronized long countCreatedSince(String recipient, Kind kind, Instant since) {
                if ("ops@example.com".equals(recipient)) {
                    throw new IllegalStateException("connection reset");
                }
                return super.countCreatedSince(recipient, kind, since);
            }
        };

        notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

        assertEquals(List.of("manager@example.com"), ledger.all().stream().map(LedgerEntry::recipient).toList());
        assertEquals(1, sent.size());
    }

    @Test
    @DisplayName("a new pause of the same conversation is a new notification")
    void newPauseNotifiesAgain() throws Exception {
        notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);
        Instant secondPause = PAUSED_AT.plusSeconds(600);
        snapshot.setHitlPausedAt(secondPause);

        notifier(6).notifyPause(CONVERSATION, HitlDecision.pauseIdOf(secondPause), secondPause.plusSeconds(1));

        assertEquals(4, sent.size());
    }

    @Test
    @DisplayName("nothing happens without a mailer, without notify config, or once the pause is gone")
    void nothingToDo() throws Exception {
        when(mailer.isEnabled()).thenReturn(false);
        notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);
        when(mailer.isEnabled()).thenReturn(true);

        agent.getHitlConfig().setNotify(null);
        notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);
        notifyTo("ops@example.com");

        notifier(6).notifyPause(CONVERSATION, "not-the-current-pause", NOW);
        when(conversations.getConversationState(CONVERSATION)).thenReturn(ConversationState.READY);
        notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

        assertTrue(sent.isEmpty());
        assertTrue(ledger.all().isEmpty());
        verify(mailer, never()).send(any(), anyList());
    }

    @Nested
    @DisplayName("retries")
    class Retries {

        @Test
        @DisplayName("a transient failure leaves the row PENDING with backoff; the sweep resends it with the same Message-ID")
        void transientThenSent() throws Exception {
            notifyTo("ops@example.com");
            doThrow(new MailSendException(MailSendException.Category.CONNECT, false, "refused", null)).doAnswer(i -> {
                sent.add(i.getArgument(0));
                return new SystemMailer.SendResult("250 ok");
            }).when(mailer).send(any(), anyList());
            var notifier = notifier(6);

            notifier.notifyPause(CONVERSATION, PAUSE, NOW);

            LedgerEntry row = ledger.all().get(0);
            assertEquals(State.PENDING, row.state());
            assertEquals(NOW.plus(Duration.ofMinutes(1)), row.nextAttemptAt());
            assertNull(row.claimToken());
            assertTrue(row.lastError().startsWith("CONNECT"));
            assertEquals(1.0, outbound("retry"));

            notifier.sweep(NOW.plusSeconds(30));
            assertTrue(sent.isEmpty(), "not due yet");
            notifier.sweep(NOW.plus(Duration.ofMinutes(1)));

            assertEquals(1, sent.size());
            assertEquals(row.messageId(), sent.get(0).messageId());
            assertEquals(State.SENT, ledger.all().get(0).state());
            assertEquals(2, ledger.all().get(0).attempts());
            assertEquals(0.0, notifier.pendingGauge());
        }

        @Test
        @DisplayName("an unreachable relay ends FAILED after max-attempts, audited once, with growing backoff")
        void failsAfterMaxAttempts() throws Exception {
            notifyTo("ops@example.com");
            doThrow(new MailSendException(MailSendException.Category.CONNECT, false, "refused", null)).when(mailer).send(any(), anyList());
            var notifier = notifier(3);

            notifier.notifyPause(CONVERSATION, PAUSE, NOW);
            Instant second = ledger.all().get(0).nextAttemptAt();
            notifier.sweep(second);
            Instant third = ledger.all().get(0).nextAttemptAt();
            assertEquals(Duration.ofMinutes(2), Duration.between(second, third), "backoff doubles");
            assertEquals(State.PENDING, ledger.all().get(0).state());
            notifier.sweep(third);

            LedgerEntry row = ledger.all().get(0);
            assertEquals(State.FAILED, row.state());
            assertEquals(3, row.attempts());
            assertEquals(1.0, outbound("failed"));
            assertEquals(2.0, outbound("retry"));
            verify(mailer, times(3)).send(any(), anyList());
            ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).submit(captor.capture());
            assertEquals("failed", captor.getValue().output().get("outcome"));

            notifier.sweep(third.plus(Duration.ofHours(2)));
            verify(mailer, times(3)).send(any(), anyList());
        }

        @Test
        @DisplayName("a permanent 5xx refusal fails at once")
        void permanentFailsAtOnce() throws Exception {
            notifyTo("ops@example.com");
            doThrow(new MailSendException(MailSendException.Category.REJECTED, true, "550", null)).when(mailer).send(any(), anyList());

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            assertEquals(State.FAILED, ledger.all().get(0).state());
            assertEquals(1, ledger.all().get(0).attempts());
        }

        @Test
        @DisplayName("a send failure that is not a MailSendException is an attempt: retried, then FAILED at max-attempts")
        void unexpectedSendFailureCountsAsAttempt() throws Exception {
            notifyTo("ops@example.com");
            doThrow(new IllegalArgumentException("bad allowed-hosts entry")).when(mailer).send(any(), anyList());
            var notifier = notifier(2);

            notifier.notifyPause(CONVERSATION, PAUSE, NOW);
            LedgerEntry first = ledger.all().get(0);
            assertEquals(State.PENDING, first.state());
            assertTrue(first.lastError().startsWith("SEND: IllegalArgumentException"), first.lastError());
            assertEquals(1.0, outbound("retry"));
            notifier.sweep(first.nextAttemptAt());

            LedgerEntry row = ledger.all().get(0);
            assertEquals(State.FAILED, row.state());
            assertEquals(2, row.attempts());
            assertEquals(1.0, outbound("failed"));
        }

        @Test
        @DisplayName("a pause decided before the retry is SUPPRESSED, not mailed")
        void decidedBeforeRetry() throws Exception {
            notifyTo("ops@example.com");
            doThrow(new MailSendException(MailSendException.Category.CONNECT, false, "refused", null)).when(mailer).send(any(), anyList());
            var notifier = notifier(6);
            notifier.notifyPause(CONVERSATION, PAUSE, NOW);
            when(conversations.getConversationState(CONVERSATION)).thenReturn(ConversationState.READY);

            notifier.sweep(NOW.plus(Duration.ofMinutes(5)));

            assertEquals(State.SUPPRESSED, ledger.all().get(0).state());
            assertEquals(1.0, outbound("suppressed"));
            verify(mailer, times(1)).send(any(), anyList());
        }

        @Test
        @DisplayName("a pause that cannot be read on a retry is retried with backoff, then FAILED at max-attempts — never SUPPRESSED")
        void unreadableBeforeRetry() throws Exception {
            notifyTo("ops@example.com");
            doThrow(new MailSendException(MailSendException.Category.CONNECT, false, "refused", null)).doAnswer(i -> {
                sent.add(i.getArgument(0));
                return new SystemMailer.SendResult("250 ok");
            }).when(mailer).send(any(), anyList());
            var notifier = notifier(6);
            notifier.notifyPause(CONVERSATION, PAUSE, NOW);
            var snapshot = conversations.loadConversationMemorySnapshot(CONVERSATION);
            doThrow(new IResourceStore.ResourceStoreException("db down")).when(conversations).loadConversationMemorySnapshot(CONVERSATION);

            notifier.sweep(NOW.plus(Duration.ofMinutes(1)));

            LedgerEntry row = ledger.all().get(0);
            assertEquals(State.PENDING, row.state(), "an unreadable pause is not a resolved one");
            assertTrue(row.lastError().startsWith("UNREADABLE"), row.lastError());
            assertEquals(0.0, outbound("suppressed"));

            doReturn(snapshot).when(conversations).loadConversationMemorySnapshot(CONVERSATION);
            notifier.sweep(row.nextAttemptAt());
            assertEquals(State.SENT, ledger.all().get(0).state(), "sent once the store answers again");
            assertEquals(1, sent.size());

            ledger = new InMemoryEmailMessageLedger();
            doThrow(new MailSendException(MailSendException.Category.CONNECT, false, "refused", null)).when(mailer).send(any(), anyList());
            var bounded = notifier(2);
            bounded.notifyPause(CONVERSATION, PAUSE, NOW);
            assertEquals(State.PENDING, ledger.all().get(0).state());
            doThrow(new IResourceStore.ResourceStoreException("still down")).when(conversations).loadConversationMemorySnapshot(CONVERSATION);
            bounded.sweep(ledger.all().get(0).nextAttemptAt());
            assertEquals(State.FAILED, ledger.all().get(0).state(), "bounded by max-attempts");
        }

        @Test
        @DisplayName("backoff doubles from the configured base and stops at one hour")
        void backoffCapped() {
            var notifier = notifier(20);
            assertEquals(Duration.ofMinutes(1), notifier.backoff(1));
            assertEquals(Duration.ofMinutes(4), notifier.backoff(3));
            assertEquals(Duration.ofHours(1), notifier.backoff(12));
        }
    }

    @Nested
    @DisplayName("what the mail says")
    class Content {

        @Test
        @DisplayName("agent, pause, reason, deadline and the approvals link — and no conversation content")
        void ruleContent() throws Exception {
            notifyTo("ops@example.com");

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            String body = body(sent.get(0));
            assertEquals("[EDDI] Approval needed: Refund desk", subject(sent.get(0)));
            assertTrue(body.contains("Agent: Refund desk"), body);
            assertTrue(body.contains("Refunds over 500 need a manager"), body);
            assertTrue(body.contains("2026-10-24 12:00 UTC, then it is rejected automatically"), body);
            assertTrue(body.contains("Conversation: conv-42"), body);
            assertTrue(body.contains("https://eddi.example.com/manage/approvals "), body);
            assertFalse(body.contains(USER_TEXT));
            assertFalse(body.contains("?"), "the link carries no query, so no token: " + body);
        }

        @Test
        @DisplayName("a valid timeout past the end of the time-line still mails, naming the duration instead of a date")
        void deadlineBeyondInstantRange() throws Exception {
            notifyTo("ops@example.com");
            snapshot.setHitlApprovalTimeout("P1000000000000D");

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            assertEquals(1, sent.size());
            assertEquals(State.SENT, ledger.all().get(0).state());
            String body = body(sent.get(0));
            assertTrue(body.contains("after P1000000000000D, then it is rejected automatically"), body);
        }

        @Test
        @DisplayName("an unexpected render failure marks the row FAILED instead of leaving it claimed")
        void unexpectedRenderFailureFailsTheRow() throws Exception {
            notifyTo("ops@example.com");
            var notifier = notifier(6);
            // Present when the row is written, gone when the mail is built: a runtime
            // failure no render path names in a catch clause.
            when(mailer.from()).thenReturn(Optional.of(FROM), Optional.empty());

            notifier.notifyPause(CONVERSATION, PAUSE, NOW);

            assertTrue(sent.isEmpty());
            LedgerEntry row = ledger.all().get(0);
            assertEquals(State.FAILED, row.state());
            assertTrue(row.lastError().startsWith("RENDER: NoSuchElementException"), row.lastError());
        }

        @Test
        @DisplayName("a TOOL_CALL pause lists tool names with redacted, truncated arguments only")
        void toolCallContent() throws Exception {
            notifyTo("ops@example.com");
            var call = new PendingToolCallBatch.PendingToolCall();
            call.setToolName("transfer_funds");
            call.setArgumentsRaw("{\"amount\":10000,\"apiKey\":\"sk-live-raw-should-never-appear\"}");
            call.setArgumentsRedacted("{\"amount\":10000,\"apiKey\":\"[REDACTED]\",\"memo\":\"" + "x".repeat(400) + "\"}");
            var batch = new PendingToolCallBatch();
            batch.setCalls(new ArrayList<>(List.of(call)));
            snapshot.setHitlPauseType("TOOL_CALL");
            snapshot.setHitlPendingToolCalls(batch);

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            String body = body(sent.get(0));
            assertTrue(body.contains("tool call approval"), body);
            assertTrue(body.contains("transfer_funds"), body);
            assertTrue(body.contains("\"amount\":10000"), body);
            assertFalse(body.contains("sk-live-raw-should-never-appear"), "the raw arguments must never be read");
            assertFalse(body.contains("x".repeat(301)), "arguments are truncated for display");
        }

        @Test
        @DisplayName("a designer agent name cannot add a header or a line to the subject")
        void agentNameCannotInject() throws Exception {
            notifyTo("ops@example.com");
            var descriptor = new DocumentDescriptor();
            descriptor.setName("Refund desk\r\nBcc: attacker@evil.test");
            when(descriptors.readDescriptor("agent-1", 3)).thenReturn(descriptor);

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            var message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(sent.get(0).mime()));
            assertNull(message.getHeader("Bcc"));
            assertEquals("[EDDI] Approval needed: Refund desk Bcc: attacker@evil.test", message.getSubject());
            assertEquals("auto-generated", message.getHeader("Auto-Submitted")[0]);
        }

        @Test
        @DisplayName("without a public base URL the mail names the page instead of linking a guessed host")
        void noBaseUrl() throws Exception {
            notifyTo("ops@example.com");
            when(connectionsConfig.getPublicBaseUrl()).thenReturn("");

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            String body = body(sent.get(0));
            assertFalse(body.contains("http"), body);
            assertTrue(body.contains("/manage/approvals"), body);
        }

        @Test
        @DisplayName("the audit entry names the recipient by keyed pseudonym and domain, never in plain text, and carries no body")
        void auditMinimised() throws Exception {
            notifyTo("ops@example.com");

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).submit(captor.capture());
            AuditEntry entry = captor.getValue();
            assertEquals(CONVERSATION, entry.conversationId());
            assertEquals("email.notify", entry.taskId());
            assertEquals(pseudonymizer.pseudonymFor("ops@example.com"), entry.output().get("recipientPseudonym"));
            assertFalse(entry.output().containsKey("recipientHash"), "no unkeyed hash beside the domain");
            assertEquals("example.com", entry.output().get("recipientDomain"));
            String flat = entry.toString() + entry.output();
            assertFalse(flat.contains("ops@example.com"), flat);
            assertFalse(flat.contains("Refunds over 500"), "no body in the audit: " + flat);
        }
    }

    @Test
    @DisplayName("the sweep removes expired rows and sets the pending gauge")
    void retentionAndGauge() throws Exception {
        notifyTo("ops@example.com");
        doThrow(new MailSendException(MailSendException.Category.CONNECT, false, "refused", null)).when(mailer).send(any(), anyList());
        var notifier = notifier(6);
        assertTrue(Double.isNaN(notifier.pendingGauge()), "unknown before the first sweep");
        notifier.notifyPause(CONVERSATION, PAUSE, NOW);

        notifier.sweep(NOW.plusSeconds(1));
        assertEquals(1.0, notifier.pendingGauge());
        notifier.sweep(NOW.plus(Duration.ofDays(31)));

        assertTrue(ledger.all().isEmpty());
        assertEquals(0.0, notifier.pendingGauge());
    }

    @Nested
    @DisplayName("who may be mailed")
    class RecipientGuards {

        @Test
        @DisplayName("by default only the sender's domain and its subdomains: an external recipient is SUPPRESSED, never mailed")
        void defaultDomainPolicy() throws Exception {
            notifyTo("ops@example.com", "lead@eu.example.com", "someone@gmail.com");

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            assertEquals(2, sent.size());
            LedgerEntry external = ledger.all().stream().filter(r -> r.recipient().equals("someone@gmail.com")).findFirst().orElseThrow();
            assertEquals(State.SUPPRESSED, external.state());
            assertEquals(HitlEmailNotifier.NOT_ALLOWED, external.lastError());
            assertEquals(0, external.attempts(), "refused before any attempt is made, not only by the send-time re-check");
            assertEquals(1.0, outbound("not_allowed"));
            verify(audit, times(2)).submit(any(AuditEntry.class));
        }

        @Test
        @DisplayName("an explicit allowlist replaces the default; * allows any domain")
        void explicitPolicy() throws Exception {
            notifyTo("ops@example.com", "someone@partner.test");
            allowedDomains = List.of("partner.test");

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);
            assertEquals(1, sent.size());
            assertEquals(1.0, outbound("not_allowed"));

            allowedDomains = List.of("*");
            Instant later = PAUSED_AT.plusSeconds(60);
            snapshot.setHitlPausedAt(later);
            notifier(6).notifyPause(CONVERSATION, HitlDecision.pauseIdOf(later), later.plusSeconds(1));
            assertEquals(3, sent.size());
        }

        @Test
        @DisplayName("a malformed allowlist fails closed: nobody is mailed")
        void malformedPolicyFailsClosed() throws Exception {
            notifyTo("ops@example.com");
            allowedDomains = List.of("not a domain");

            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);

            assertTrue(sent.isEmpty());
            assertEquals(State.SUPPRESSED, ledger.all().get(0).state());
        }

        @Test
        @DisplayName("a retry checks the policy again and suppresses a recipient it no longer allows")
        void policyRecheckedOnRetry() throws Exception {
            notifyTo("someone@partner.test");
            allowedDomains = List.of("*");
            doThrow(new MailSendException(MailSendException.Category.CONNECT, false, "refused", null)).when(mailer).send(any(), anyList());
            notifier(6).notifyPause(CONVERSATION, PAUSE, NOW);
            assertEquals(State.PENDING, ledger.all().get(0).state());

            allowedDomains = List.of();
            notifier(6).sweep(NOW.plus(Duration.ofMinutes(5)));

            assertEquals(State.SUPPRESSED, ledger.all().get(0).state());
            assertEquals(HitlEmailNotifier.NOT_ALLOWED, ledger.all().get(0).lastError());
            verify(mailer, times(1)).send(any(), anyList());
        }

        @Test
        @DisplayName("past the hourly maximum a recipient's notifications are SUPPRESSED, counted, warned once — and flow again an hour later")
        void rateLimited() throws Exception {
            notifyTo("ops@example.com");
            maxPerHour = 2;
            var notifier = notifier(6);
            for (int i = 0; i < 4; i++) {
                Instant pausedAt = PAUSED_AT.plusSeconds(60L * i);
                snapshot.setHitlPausedAt(pausedAt);
                notifier.notifyPause(CONVERSATION, HitlDecision.pauseIdOf(pausedAt), pausedAt.plusSeconds(1));
            }

            assertEquals(2, sent.size());
            assertEquals(2.0, outbound("rate_limited"));
            assertTrue(ledger.all().stream().filter(r -> r.state() == State.SUPPRESSED)
                    .allMatch(r -> HitlEmailNotifier.RATE_LIMITED.equals(r.lastError())));
            assertTrue(notifier.warnedRateLimited("ops@example.com"));

            Instant nextHour = PAUSED_AT.plus(Duration.ofMinutes(65));
            snapshot.setHitlPausedAt(nextHour);
            notifier.notifyPause(CONVERSATION, HitlDecision.pauseIdOf(nextHour), nextHour.plusSeconds(1));
            assertEquals(3, sent.size(), "suppressed rows do not keep the window full");
        }

        @Test
        @DisplayName("the limit is per recipient: one flooded address does not silence another")
        void perRecipient() throws Exception {
            notifyTo("ops@example.com");
            maxPerHour = 1;
            var notifier = notifier(6);
            notifier.notifyPause(CONVERSATION, PAUSE, NOW);
            notifyTo("ops@example.com", "lead@example.com");
            Instant second = PAUSED_AT.plusSeconds(60);
            snapshot.setHitlPausedAt(second);

            notifier.notifyPause(CONVERSATION, HitlDecision.pauseIdOf(second), second.plusSeconds(1));

            assertEquals(2, sent.size());
            assertEquals(1.0, outbound("rate_limited"));
        }
    }
}
