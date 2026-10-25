/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailInboundSweeperTest {

    InMemoryEmailMessageLedger ledger;
    IConversationService conversations;
    EmailIntegrationDirectory directory;
    EmailPollScheduler scheduler;
    EmailOwnerNotifier ownerNotifier;
    EmailInboundSweeper sweeper;
    Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        ledger = new InMemoryEmailMessageLedger();
        conversations = mock(IConversationService.class);
        directory = mock(EmailIntegrationDirectory.class);
        scheduler = mock(EmailPollScheduler.class);
        ownerNotifier = mock(EmailOwnerNotifier.class);
        sweeper = new EmailInboundSweeper(ledger, conversations, directory, scheduler, ownerNotifier, new SimpleMeterRegistry(),
                Duration.ofMinutes(30));
    }

    private LedgerEntry submitted(String id, String conversationId, Instant deadline) {
        return row(id, State.TURN_SUBMITTED, conversationId, deadline);
    }

    private LedgerEntry row(String id, State state, String conversationId, Instant deadline) {
        var row = new LedgerEntry(id, "int-1", Kind.TRIGGER, Direction.IN, "<" + id + "@x.com>", conversationId, "imap:INBOX:1:1", "in:" + id,
                "a@x.com", state, 1, deadline, null, null, null, now, now, now.plus(Duration.ofDays(30)));
        ledger.put(row);
        return row;
    }

    @Test
    @DisplayName("stranded turns are resolved from the conversation; nothing is run again")
    void resolvesStranded() {
        submitted("done", "c-ready", now.minusSeconds(1));
        submitted("failed", "c-error", now.minusSeconds(1));
        submitted("paused", "c-paused", now.minusSeconds(1));
        submitted("running", "c-running", now.minusSeconds(1));
        submitted("lost", null, now.minusSeconds(1));
        submitted("gone", "c-gone", now.minusSeconds(1));
        submitted("fresh", "c-ready", now.plusSeconds(600));
        when(conversations.getConversationState("c-ready")).thenReturn(ConversationState.READY);
        when(conversations.getConversationState("c-error")).thenReturn(ConversationState.ERROR);
        when(conversations.getConversationState("c-paused")).thenReturn(ConversationState.AWAITING_HUMAN);
        when(conversations.getConversationState("c-running")).thenReturn(ConversationState.IN_PROGRESS);
        when(conversations.getConversationState("c-gone")).thenThrow(new IConversationService.ConversationNotFoundException("gone"));

        assertEquals(5, sweeper.resolveStranded(now));

        assertEquals(State.DONE, ledger.findById("done").orElseThrow().state());
        assertEquals("recovered:failed", ledger.findById("failed").orElseThrow().lastError());
        assertEquals("recovered:awaiting_human", ledger.findById("paused").orElseThrow().lastError());
        assertEquals(State.TURN_SUBMITTED, ledger.findById("running").orElseThrow().state());
        assertTrue(ledger.findById("running").orElseThrow().nextAttemptAt().isAfter(now), "the deadline moved on");
        assertEquals(State.REFUSED, ledger.findById("lost").orElseThrow().state());
        assertEquals("stranded", ledger.findById("gone").orElseThrow().lastError());
        assertEquals(State.TURN_SUBMITTED, ledger.findById("fresh").orElseThrow().state(), "not past its deadline");
        verify(ownerNotifier, times(2)).notifyOnce(eq("int-1"), any(), any(), eq(EmailOwnerNotifier.Reason.LOST), any());
    }

    @Test
    @DisplayName("a stale claim (nothing done outside EDDI) is given back to RECEIVED for the next poll; a live one is left alone")
    void requeuesStaleClaims() {
        row("stale", State.CLAIMED, null, now.minusSeconds(1));
        row("live", State.CLAIMED, null, now.plusSeconds(600));

        assertEquals(1, sweeper.resolveStranded(now));

        var requeued = ledger.findById("stale").orElseThrow();
        assertEquals(State.RECEIVED, requeued.state());
        assertEquals(null, requeued.nextAttemptAt(), "due at once");
        assertEquals(State.CLAIMED, ledger.findById("live").orElseThrow().state());
        verify(ownerNotifier, never()).notifyOnce(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a missing poll schedule is re-created for a valid trigger integration, not for an invalid one")
    void ensuresSchedules() throws Exception {
        var good = EmailInboundProcessorTest.integration(Map.of());
        var bad = EmailInboundProcessorTest.integration(Map.of("mode", "conversation"));
        when(directory.scan()).thenReturn(List.of(new EmailIntegration("good", good, EmailChannelSettings.from(good.getPlatformConfig())),
                new EmailIntegration("bad", bad, EmailChannelSettings.from(bad.getPlatformConfig()))));
        when(scheduler.ensure(eq("good"), any())).thenReturn(true);

        assertEquals(1, sweeper.ensureSchedules());
        verify(scheduler, never()).ensure(eq("bad"), any());
    }
}
