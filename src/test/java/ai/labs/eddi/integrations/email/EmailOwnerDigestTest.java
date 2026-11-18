/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.SystemMailer;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The daily owner digest ({@code ownerDigest: daily}, email v2 plan §6.3): once
 * per UTC day, counts of the previous day only, inside the notification domain
 * policy, nothing for a quiet day.
 */
class EmailOwnerDigestTest {

    private static final Instant TODAY_0010 = Instant.parse("2026-11-18T00:10:00Z");
    private static final Instant YESTERDAY_NOON = Instant.parse("2026-11-17T12:00:00Z");

    private SystemMailer mailer;
    private InMemoryEmailMessageLedger ledger;
    private EmailOwnerNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        mailer = mock(SystemMailer.class);
        when(mailer.isEnabled()).thenReturn(true);
        when(mailer.from()).thenReturn(Optional.of(new MailAddress("eddi@contoso.com", "EDDI")));
        when(mailer.send(any(), anyList())).thenReturn(new SystemMailer.SendResult("250 OK"));
        ledger = new InMemoryEmailMessageLedger();
        notifier = new EmailOwnerNotifier(mailer, new InMemoryEmailMailboxStateStore(), ledger, new SimpleMeterRegistry(), Optional.empty(),
                Duration.ofDays(30));
    }

    private static EmailChannelSettings settings(String digest) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "c", "host", "imap.x.com", "address",
                "invoices@contoso.com", "ownerNotifyAddresses", "ops@contoso.com,outsider@elsewhere.com"));
        if (digest != null) {
            pc.put("ownerDigest", digest);
        }
        return EmailChannelSettings.from(pc);
    }

    private void inbound(String integrationId, State state, String reason, Instant at) {
        String id = UUID.randomUUID().toString();
        ledger.insertIfAbsent(new LedgerEntry(id, integrationId, Kind.TRIGGER, Direction.IN, "<" + id + "@x.example>", null, "ref", "in:" + id,
                "alice@example.com", state, 0, null, null, null, reason, at, at, at.plus(Duration.ofDays(30))));
    }

    private long digestRows() {
        return ledger.all().stream().filter(r -> r.kind() == Kind.NOTICE && "digest".equals(r.sourceRef())).count();
    }

    @Test
    @DisplayName("sent once per day to the owners inside the domain policy; a second sweep the same day sends nothing")
    void oncePerDay() throws Exception {
        inbound("int1", State.DONE, null, YESTERDAY_NOON);
        inbound("int1", State.REFUSED, "blocked:not_allowlisted", YESTERDAY_NOON);

        assertTrue(notifier.sendDigestIfDue("int1", "Invoices", settings("daily"), TODAY_0010));
        assertFalse(notifier.sendDigestIfDue("int1", "Invoices", settings("daily"), TODAY_0010.plus(Duration.ofHours(5))));

        verify(mailer, times(1)).send(any(), anyList());
        assertEquals(1, digestRows());
        assertTrue(ledger.all().stream().filter(r -> r.kind() == Kind.NOTICE).allMatch(r -> "ops@contoso.com".equals(r.recipient())),
                "outside the notification domain policy: not mailed");
    }

    @Test
    @DisplayName("off by default, off when set off; not before 00:05 UTC; nothing for a day without mail")
    void notDue() throws Exception {
        inbound("int1", State.DONE, null, YESTERDAY_NOON);

        assertFalse(notifier.sendDigestIfDue("int1", "Invoices", settings(null), TODAY_0010));
        assertFalse(notifier.sendDigestIfDue("int1", "Invoices", settings("off"), TODAY_0010));
        assertFalse(notifier.sendDigestIfDue("int1", "Invoices", settings("daily"), Instant.parse("2026-11-18T00:04:59Z")));
        assertFalse(notifier.sendDigestIfDue("quiet", "Quiet", settings("daily"), TODAY_0010), "no mail yesterday");
        verify(mailer, never()).send(any(), anyList());
    }

    @Test
    @DisplayName("without a configured system mailer nothing is claimed or sent")
    void noMailer() throws Exception {
        when(mailer.isEnabled()).thenReturn(false);
        inbound("int1", State.DONE, null, YESTERDAY_NOON);

        assertFalse(notifier.sendDigestIfDue("int1", "Invoices", settings("daily"), TODAY_0010));
        verify(mailer, never()).send(any(), anyList());

        // The day was not used up: once the mailer is configured, the digest goes out.
        when(mailer.isEnabled()).thenReturn(true);
        assertTrue(notifier.sendDigestIfDue("int1", "Invoices", settings("daily"), TODAY_0010.plus(Duration.ofHours(1))));
        verify(mailer, times(1)).send(any(), anyList());
    }

    @Test
    @DisplayName("owners all outside the domain policy: nothing is claimed, sent or reported as sent")
    void noAllowedOwner() throws Exception {
        inbound("int1", State.DONE, null, YESTERDAY_NOON);
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "c", "host", "imap.x.com", "address",
                "invoices@contoso.com", "ownerNotifyAddresses", "outsider@elsewhere.com", "ownerDigest", "daily"));

        assertFalse(notifier.sendDigestIfDue("int1", "Invoices", EmailChannelSettings.from(pc), TODAY_0010));
        verify(mailer, never()).send(any(), anyList());
        assertTrue(notifier.sendDigestIfDue("int1", "Invoices", settings("daily"), TODAY_0010.plus(Duration.ofHours(1))),
                "the day was not used up");
    }

    @Test
    @DisplayName("the text counts the previous UTC day by outcome and links the mail log — no address, subject or body")
    void content() {
        String text = EmailOwnerNotifier.digestText("int1", "Invoices", Instant.parse("2026-11-17T00:00:00Z"),
                Map.of("done", 3L, "refused:blocked", 2L), 5);

        assertTrue(text.contains("on 2026-11-17 (UTC)"), text);
        assertTrue(text.contains("  done: 3\n"), text);
        assertTrue(text.contains("  refused:blocked: 2\n"), text);
        assertTrue(text.contains("total: 5"), text);
        assertTrue(text.contains("/manage/channels/int1#mail-log"), text);
        assertFalse(text.contains("@"), "no address in the digest: " + text);
    }

    @Test
    @DisplayName("the inbound sweep asks the notifier for each integration that set ownerDigest: daily, and only those")
    void sweepSendsDigests() {
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        EmailOwnerNotifier owner = mock(EmailOwnerNotifier.class);
        var daily = new ChannelIntegrationConfiguration();
        daily.setName("Daily");
        var none = new ChannelIntegrationConfiguration();
        none.setName("None");
        when(directory.all()).thenReturn(List.of(new EmailIntegration("int-daily", daily, settings("daily")),
                new EmailIntegration("int-none", none, settings(null))));
        when(owner.sendDigestIfDue(eq("int-daily"), eq("Daily"), any(), eq(TODAY_0010))).thenReturn(true);
        var sweeper = new EmailInboundSweeper(ledger, mock(IConversationService.class), directory, mock(EmailPollScheduler.class), owner,
                new SimpleMeterRegistry(), Duration.ofMinutes(30));

        assertEquals(1, sweeper.sendDigests(TODAY_0010));
        verify(owner, never()).sendDigestIfDue(eq("int-none"), any(), any(), any());
    }

    @Test
    @DisplayName("ownerDigest accepts off and daily only")
    void settingValidation() {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "c", "host", "imap.x.com", "address",
                "invoices@contoso.com"));
        pc.put("ownerDigest", "weekly");
        assertTrue(EmailChannelSettings.validate(pc).stream().anyMatch(e -> e.contains("ownerDigest")));
        pc.put("ownerDigest", "Daily");
        assertTrue(EmailChannelSettings.validate(pc).isEmpty(), EmailChannelSettings.validate(pc).toString());
        assertTrue(EmailChannelSettings.from(pc).dailyOwnerDigest());
        pc.put("ownerDigest", "");
        assertTrue(EmailChannelSettings.validate(pc).isEmpty());
        assertFalse(EmailChannelSettings.from(pc).dailyOwnerDigest());
    }
}
