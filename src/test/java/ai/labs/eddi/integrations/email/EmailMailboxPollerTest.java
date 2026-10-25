/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.Disposition;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.integrations.email.transport.MailboxException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailMailboxPollerTest {

    static final String ID = "a1b2c3d4e5f60718293a4b5c";

    EmailIntegrationDirectory directory;
    EmailInboundProcessor processor;
    InMemoryEmailMailboxStateStore state;
    FakeTransport transport;
    EmailPollScheduler scheduler;
    EmailMailboxPoller poller;
    List<String> events;
    Map<Long, Disposition> dispositions;

    @BeforeEach
    void setUp() {
        directory = mock(EmailIntegrationDirectory.class);
        var configuration = EmailInboundProcessorTest.integration(Map.of());
        when(directory.find(ID))
                .thenReturn(Optional.of(new EmailIntegration(ID, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
        when(directory.otherAddresses(ID)).thenReturn(Set.of());
        events = new ArrayList<>();
        dispositions = new HashMap<>();
        processor = mock(EmailInboundProcessor.class);
        when(processor.process(any(), any(), any())).thenAnswer(invocation -> {
            MailTransport.MailboxMessage message = invocation.getArgument(1);
            events.add("ledger:" + message.uid());
            return dispositions.getOrDefault(message.uid(), Disposition.SETTLED);
        });
        state = new InMemoryEmailMailboxStateStore();
        transport = new FakeTransport();
        scheduler = mock(EmailPollScheduler.class);
        poller = new EmailMailboxPoller(directory, processor, state, transport, scheduler, mock(EmailOwnerNotifier.class), new SimpleMeterRegistry(),
                50,
                Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("the first poll starts after the mail already in the folder; later mail is processed, then marked")
    void firstPollThenNewMail() {
        transport.uidNext = 100;
        poller.poll(ID);
        assertEquals(99, state.read(ID).orElseThrow().lastUid());
        assertTrue(events.isEmpty());

        transport.add(100, Instant.now());
        transport.add(101, Instant.now());
        poller.poll(ID);

        assertEquals(List.of("ledger:100", "mark:100", "ledger:101", "mark:101"), events, "marked only after its ledger row was written");
        assertEquals(101, state.read(ID).orElseThrow().lastUid());
    }

    @Test
    @DisplayName("a deferred mail is not marked and holds the cursor; later mail still runs")
    void deferredHoldsCursor() {
        initialise(9);
        transport.add(10, Instant.now());
        transport.add(11, Instant.now());
        dispositions.put(10L, Disposition.DEFERRED);

        poller.poll(ID);

        assertEquals(List.of("ledger:10", "ledger:11", "mark:11"), events);
        assertEquals(9, state.read(ID).orElseThrow().lastUid(), "the cursor stays before the deferred mail");
    }

    @Test
    @DisplayName("STOP (a budget) ends the batch")
    void stopEndsBatch() {
        initialise(9);
        transport.add(10, Instant.now());
        transport.add(11, Instant.now());
        dispositions.put(10L, Disposition.STOP);

        poller.poll(ID);

        assertEquals(List.of("ledger:10"), events);
    }

    @Test
    @DisplayName("flagged mail and mail older than the integration are skipped without the ledger")
    void skips() {
        initialise(9);
        transport.add(10, Instant.now()).processed = true;
        transport.add(11, Instant.now().minus(Duration.ofDays(3)));
        transport.add(12, Instant.now());

        poller.poll(ID);

        assertEquals(List.of("ledger:12", "mark:12"), events);
        assertEquals(12, state.read(ID).orElseThrow().lastUid());
    }

    @Test
    @DisplayName("a changed UIDVALIDITY rescans the folder from the start")
    void uidValidityReset() {
        initialise(9);
        transport.uidValidity = 2;
        transport.add(3, Instant.now());

        poller.poll(ID);

        assertEquals(List.of("ledger:3", "mark:3"), events);
        assertEquals(2, state.read(ID).orElseThrow().uidValidity());
    }

    @Test
    @DisplayName("a connection failure is recorded and backs off; the next poll does not connect until it has passed")
    void backoff() {
        transport.failure = new MailboxException(MailboxException.Category.AUTHENTICATION, "login refused", null);

        var report = poller.poll(ID);

        assertTrue(report.failed());
        var recorded = state.read(ID).orElseThrow();
        assertEquals(1, recorded.failures());
        assertNotNull(recorded.backoffUntil());
        assertTrue(recorded.lastError().startsWith("AUTHENTICATION"));

        transport.failure = null;
        transport.opened = 0;
        assertFalse(poller.poll(ID).failed());
        assertEquals(0, transport.opened, "still backing off");
        assertEquals(Duration.ofSeconds(120), EmailMailboxPoller.backoff(60, 2));
        assertEquals(Duration.ofHours(1), EmailMailboxPoller.backoff(60, 30));
    }

    @Test
    @DisplayName("a deleted integration removes its own schedule and touches no mailbox")
    void deletedIntegration() {
        when(directory.find(ID)).thenReturn(Optional.empty());
        poller.poll(ID);
        verify(scheduler).remove(ID);
        assertEquals(0, transport.opened);
        verify(processor, never()).process(any(), any(), any());
    }

    @Test
    @DisplayName("a configuration that no longer validates is not polled")
    void invalidConfiguration() {
        ChannelIntegrationConfiguration broken = EmailInboundProcessorTest.integration(Map.of("mode", "conversation"));
        when(directory.find(eq(ID))).thenReturn(Optional.of(new EmailIntegration(ID, broken, EmailChannelSettings.from(broken.getPlatformConfig()))));
        assertTrue(poller.poll(ID).failed());
        assertEquals(0, transport.opened);
    }

    private void initialise(long lastUid) {
        state.saveCursor(ID, "INBOX", 1, lastUid, Instant.now().minus(Duration.ofDays(1)), Instant.now());
    }

    final class FakeTransport implements MailTransport {
        long uidValidity = 1;
        long uidNext = 1;
        int opened;
        MailboxException failure;
        final List<EmailTestMail.Polled> messages = new ArrayList<>();

        EmailTestMail.Polled add(long uid, Instant internalDate) {
            var polled = new EmailTestMail.Polled(uid, EmailTestMail.mail().bytes(), null, internalDate, false);
            messages.add(polled);
            return polled;
        }

        @Override
        public String id() {
            return "imap";
        }

        @Override
        public MailboxSession open(String integrationId, EmailChannelSettings settings) throws MailboxException {
            if (failure != null) {
                throw failure;
            }
            opened++;
            return new MailboxSession() {
                @Override
                public long uidValidity() {
                    return uidValidity;
                }

                @Override
                public long uidNext() {
                    return uidNext;
                }

                @Override
                public List<MailboxMessage> messagesAfter(long lastUid, int max) {
                    return messages.stream().filter(m -> m.uid() > lastUid).limit(max).map(m -> (MailboxMessage) m).toList();
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

    @Test
    @DisplayName("the processor is never asked about mail without its integration context")
    void contextCarriesIntegration() {
        initialise(0);
        transport.add(1, Instant.now());
        poller.poll(ID);
        verify(processor).process(argThat(ctx -> ID.equals(ctx.integrationId())), any(), any());
        verify(scheduler, never()).remove(anyString());
    }
}
