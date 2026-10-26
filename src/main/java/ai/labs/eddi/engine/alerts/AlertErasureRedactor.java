/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The GDPR redactions of alert events, run until nothing is left.
 * <p>
 * Each redaction skips an event whose send claim is live (a delivery holding it
 * has read the event and is about to send, or sending). Erasure therefore
 * repeats the redaction, pausing briefly between passes, until no event of the
 * user still holds the content — which happens when the delivery releases its
 * claim, or the claim expires (a node that died mid-send). So erasure completes
 * only once no send of the erased content can still start.
 * <p>
 * The wait is bounded by {@link #MAX_WAIT}, ten times the send lease: a claim
 * always expires within one lease, so only a store that keeps failing to apply
 * the update — or deliveries re-claiming the same event without pause, which
 * the retry backoff rules out — can reach it, and then the step is reported as
 * failed rather than hanging the erasure.
 */
@ApplicationScoped
public class AlertErasureRedactor {

    private static final Logger LOGGER = Logger.getLogger(AlertErasureRedactor.class);

    /** The pause between passes while a claim is live. */
    static final Duration PAUSE = Duration.ofMillis(250);
    /** The longest erasure waits for claims to go. */
    static final Duration MAX_WAIT = AlertDeliveryService.SEND_CLAIM_LEASE.multipliedBy(10);

    /** How erasure pauses between passes; replaceable in tests. */
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final IAlertEventStore eventStore;
    private final Clock clock;
    private final Pause pause;

    @Inject
    public AlertErasureRedactor(IAlertEventStore eventStore) {
        this(eventStore, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()));
    }

    AlertErasureRedactor(IAlertEventStore eventStore, Clock clock, Pause pause) {
        this.eventStore = eventStore;
        this.clock = clock;
        this.pause = pause;
    }

    /**
     * Blanks every OUTPUT_MATCH value stored from a conversation of {@code userId}.
     */
    public long redactOutputValues(String userId) {
        return untilDone("output values", now -> eventStore.redactOutputValues(userId, now), () -> eventStore.countOutputValues(userId));
    }

    /** Blanks the comment excerpt of every event about these ratings. */
    public long redactRatingComments(Collection<String> ratingIds) {
        if (ratingIds == null || ratingIds.isEmpty()) {
            return 0;
        }
        return untilDone("rating comments", now -> eventStore.redactRatingComments(ratingIds, now),
                () -> eventStore.countRatingComments(ratingIds));
    }

    long untilDone(String what, Function<Instant, Long> redact, LongSupplier remaining) {
        Instant deadline = clock.instant().plus(MAX_WAIT);
        long redacted = 0;
        while (true) {
            redacted += redact.apply(clock.instant());
            long left = remaining.getAsLong();
            if (left == 0) {
                return redacted;
            }
            if (clock.instant().isAfter(deadline)) {
                throw new IllegalStateException("Alert " + what + " still held by " + left + " event(s) after " + MAX_WAIT);
            }
            LOGGER.debugf("GDPR: %d alert event(s) with %s are being delivered; waiting for their send claims", left, what);
            try {
                pause.sleep(PAUSE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for alert deliveries to finish", e);
            }
        }
    }
}
