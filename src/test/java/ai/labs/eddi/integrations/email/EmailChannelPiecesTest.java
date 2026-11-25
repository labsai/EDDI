/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.GdprDeletionResult;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.memory.ConversationPurgeService;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.integrations.email.ledger.EmailLedgerGdprParticipant;
import ai.labs.eddi.engine.mail.SystemMailer;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.ReservedPrincipals;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.RecipientRole;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.ReplyRecipient;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailChannelPiecesTest {

    @Nested
    @DisplayName("sender patterns")
    class Patterns {
        @Test
        @DisplayName("exact address, a domain, its subdomains — and nothing more")
        void matching() {
            assertTrue(SenderPatterns.matchesAny("bob@supplier.com", List.of("Bob@Supplier.com")));
            assertTrue(SenderPatterns.matchesAny("bob@supplier.com", List.of("*@supplier.com")));
            assertFalse(SenderPatterns.matchesAny("bob@evil-supplier.com", List.of("*@supplier.com")));
            assertFalse(SenderPatterns.matchesAny("bob@sub.supplier.com", List.of("*@supplier.com")), "*@domain is that domain only");
            assertTrue(SenderPatterns.matchesAny("bob@sub.supplier.com", List.of("*@*.supplier.com")));
            assertFalse(SenderPatterns.matchesAny("bob@supplier.com", List.of("*@*.supplier.com")), "*@*.domain is subdomains only");
            assertFalse(SenderPatterns.matchesAny("bob@supplier.com", List.of("garbage")));
            assertFalse(SenderPatterns.matchesAny(null, List.of("*@supplier.com")));
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {
        @Test
        @DisplayName("namespaced, reserved, and only anonymous ids are memory-less")
        void identities() {
            String perSender = EmailUserIdentity.perSender("int1", "alice@x.com");
            assertEquals("email:int1:alice@x.com", perSender);
            assertEquals(Optional.of("alice@x.com"), EmailUserIdentity.addressOf(perSender));
            assertEquals(Optional.empty(), EmailUserIdentity.addressOf(EmailUserIdentity.integration("int1")));
            String anonymous = EmailUserIdentity.anonymous();
            assertTrue(anonymous.startsWith("email-anon:"));
            assertTrue(ReservedPrincipals.isReserved(perSender), "no signed-in user may carry it");
            assertTrue(ReservedPrincipals.isReserved(anonymous));
            assertTrue(ReservedPrincipals.hasNoPersistentMemory(anonymous));
            assertFalse(ReservedPrincipals.hasNoPersistentMemory(perSender));
            assertFalse(ReservedPrincipals.hasNoPersistentMemory("alice"));
        }

        @Test
        @DisplayName("thread identities (email v2 plan §9.2): reserved, memory-less, bound to one integration and one thread")
        void threadIdentities() {
            String thread = EmailUserIdentity.thread("int1", "<root@x.com>");
            assertTrue(thread.startsWith("email-thread:int1:"));
            assertEquals(thread, EmailUserIdentity.thread("int1", "<root@x.com>"), "one per thread");
            assertNotEquals(thread, EmailUserIdentity.thread("int1", "<other@x.com>"));
            String own = EmailUserIdentity.threadForSender("int1", "<root@x.com>", "bob@x.com");
            assertNotEquals(thread, own, "a non-participant gets a conversation of their own");
            assertNotEquals(own, EmailUserIdentity.threadForSender("int1", "<root@x.com>", "carol@x.com"));
            assertTrue(ReservedPrincipals.isReserved(thread), "no signed-in user may carry it");
            assertTrue(ReservedPrincipals.hasNoPersistentMemory(thread), "colleagues read the answer: no long-term memory");
            assertTrue(ReservedPrincipals.hasNoPersistentMemory(own));
            assertTrue(EmailUserIdentity.isEmailIdentity(thread), "the GDPR cascade must not expand it again");
            assertTrue(EmailUserIdentity.isThreadIdentityOf(thread, "int1"));
            assertTrue(EmailUserIdentity.isThreadIdentityOf(own, "int1"));
            assertFalse(EmailUserIdentity.isThreadIdentityOf(thread, "int"), "another integration's id that starts the same way");
            assertFalse(EmailUserIdentity.isThreadIdentityOf(EmailUserIdentity.thread("int12", "<root@x.com>"), "int1"));
            assertFalse(EmailUserIdentity.isThreadIdentityOf("email-thread:int1:not-a-digest", "int1"));
            assertFalse(EmailUserIdentity.isThreadIdentityOf(EmailUserIdentity.perSender("int1", "alice@x.com"), "int1"));
            assertEquals(Optional.empty(), EmailUserIdentity.addressOf(thread), "names no address");
        }
    }

    @Nested
    @DisplayName("routing helpers")
    class Routing {
        @Test
        @DisplayName("subject words are letters and digits, lower-cased")
        void words() {
            assertEquals(List.of("re", "urgent", "invoice", "4711"), EmailTargetRouting.subjectWords("Re: URGENT – invoice #4711!"));
        }
    }

    @Nested
    @DisplayName("poll schedule")
    class Schedule {
        @Test
        @DisplayName("a heartbeat every pollSeconds, armed, named and tagged for the fire fast-path")
        void build() {
            var settings = EmailChannelSettings.from(Map.of("mode", "trigger", "transport", "imap", "connection", "c", "host", "imap.x.com",
                    "address", "a@x.com", "pollSeconds", "120"));
            ScheduleConfiguration schedule = EmailPollScheduler.build("int1", settings);
            assertEquals("email-poll:int1", schedule.getName());
            assertEquals(ScheduleConfiguration.TriggerType.HEARTBEAT, schedule.getTriggerType());
            assertEquals(120L, schedule.getHeartbeatIntervalSeconds());
            assertTrue(schedule.getNextFire() != null && schedule.isEnabled());
            assertTrue(EmailPollSchedules.isEmailPollSchedule(schedule.getMetadata()));
            assertEquals("int1", EmailPollSchedules.integrationId(schedule.getMetadata()));
        }
    }

    @Nested
    @DisplayName("owner notices")
    class Notices {
        @Test
        @DisplayName("once per day and reason, cluster-wide; mailed only to the configured owners inside the domain policy")
        void notifyOnce() throws Exception {
            SystemMailer mailer = mock(SystemMailer.class);
            when(mailer.isEnabled()).thenReturn(true);
            when(mailer.from()).thenReturn(Optional.of(new MailAddress("eddi@contoso.com", "EDDI")));
            when(mailer.send(any(), anyList())).thenReturn(new SystemMailer.SendResult("250 OK"));
            var ledger = new InMemoryEmailMessageLedger();
            var notifier = new EmailOwnerNotifier(mailer, new InMemoryEmailMailboxStateStore(), ledger, new SimpleMeterRegistry(), Optional.empty(),
                    Duration.ofDays(30));
            var settings = EmailChannelSettings.from(Map.of("mode", "trigger", "transport", "imap", "connection", "c", "host", "imap.x.com",
                    "address", "invoices@contoso.com", "ownerNotifyAddresses", "ops@contoso.com,outsider@elsewhere.com"));
            Instant now = Instant.now();

            assertTrue(notifier.notifyOnce("int1", "Invoices", settings, EmailOwnerNotifier.Reason.BUDGET, now));
            assertFalse(notifier.notifyOnce("int1", "Invoices", settings, EmailOwnerNotifier.Reason.BUDGET, now.plusSeconds(60)));
            assertTrue(notifier.notifyOnce("int1", "Invoices", settings, EmailOwnerNotifier.Reason.THROTTLED, now));

            verify(mailer, times(2)).send(any(), anyList());
            assertEquals(2, ledger.all().stream().filter(r -> r.kind() == Kind.NOTICE && r.state() == State.SENT).count());
            assertTrue(ledger.all().stream().allMatch(r -> "ops@contoso.com".equals(r.recipient())), "outside the sender's domain: not mailed");
        }

        @Test
        @DisplayName("without a system mailer the notice is a log line only")
        void noMailer() throws Exception {
            SystemMailer mailer = mock(SystemMailer.class);
            when(mailer.from()).thenReturn(Optional.empty());
            var notifier = new EmailOwnerNotifier(mailer, new InMemoryEmailMailboxStateStore(), new InMemoryEmailMessageLedger(),
                    new SimpleMeterRegistry(), Optional.empty(), Duration.ofDays(30));
            var settings = EmailChannelSettings.from(Map.of("ownerNotifyAddresses", "ops@contoso.com"));

            assertTrue(notifier.notifyOnce("int1", "Invoices", settings, EmailOwnerNotifier.Reason.QUOTA, Instant.now()));
            verify(mailer, never()).send(any(), anyList());
        }
    }

    @Nested
    @DisplayName("GDPR")
    class Gdpr {
        @Test
        @DisplayName("an address is erased under every email identity it has — current integrations and those its inbound rows name")
        @SuppressWarnings("unchecked")
        void eraseByAddress() throws Exception {
            GdprComplianceService service = mock(GdprComplianceService.class);
            GdprDeletionResult result = mock(GdprDeletionResult.class);
            when(result.failedSteps()).thenReturn(List.of());
            when(result.conversationsDeleted()).thenReturn(2L);
            when(service.deleteUserData(anyString())).thenReturn(result);
            Instance<GdprComplianceService> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(true);
            when(instance.get()).thenReturn(service);
            EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
            var configuration = EmailInboundProcessorTest.integration(Map.of());
            when(directory.scan()).thenReturn(
                    List.of(new EmailIntegration("current", configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
            var ledger = new InMemoryEmailMessageLedger();
            Instant now = Instant.now();
            ledger.insertIfAbsent(new LedgerEntry("r1", "deleted-since", Kind.TRIGGER, Direction.IN, "<m@x.com>", null, null, "in:1", "alice@x.com",
                    State.DONE, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
            var participant = new EmailIdentityGdprParticipant(instance, directory, ledger);

            assertEquals(Set.of("email:current:alice@x.com", "email:deleted-since:alice@x.com"), participant.emailUserIdsOf("Alice@X.com"));
            assertEquals(4, participant.erase("alice@x.com"));
            verify(service).deleteUserData("email:current:alice@x.com");
            verify(service).deleteUserData("email:deleted-since:alice@x.com");
            assertTrue(participant.emailUserIdsOf("email:current:alice@x.com").isEmpty(), "an email identity is not expanded again");
            assertTrue(participant.emailUserIdsOf("alice").isEmpty());

            when(result.failedSteps()).thenReturn(List.of("conversations"));
            assertThrows(IllegalStateException.class, () -> participant.erase("alice@x.com"), "an incomplete nested erasure is reported");
        }

        @Test
        @DisplayName("thread conversations (email v2 plan §9.5) are reached through the ledger, only when they run as that integration's thread")
        @SuppressWarnings("unchecked")
        void threadConversations() throws Exception {
            GdprComplianceService service = mock(GdprComplianceService.class);
            Instance<GdprComplianceService> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(true);
            when(instance.get()).thenReturn(service);
            EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
            when(directory.scan()).thenReturn(List.of());
            var ledger = new InMemoryEmailMessageLedger();
            Instant now = Instant.now();
            String started = EmailUserIdentity.thread("int1", "<root@x.com>");
            String copied = EmailUserIdentity.thread("int1", "<other@x.com>");
            // Alice started one thread conversation...
            ledger.insertIfAbsent(new LedgerEntry("in-1", "int1", Kind.TRIGGER, Direction.IN, "<m1@x.com>", "conv-started", null, "in:1",
                    "alice@x.com", State.DONE, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
            // ...was copied on a reply in another...
            LedgerEntry reply = new LedgerEntry("out-1", "int1", Kind.REPLY, Direction.OUT, "<eddi.1@x.com>", "conv-copied", "in-9|reply|1",
                    "reply:1",
                    "bob@x.com", State.SENT, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30)));
            ledger.insertIfAbsent(reply);
            ledger.addReplyRecipients(List.of(
                    new ReplyRecipient("out-1", "int1", "conv-copied", "bob@x.com", RecipientRole.TO, now, now.plus(Duration.ofDays(30))),
                    new ReplyRecipient("out-1", "int1", "conv-copied", "alice@x.com", RecipientRole.CC, now, now.plus(Duration.ofDays(30)))));
            // ...and her rows also point at a per-sender conversation and at another
            // integration's thread, which must not be reached this way.
            ledger.insertIfAbsent(new LedgerEntry("in-2", "int1", Kind.TRIGGER, Direction.IN, "<m2@x.com>", "conv-personal", null, "in:2",
                    "alice@x.com", State.DONE, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
            ledger.insertIfAbsent(new LedgerEntry("in-3", "int1", Kind.TRIGGER, Direction.IN, "<m3@x.com>", "conv-foreign", null, "in:3",
                    "alice@x.com", State.DONE, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
            IConversationMemoryStore store = mock(IConversationMemoryStore.class);
            Map<String, String> owners = Map.of("conv-started", started, "conv-copied", copied, "conv-personal",
                    EmailUserIdentity.perSender("int1", "carol@x.com"), "conv-foreign", EmailUserIdentity.thread("int2", "<root@x.com>"));
            when(store.loadConversationMemorySnapshot(anyString())).thenAnswer(invocation -> {
                var snapshot = new ConversationMemorySnapshot();
                snapshot.setUserId(owners.get((String) invocation.getArgument(0)));
                return snapshot;
            });
            Instance<IConversationMemoryStore> stores = instanceOf(store);
            ConversationPurgeService purge = mock(ConversationPurgeService.class);
            IConversationService conversationService = mock(IConversationService.class);
            // The shared conversation Alice started: her mail, EDDI's reply, then Bob's
            // mail and its reply, which she was not sent.
            var shared = new SimpleConversationMemorySnapshot();
            shared.getConversationOutputs().add(step("Alice asks about 4711", "<m1@x.com>", "Ships Monday."));
            shared.getConversationOutputs().add(step("Bob asks about his salary", "<bob@x.com>", "Bob, your raise is approved."));
            when(conversationService.readConversation(eq("conv-started"), any(), any(), anyList())).thenReturn(shared);
            when(conversationService.readConversation(eq("conv-copied"), any(), any(), anyList())).thenReturn(new SimpleConversationMemorySnapshot());
            ledger.insertIfAbsent(new LedgerEntry("out-a", "int1", Kind.REPLY, Direction.OUT, "<eddi.a@x.com>", "conv-started", "in-1|reply|0",
                    "reply:a", "alice@x.com", State.SENT, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
            ledger.insertIfAbsent(new LedgerEntry("out-b", "int1", Kind.REPLY, Direction.OUT, "<eddi.b@x.com>", "conv-started", "in-b|reply|1",
                    "reply:b", "bob@x.com", State.SENT, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
            AuditLedgerService audit = mock(AuditLedgerService.class);
            when(audit.isEnabled()).thenReturn(true);
            var participant = new EmailIdentityGdprParticipant(instance, directory, ledger, stores, instanceOf(purge),
                    instanceOf(conversationService),
                    instanceOf(audit));

            assertEquals(Set.of("email:int1:alice@x.com"), participant.emailUserIdsOf("alice@x.com"), "never the shared thread identity");
            assertEquals(Map.of("conv-started", "int1", "conv-copied", "int1"), participant.threadConversationsOf("alice@x.com"));

            @SuppressWarnings("unchecked")
            var export = (Map<String, Object>) participant.export("alice@x.com");
            String exported = export.get("threadConversations").toString();
            assertTrue(exported.contains("Alice asks about 4711") && exported.contains("Ships Monday."), exported);
            assertFalse(exported.contains("salary") || exported.contains("raise"), "nothing anyone else wrote or was told (Art. 15(4))");
            verify(service, never()).exportUserData(started);

            participant.erase("alice@x.com");
            verify(purge).purge("conv-started");
            verify(purge).purge("conv-copied");
            verify(purge, never()).purge("conv-personal");
            verify(purge, never()).purge("conv-foreign");
            verify(service, never()).deleteUserData(started);
            verify(service, never()).deleteUserData(copied);
            verify(audit, times(2)).submit(any());
        }

        @Test
        @DisplayName("a failed thread-conversation erasure keeps the ledger rows, so the retry still reaches the conversation")
        @SuppressWarnings("unchecked")
        void failedErasureKeepsItsPointers() throws Exception {
            GdprComplianceService service = mock(GdprComplianceService.class);
            Instance<GdprComplianceService> instance = instanceOf(service);
            EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
            when(directory.scan()).thenReturn(List.of());
            var ledger = new InMemoryEmailMessageLedger();
            Instant now = Instant.now();
            ledger.insertIfAbsent(new LedgerEntry("in-1", "int1", Kind.TRIGGER, Direction.IN, "<m1@x.com>", "conv-t", null, "in:1", "alice@x.com",
                    State.DONE, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30))));
            IConversationMemoryStore store = mock(IConversationMemoryStore.class);
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setUserId(EmailUserIdentity.thread("int1", "<m1@x.com>"));
            when(store.loadConversationMemorySnapshot("conv-t")).thenReturn(snapshot);
            ConversationPurgeService purge = mock(ConversationPurgeService.class);
            doThrow(new IResourceStore.ResourceStoreException("db down")).when(purge).purge("conv-t");
            var identities = new EmailIdentityGdprParticipant(instance, directory, ledger, instanceOf(store), instanceOf(purge), null, null);
            var rows = new EmailLedgerGdprParticipant(ledger, null, instanceOf(store));

            assertThrows(IllegalStateException.class, () -> identities.erase("alice@x.com"));
            assertThrows(IllegalStateException.class, () -> rows.erase("alice@x.com"), "the ledger step holds while the conversation is there");
            assertEquals(1, ledger.findByRecipient("alice@x.com").size());

            // The retry: the conversation store is back.
            doNothing().when(purge).purge("conv-t");
            identities.erase("alice@x.com");
            verify(purge, times(2)).purge("conv-t");
            doThrow(new IResourceStore.ResourceNotFoundException("gone")).when(store).loadConversationMemorySnapshot("conv-t");
            assertEquals(1, rows.erase("alice@x.com"), "once it is gone the rows go too");
        }

        @SuppressWarnings("unchecked")
        private <T> Instance<T> instanceOf(T value) {
            Instance<T> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(true);
            when(instance.get()).thenReturn(value);
            return instance;
        }

        private ConversationOutput step(String input, String messageId, String answer) {
            ConversationOutput output = new ConversationOutput();
            output.put("input", input);
            output.put("output", List.of(answer));
            output.put("context", new HashMap<>(Map.of("mail", Map.of("messageId", messageId))));
            return output;
        }
    }
}
