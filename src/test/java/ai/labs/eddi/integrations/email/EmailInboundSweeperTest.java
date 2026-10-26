/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.integrations.email.reply.EmailReplySender;
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

    private static SimpleConversationMemorySnapshot conversation(String... mailIds) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        var start = new ConversationOutput();
        start.put("output", List.of("Hello."));
        snapshot.getConversationOutputs().add(start);
        for (String mailId : mailIds) {
            var step = new ConversationOutput();
            step.put("output", List.of("An answer."));
            step.put("context", new HashMap<>(Map.of("mail", Map.of("messageId", mailId))));
            snapshot.getConversationOutputs().add(step);
        }
        return snapshot;
    }

    @Test
    @DisplayName("a conversation that was started but never ran the mail's turn: the mail is handed back once, keeping its conversation")
    void resubmitsMissingTurnOnce() throws Exception {
        submitted("lost-turn", "c-started", now.minusSeconds(1));
        submitted("ran", "c-ran", now.minusSeconds(1));
        submitted("ended", "c-ended", now.minusSeconds(1));
        when(conversations.getConversationState("c-started")).thenReturn(ConversationState.READY);
        when(conversations.getConversationState("c-ran")).thenReturn(ConversationState.READY);
        when(conversations.getConversationState("c-ended")).thenReturn(ConversationState.ENDED);
        when(conversations.readConversation("c-started", true, false, List.of())).thenReturn(conversation());
        when(conversations.readConversation("c-ran", true, false, List.of())).thenReturn(conversation("<ran@x.com>"));
        when(conversations.readConversation("c-ended", true, false, List.of())).thenReturn(conversation());

        assertEquals(3, sweeper.resolveStranded(now));

        var handedBack = ledger.findById("lost-turn").orElseThrow();
        assertEquals(State.RECEIVED, handedBack.state());
        assertEquals("c-started", handedBack.conversationId(), "it runs in the conversation that was started for it");
        assertEquals(EmailInboundSweeper.RESUBMIT, handedBack.lastError());
        assertEquals(State.DONE, ledger.findById("ran").orElseThrow().state(), "its step is there: it ran");
        assertEquals(State.DONE, ledger.findById("ended").orElseThrow().state(), "an ended conversation is not resubmitted into");
        assertEquals(0, sweeper.resolveStranded(now.plusSeconds(1)), "a second sweep finds nothing to do");
        verify(ownerNotifier, never()).notifyOnce(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("conversation mode: a stranded mail whose step is there gets its reply rebuilt before the row is closed")
    void rebuildsReply() throws Exception {
        submitted("answered", "c-answered", now.minusSeconds(1));
        when(conversations.getConversationState("c-answered")).thenReturn(ConversationState.READY);
        var snapshot = conversation("<answered@x.com>");
        when(conversations.readConversation("c-answered", true, false, List.of())).thenReturn(snapshot);
        var configuration = EmailInboundProcessorTest.integration(Map.of("mode", "conversation", "smtpHost", "smtp.contoso.com"));
        var settings = EmailChannelSettings.from(configuration.getPlatformConfig());
        when(directory.find("int-1")).thenReturn(Optional.of(new EmailIntegration("int-1", configuration, settings)));
        EmailReplySender replies = mock(EmailReplySender.class);
        when(replies.ensureFor(any(), any(), any(), any())).thenReturn(true);
        sweeper.replies = replies;

        sweeper.resolveStranded(now);

        verify(replies).ensureFor(eq(settings), any(), eq(snapshot), eq(now));
        assertEquals(State.DONE, ledger.findById("answered").orElseThrow().state());
    }

    @Test
    @DisplayName("conversation mode: a reply that cannot be rebuilt keeps the row submitted for a later sweep, a bounded number of times")
    void rebuildFailureKeepsRow() throws Exception {
        submitted("answered", "c-answered", now.minusSeconds(1));
        when(conversations.getConversationState("c-answered")).thenReturn(ConversationState.READY);
        var snapshot = conversation("<answered@x.com>");
        when(conversations.readConversation("c-answered", true, false, List.of())).thenReturn(snapshot);
        var configuration = EmailInboundProcessorTest.integration(Map.of("mode", "conversation", "smtpHost", "smtp.contoso.com"));
        var settings = EmailChannelSettings.from(configuration.getPlatformConfig());
        when(directory.find("int-1")).thenReturn(Optional.of(new EmailIntegration("int-1", configuration, settings)));
        EmailReplySender replies = mock(EmailReplySender.class);
        when(replies.ensureFor(any(), any(), any(), any())).thenThrow(new IllegalStateException("ledger unavailable"))
                .thenReturn(true);
        sweeper.replies = replies;

        sweeper.resolveStranded(now);

        var kept = ledger.findById("answered").orElseThrow();
        assertEquals(State.TURN_SUBMITTED, kept.state(), "not closed without its reply");
        assertEquals(EmailInboundSweeper.REBUILD_FAILED + 1, kept.lastError());
        assertTrue(kept.nextAttemptAt().isAfter(now), "tried again after stranded-after, not on every sweep");

        sweeper.resolveStranded(now.plus(Duration.ofMinutes(31)));
        assertEquals(State.DONE, ledger.findById("answered").orElseThrow().state(), "rebuilt on the next sweep, then closed");

        submitted("hopeless", "c-hopeless", now.minusSeconds(1));
        when(conversations.getConversationState("c-hopeless")).thenReturn(ConversationState.READY);
        when(conversations.readConversation("c-hopeless", true, false, List.of())).thenReturn(conversation("<hopeless@x.com>"));
        when(replies.ensureFor(any(), any(), any(), any())).thenThrow(new IllegalStateException("never"));
        Instant at = now;
        for (int i = 1; i < EmailInboundSweeper.MAX_REBUILD_ATTEMPTS; i++) {
            sweeper.resolveStranded(at);
            assertEquals(State.TURN_SUBMITTED, ledger.findById("hopeless").orElseThrow().state());
            at = at.plus(Duration.ofMinutes(31));
        }
        sweeper.resolveStranded(at);
        assertEquals(State.DONE, ledger.findById("hopeless").orElseThrow().state(), "given up after the last attempt");
    }

    @Test
    @DisplayName("trigger mode: no reply is ever rebuilt")
    void triggerModeRebuildsNothing() throws Exception {
        submitted("answered", "c-answered", now.minusSeconds(1));
        when(conversations.getConversationState("c-answered")).thenReturn(ConversationState.READY);
        when(conversations.readConversation("c-answered", true, false, List.of())).thenReturn(conversation("<answered@x.com>"));
        var configuration = EmailInboundProcessorTest.integration(Map.of());
        when(directory.find("int-1"))
                .thenReturn(Optional.of(new EmailIntegration("int-1", configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
        EmailReplySender replies = mock(EmailReplySender.class);
        sweeper.replies = replies;

        sweeper.resolveStranded(now);

        verify(replies, never()).ensureFor(any(), any(), any(), any());
        assertEquals(State.DONE, ledger.findById("answered").orElseThrow().state());
    }
}
