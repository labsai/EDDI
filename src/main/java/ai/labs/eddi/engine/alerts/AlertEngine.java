/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Matches inputs against the cached rules and fires: dedupe, throttle, outbox
 * row, delivery. Runs on the alert worker only (see {@link AlertDispatcher}).
 *
 * <h3>Order of a fire</h3>
 * <ol>
 * <li>Insert the event with its {@code dedupeKey}; a duplicate in the same
 * window is refused by the store's unique index — on any replica — and counted
 * on the first event instead.</li>
 * <li>Count it in the rule's window (atomic in the store).</li>
 * <li>Within {@code maxPerWindow}: deliver now. Past it: hold it for the digest
 * ({@code DIGEST_PENDING}), or keep it in the inbox only when the rule has no
 * digest. A rule with no targets is inbox-only from the start.</li>
 * </ol>
 * The row is inserted with {@code nextAttemptAt = now + claimLease}, so a crash
 * between 1 and 3 leaves an event the maintenance job delivers after the lease.
 */
@ApplicationScoped
public class AlertEngine {

    private static final Logger LOGGER = Logger.getLogger(AlertEngine.class);

    private final AlertRuleCache ruleCache;
    private final IAlertEventStore eventStore;
    private final AlertDeliveryService deliveryService;
    private final AlertSettings settings;
    private final AlertMetrics metrics;
    private final Clock clock;

    @Inject
    public AlertEngine(AlertRuleCache ruleCache, IAlertEventStore eventStore, AlertDeliveryService deliveryService, AlertSettings settings,
            AlertMetrics metrics) {
        this(ruleCache, eventStore, deliveryService, settings, metrics, Clock.systemUTC());
    }

    AlertEngine(AlertRuleCache ruleCache, IAlertEventStore eventStore, AlertDeliveryService deliveryService, AlertSettings settings,
            AlertMetrics metrics, Clock clock) {
        this.ruleCache = ruleCache;
        this.eventStore = eventStore;
        this.deliveryService = deliveryService;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void evaluateRating(ConversationRating rating) {
        for (AlertRule rule : ruleCache.rulesFor(rating.agentId())) {
            AlertMatchers.matchRating(rule, rating)
                    .ifPresent(match -> fire(rule, match, rating.agentId(), rating.agentVersion(), rating.conversationId()));
        }
    }

    public void evaluateTurn(TurnOutcome outcome) {
        for (AlertRule rule : ruleCache.rulesFor(outcome.agentId())) {
            AlertMatchers.matchTurn(rule, outcome)
                    .ifPresent(match -> fire(rule, match, outcome.agentId(), outcome.agentVersion(), outcome.conversationId()));
        }
    }

    /**
     * Records and, within the throttle, delivers one alert.
     *
     * @return the event as stored after this fire, or empty when it was a duplicate
     */
    Optional<AlertEvent> fire(AlertRule rule, AlertMatch match, String agentId, Integer agentVersion, String conversationId) {
        Instant now = clock.instant();
        AlertRule.Throttle throttle = rule.effectiveThrottle();
        Duration window = Duration.ofMinutes(Math.max(1, throttle.windowMinutes()));
        Instant windowStart = windowStart(now, window);

        List<Delivery> deliveries = new ArrayList<>();
        for (DeliveryTarget target : rule.delivery()) {
            deliveries.add(Delivery.pending(target));
        }
        AlertEvent event = new AlertEvent(null, rule.id(), rule.name(), rule.source(), AlertEvent.Kind.ALERT, agentId, agentVersion,
                conversationId, now, AlertMatchers.summary(rule, match, agentId, agentVersion), match, deliveries, AlertEvent.State.PENDING,
                deliveries.isEmpty() ? null : now.plus(settings.claimLease()), dedupeKey(rule.id(), agentId, conversationId, windowStart), 0,
                null, null, null, null, 0);

        Optional<AlertEvent> inserted = eventStore.insert(event);
        if (inserted.isEmpty()) {
            metrics.deduplicated();
            return Optional.empty();
        }
        AlertEvent stored = inserted.get();
        metrics.fired(rule.source());
        long count = eventStore.incrementWindow(rule.id(), windowStart, windowStart.plus(window.multipliedBy(2)));

        if (deliveries.isEmpty()) {
            return eventStore.updateDelivery(stored.withDelivery(deliveries, AlertEvent.State.INBOX_ONLY, null), stored.version());
        }
        if (count > throttle.maxPerWindow()) {
            metrics.throttled();
            AlertEvent.State held = rule.effectiveDigest() == AlertRule.DigestMode.NONE
                    ? AlertEvent.State.INBOX_ONLY
                    : AlertEvent.State.DIGEST_PENDING;
            return eventStore.updateDelivery(stored.withDelivery(deliveries, held, null), stored.version());
        }
        Optional<AlertEvent> delivered = deliveryService.deliver(stored, rule);
        if (delivered.isEmpty()) {
            LOGGER.debugf("Alert %s was updated by another writer during its first delivery", sanitize(stored.id()));
        }
        return delivered;
    }

    /**
     * The start of the fixed window {@code now} falls into, aligned to the epoch.
     */
    static Instant windowStart(Instant now, Duration window) {
        long size = window.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), size) * size);
    }

    static String dedupeKey(String ruleId, String agentId, String conversationId, Instant windowStart) {
        return ruleId + "|" + (agentId == null ? "-" : agentId) + "|" + (conversationId == null ? "-" : conversationId) + "|"
                + windowStart.toEpochMilli();
    }
}
