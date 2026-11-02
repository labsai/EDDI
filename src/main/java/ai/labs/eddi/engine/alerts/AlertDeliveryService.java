/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.AlertSenders.AlertDeliveryException;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertEvent.DeliveryStatus;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Delivers an event's pending targets and records each outcome on the event.
 * <p>
 * A failed target is retried with {@link AlertSettings#backoff} until
 * {@link AlertSettings#maxAttempts}, then marked {@code FAILED}. The event's
 * {@code nextAttemptAt} becomes the earliest pending retry (the maintenance job
 * picks it up from there), and its state {@code DELIVERED} once every target
 * took it, {@code FAILED} once none is pending and one gave up. The update is
 * conditional on the version the caller holds: if another writer moved the
 * event meanwhile, this outcome is dropped rather than overwriting theirs — at
 * worst a target is delivered twice, never lost.
 */
@ApplicationScoped
public class AlertDeliveryService {

    private static final Logger LOGGER = Logger.getLogger(AlertDeliveryService.class);

    /**
     * How long one send holds its claim on the event — well above the webhook
     * timeout, so a send that starts with the lease check passed ends inside it.
     */
    public static final Duration SEND_CLAIM_LEASE = Duration.ofSeconds(30);

    private final IAlertEventStore eventStore;
    private final AlertSenders senders;
    private final AlertSettings settings;
    private final AlertMetrics metrics;
    private final Clock clock;

    @Inject
    public AlertDeliveryService(IAlertEventStore eventStore, AlertSenders senders, AlertSettings settings, AlertMetrics metrics) {
        this(eventStore, senders, settings, metrics, Clock.systemUTC());
    }

    AlertDeliveryService(IAlertEventStore eventStore, AlertSenders senders, AlertSettings settings, AlertMetrics metrics, Clock clock) {
        this.eventStore = eventStore;
        this.senders = senders;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Attempts every pending delivery of {@code event} now.
     *
     * @param rule
     *            the event's rule as it is now, for the targets' addresses and
     *            secrets; {@code null} when it was deleted (every pending target
     *            then fails)
     * @return the stored event after the attempt, or empty when the update lost a
     *         race
     */
    public Optional<AlertEvent> deliver(AlertEvent event, AlertRule rule) {
        if (eventStore.read(event.id()).isEmpty()) {
            // Deleted (retention) since it was claimed: nothing to send.
            return Optional.empty();
        }
        Instant now = clock.instant();
        List<Delivery> outcome = new ArrayList<>();
        for (Delivery delivery : event.deliveries()) {
            boolean due = delivery.status() == DeliveryStatus.PENDING
                    && (delivery.nextAttemptAt() == null || !delivery.nextAttemptAt().isAfter(now));
            outcome.add(due ? attempt(event, delivery, rule, now) : delivery);
        }
        return eventStore.updateDelivery(settle(event, outcome), event.version());
    }

    private Delivery attempt(AlertEvent event, Delivery delivery, AlertRule rule, Instant now) {
        DeliveryTarget target = rule == null
                ? null
                : rule.delivery().stream().filter(t -> t.key().equals(delivery.targetKey())).findFirst().orElse(null);
        int attempts = delivery.attempts() + 1;
        if (target == null) {
            metrics.delivery(delivery.type(), "gave_up");
            return new Delivery(delivery.targetKey(), delivery.type(), delivery.label(), DeliveryStatus.FAILED, attempts, now,
                    rule == null ? "The rule was deleted" : "The target is no longer on the rule", null);
        }
        // Claim the event for this send, then read it, then send it: while the claim
        // is live the GDPR redactions skip the event and the erasure waits, so a send
        // never starts from content an erasure has already finished with. A send
        // that starts before the erasure finishes cannot be recalled.
        String token = UUID.randomUUID().toString();
        Instant claimedAt = clock.instant();
        Instant until = claimedAt.plus(sendLease());
        if (!eventStore.claimSend(event.id(), token, claimedAt, until)) {
            // Another node is sending this event right now: leave it for the next pass.
            return skipped(delivery, now);
        }
        try {
            Optional<AlertEvent> current = eventStore.read(event.id());
            if (current.isEmpty()) {
                metrics.delivery(delivery.type(), "gave_up");
                return new Delivery(delivery.targetKey(), delivery.type(), delivery.label(), DeliveryStatus.FAILED, attempts, now,
                        "The alert no longer exists", null);
            }
            if (Duration.between(clock.instant(), until).compareTo(settings.webhookTimeout()) <= 0) {
                // Too little lease left to finish the send inside it (a slow store): retry
                // later.
                return skipped(delivery, now);
            }
            return send(current.get(), delivery, target, attempts, now);
        } finally {
            eventStore.releaseSend(event.id(), token);
        }
    }

    /** The delivery left as it was, due again after one lease. */
    private Delivery skipped(Delivery delivery, Instant now) {
        return new Delivery(delivery.targetKey(), delivery.type(), delivery.label(), DeliveryStatus.PENDING, delivery.attempts(),
                delivery.lastAttemptAt(), delivery.lastError(), now.plus(sendLease()));
    }

    /**
     * The claim lease: {@link #SEND_CLAIM_LEASE}, or three times the webhook
     * timeout when that is longer, so a configured timeout can never reach the
     * lease (which would leave every send skipped for good).
     */
    Duration sendLease() {
        Duration floor = settings.webhookTimeout().multipliedBy(3);
        return floor.compareTo(SEND_CLAIM_LEASE) > 0 ? floor : SEND_CLAIM_LEASE;
    }

    private Delivery send(AlertEvent event, Delivery delivery, DeliveryTarget target, int attempts, Instant now) {
        try {
            senders.send(event, target);
            metrics.delivery(delivery.type(), "success");
            return new Delivery(delivery.targetKey(), delivery.type(), delivery.label(), DeliveryStatus.DELIVERED, attempts, now, null, null);
        } catch (AlertDeliveryException e) {
            LOGGER.infof("Alert %s not delivered to %s (attempt %d): %s", sanitize(event.id()), sanitize(delivery.label()), attempts,
                    sanitize(e.getMessage()));
            if (attempts >= settings.maxAttempts()) {
                metrics.delivery(delivery.type(), "gave_up");
                return new Delivery(delivery.targetKey(), delivery.type(), delivery.label(), DeliveryStatus.FAILED, attempts, now,
                        e.getMessage(), null);
            }
            metrics.delivery(delivery.type(), "failure");
            return new Delivery(delivery.targetKey(), delivery.type(), delivery.label(), DeliveryStatus.PENDING, attempts, now, e.getMessage(),
                    now.plus(settings.backoff(attempts)));
        } catch (RuntimeException e) {
            // A sender bug must not wedge the event in PENDING with a stale lease.
            LOGGER.warnf(e, "Alert %s: delivery to %s threw", sanitize(event.id()), sanitize(delivery.label()));
            metrics.delivery(delivery.type(), "failure");
            boolean last = attempts >= settings.maxAttempts();
            return new Delivery(delivery.targetKey(), delivery.type(), delivery.label(), last ? DeliveryStatus.FAILED : DeliveryStatus.PENDING,
                    attempts, now, "Internal error while delivering", last ? null : now.plus(settings.backoff(attempts)));
        }
    }

    /**
     * The event with these deliveries, its state and next attempt derived from
     * them.
     */
    static AlertEvent settle(AlertEvent event, List<Delivery> deliveries) {
        Instant next = null;
        boolean anyFailed = false;
        for (Delivery delivery : deliveries) {
            if (delivery.status() == DeliveryStatus.PENDING) {
                Instant at = delivery.nextAttemptAt();
                if (at != null && (next == null || at.isBefore(next))) {
                    next = at;
                }
            } else if (delivery.status() == DeliveryStatus.FAILED) {
                anyFailed = true;
            }
        }
        boolean anyPending = deliveries.stream().anyMatch(d -> d.status() == DeliveryStatus.PENDING);
        AlertEvent.State state = anyPending ? AlertEvent.State.PENDING : anyFailed ? AlertEvent.State.FAILED : AlertEvent.State.DELIVERED;
        return event.withDelivery(deliveries, state, anyPending ? next : null);
    }

    /**
     * Puts the failed targets of an event back to pending, due now — the inbox's
     * "Retry".
     */
    public static AlertEvent resetFailed(AlertEvent event, Instant now) {
        List<Delivery> reset = new ArrayList<>();
        for (Delivery delivery : event.deliveries()) {
            reset.add(delivery.status() == DeliveryStatus.FAILED
                    ? new Delivery(delivery.targetKey(), delivery.type(), delivery.label(), DeliveryStatus.PENDING, 0, delivery.lastAttemptAt(),
                            delivery.lastError(), now)
                    : delivery);
        }
        return settle(event, reset);
    }
}
