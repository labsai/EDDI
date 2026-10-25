/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.GdprDeletionResult;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.SystemMailer;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.ReservedPrincipals;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
    }
}
