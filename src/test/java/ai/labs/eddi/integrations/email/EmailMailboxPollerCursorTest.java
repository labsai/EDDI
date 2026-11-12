/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.Disposition;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.integrations.email.transport.MailboxCursor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The poller against a transport whose cursor is not IMAP-shaped (a history id,
 * as the Gmail API has): it stores what the transport hands it and never looks
 * inside.
 */
class EmailMailboxPollerCursorTest {

    static final String ID = "b1b2c3d4e5f60718293a4b5c";

    InMemoryEmailMailboxStateStore state;
    HistoryTransport transport;
    EmailMailboxPoller poller;
    SimpleMeterRegistry registry;
    List<String> events;
    Map<Long, Disposition> dispositions;

    @BeforeEach
    void setUp() {
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        var configuration = EmailInboundProcessorTest.integration(Map.of());
        when(directory.find(ID))
                .thenReturn(Optional.of(new EmailIntegration(ID, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
        when(directory.otherAddresses(ID)).thenReturn(Set.of());
        events = new ArrayList<>();
        dispositions = new HashMap<>();
        EmailInboundProcessor processor = mock(EmailInboundProcessor.class);
        when(processor.process(any(), any(), any())).thenAnswer(invocation -> {
            MailTransport.MailboxMessage message = invocation.getArgument(1);
            events.add("ledger:" + message.uid());
            return dispositions.getOrDefault(message.uid(), Disposition.SETTLED);
        });
        state = new InMemoryEmailMailboxStateStore();
        transport = new HistoryTransport();
        registry = new SimpleMeterRegistry();
        poller = new EmailMailboxPoller(directory, processor, state, transport, mock(EmailPollScheduler.class), mock(EmailOwnerNotifier.class),
                registry, 50, Duration.ofMinutes(2));
    }

    private MailboxCursor stored() {
        return state.read(ID).orElseThrow().cursor();
    }

    private static MailboxCursor history(long id) {
        return new MailboxCursor("history", Long.toString(id));
    }

    @Test
    @DisplayName("the first poll stores the transport's start cursor; the IMAP view of it is empty")
    void firstPoll() {
        transport.head = 500;
        poller.poll(ID);
        assertEquals(history(500), stored());
        assertEquals(0, state.read(ID).orElseThrow().uidValidity());
        assertEquals(List.of(), events);
    }

    @Test
    @DisplayName("everything settled: the batch's end is stored, even past the last message")
    void endPastLastMessage() {
        transport.head = 500;
        poller.poll(ID);
        transport.add(501);
        transport.add(502);
        transport.head = 510;

        poller.poll(ID);

        assertEquals(List.of("ledger:501", "mark:501", "ledger:502", "mark:502"), events);
        assertEquals(history(510), stored());
    }

    @Test
    @DisplayName("a deferred message holds the cursor at the last entry settled before it")
    void deferredHolds() {
        transport.head = 500;
        poller.poll(ID);
        transport.add(501);
        transport.add(502);
        transport.add(503);
        transport.head = 510;
        dispositions.put(502L, Disposition.DEFERRED);

        poller.poll(ID);

        assertEquals(history(501), stored());
    }

    @Test
    @DisplayName("a cursor the transport cannot continue from is counted, and the batch's restart cursor is stored")
    void reset() {
        transport.head = 500;
        poller.poll(ID);
        transport.expired = true;
        transport.add(3);
        transport.head = 520;

        var report = poller.poll(ID);

        assertFalse(report.failed(), report.message());
        assertEquals(List.of("ledger:3", "mark:3"), events);
        assertEquals(history(520), stored());
        assertEquals(1.0, registry.get(EmailMetrics.CURSOR_RESETS).counter().count());
    }

    /** A transport whose position is a growing history id. */
    final class HistoryTransport implements MailTransport {
        long head;
        boolean expired;
        final List<EmailTestMail.Polled> messages = new ArrayList<>();

        void add(long id) {
            messages.add(new EmailTestMail.Polled(id, EmailTestMail.mail().bytes(), null, Instant.now(), false));
        }

        @Override
        public String id() {
            return "history";
        }

        @Override
        public MailboxSession open(String integrationId, EmailChannelSettings settings) {
            return new MailboxSession() {
                @Override
                public MailboxCursor startCursor() {
                    return history(head);
                }

                @Override
                public Batch messagesAfter(MailboxCursor cursor, int max) {
                    boolean reset = expired;
                    expired = false;
                    long from = reset ? 0 : Long.parseLong(cursor.position());
                    List<Entry> entries = messages.stream().filter(m -> m.uid() > from).limit(max)
                            .map(m -> new Entry(m, history(m.uid()))).toList();
                    return new Batch(entries, history(from), history(head), reset);
                }

                @Override
                public MarkResult markProcessed(MailboxMessage message, EmailChannelSettings s) {
                    events.add("mark:" + message.uid());
                    return MarkResult.FLAGGED;
                }

                @Override
                public void close() {
                    // nothing to close
                }
            };
        }
    }
}
