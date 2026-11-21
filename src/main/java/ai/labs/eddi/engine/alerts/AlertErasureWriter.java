/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.gdpr.erasure.AsyncErasureWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Alerting's part in the erasure guarantee (see {@link AsyncErasureWriter}).
 * <p>
 * The guarded rows are the personal parts of alert events: an
 * {@code OUTPUT_MATCH} value stored from a user's conversation (with its
 * internal subject id) and a rating comment excerpt. An event is not deleted —
 * the alert itself names a rule, an agent and a time, and stays in the inbox —
 * its personal part is blanked, which is what "removing the row" means here.
 * <ul>
 * <li>The barrier is the alert worker's queue ({@link AlertDispatcher}).</li>
 * <li>{@link AlertEngine} calls {@code beforeWrite} before storing a value or
 * an excerpt and {@code afterWrite} right after the insert.</li>
 * <li>User erasure: {@link AlertGdprParticipant} blanks and verifies.</li>
 * </ul>
 */
@ApplicationScoped
public class AlertErasureWriter implements AsyncErasureWriter {

    /** Recorded in pending removals: never rename. */
    public static final String NAME = "alerts";

    private final AlertDispatcher dispatcher;
    private final IAlertEventStore eventStore;
    private final AlertErasureRedactor redactor;
    private final Clock clock;

    @Inject
    public AlertErasureWriter(AlertDispatcher dispatcher, IAlertEventStore eventStore, AlertErasureRedactor redactor) {
        this(dispatcher, eventStore, redactor, Clock.systemUTC());
    }

    AlertErasureWriter(AlertDispatcher dispatcher, IAlertEventStore eventStore, AlertErasureRedactor redactor, Clock clock) {
        this.dispatcher = dispatcher;
        this.eventStore = eventStore;
        this.redactor = redactor;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        return dispatcher.awaitDrained(timeout);
    }

    @Override
    public void removeRow(String rowId) {
        removeContent(eventStore, rowId, clock.instant());
    }

    @Override
    public long eraseConversation(String conversationId) {
        // Waits for deliveries holding a send claim, as user erasure does.
        return redactor.redactConversationOutputValues(conversationId);
    }

    @Override
    public long countForConversation(String conversationId) {
        return eventStore.countConversationOutputValues(conversationId);
    }

    /**
     * Blanks one event's personal content and checks that it is gone.
     *
     * @throws IllegalStateException
     *             when the event still holds it — a delivery holds its send claim
     *             (the redaction skips a live claim), so the removal stays pending
     *             until the claim is released
     */
    static void removeContent(IAlertEventStore store, String eventId, Instant now) {
        store.redactEventContent(eventId, now);
        // Read back what the write committed (primary, majority): a lagging read
        // could say "gone" while the content is still stored.
        if (store.holdsPersonalContent(eventId)) {
            throw new IllegalStateException("alert event " + eventId + " still holds erased content (a delivery holds its send claim)");
        }
    }
}
