/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * One pass of the alert maintenance job, run by {@code ScheduleFireExecutor}
 * for the {@code alerts-maintenance} system schedule — under the poller's
 * cluster-wide claim, so one node at a time; and every step is a conditional
 * write as well, so even a duplicated schedule does no harm.
 * <ol>
 * <li><b>Retries</b>: claim events whose next attempt is due and deliver their
 * pending targets.</li>
 * <li><b>Digests</b>: for each enabled rule with a digest, once its oldest held
 * event is older than the interval, one digest event lists the held ones and is
 * delivered.</li>
 * <li><b>Retention</b>: delete events past
 * {@code eddi.alerts.event-retention}.</li>
 * </ol>
 */
@ApplicationScoped
public class AlertMaintenanceService {

    private static final Logger LOGGER = Logger.getLogger(AlertMaintenanceService.class);

    static final int CLAIM_BATCH = 100;
    static final int DIGEST_BATCH = 500;

    /** What one pass did; {@link #isEmpty()} when nothing at all. */
    public record Result(int retried, int digests, long deleted) {
        public boolean isEmpty() {
            return retried == 0 && digests == 0 && deleted == 0;
        }
    }

    private final IAlertEventStore eventStore;
    private final IAlertRuleStore ruleStore;
    private final AlertDeliveryService deliveryService;
    private final AlertSettings settings;
    private final AlertMetrics metrics;
    private final Clock clock;

    @Inject
    public AlertMaintenanceService(IAlertEventStore eventStore, IAlertRuleStore ruleStore, AlertDeliveryService deliveryService,
            AlertSettings settings, AlertMetrics metrics) {
        this(eventStore, ruleStore, deliveryService, settings, metrics, Clock.systemUTC());
    }

    AlertMaintenanceService(IAlertEventStore eventStore, IAlertRuleStore ruleStore, AlertDeliveryService deliveryService,
            AlertSettings settings, AlertMetrics metrics, Clock clock) {
        this.eventStore = eventStore;
        this.ruleStore = ruleStore;
        this.deliveryService = deliveryService;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Result run() {
        Instant now = clock.instant();
        int retried = retryDue(now);
        int digests = sendDueDigests(now);
        long deleted = eventStore.deleteFiredBefore(now.minus(settings.eventRetention()));
        return new Result(retried, digests, deleted);
    }

    int retryDue(Instant now) {
        List<AlertEvent> claimed = eventStore.claimDue(now, now.plus(settings.claimLease()), CLAIM_BATCH);
        Map<String, Optional<AlertRule>> rules = new HashMap<>();
        for (AlertEvent event : claimed) {
            Optional<AlertRule> rule = event.ruleId() == null
                    ? Optional.empty()
                    : rules.computeIfAbsent(event.ruleId(), ruleStore::read);
            try {
                deliveryService.deliver(event, rule.orElse(null));
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Retry of alert %s failed", sanitize(event.id()));
            }
        }
        return claimed.size();
    }

    int sendDueDigests(Instant now) {
        int sent = 0;
        for (AlertRule rule : ruleStore.findEnabled()) {
            Duration interval = interval(rule.effectiveDigest());
            if (interval == null) {
                continue;
            }
            List<AlertEvent> held = eventStore.findDigestPending(rule.id(), DIGEST_BATCH);
            if (held.isEmpty() || held.get(0).firedAt().isAfter(now.minus(interval))) {
                continue;
            }
            try {
                if (sendDigest(rule, held, now)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Digest for alert rule %s failed", sanitize(rule.id()));
            }
        }
        return sent;
    }

    /**
     * Records and delivers one digest of {@code held}. Its dedupe key is the rule
     * and the oldest held event, so two runners cannot both send it.
     */
    boolean sendDigest(AlertRule rule, List<AlertEvent> held, Instant now) {
        List<String> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (AlertEvent event : held) {
            ids.add(event.id());
            if (items.size() < AlertEvent.MAX_DIGEST_ITEMS) {
                items.add(event.firedAt() + " — " + event.summary() + (event.duplicates() > 0 ? " (+" + event.duplicates() + " repeats)" : ""));
            }
        }
        List<Delivery> deliveries = new ArrayList<>();
        for (DeliveryTarget target : rule.delivery()) {
            deliveries.add(Delivery.pending(target));
        }
        String agentId = rule.scope() == null ? null : rule.scope().singleAgent();
        String summary = rule.name() + ": " + held.size() + " alert" + (held.size() == 1 ? "" : "s") + " held back by the throttle";
        // The covered ids are the held batch, at most DIGEST_BATCH long.
        String dedupeKey = "digest|" + rule.id() + "|" + held.get(0).id();
        AlertEvent digest = new AlertEvent(null, rule.id(), rule.name(), rule.source(), AlertEvent.Kind.DIGEST, agentId, null, null, now,
                summary, null, deliveries, deliveries.isEmpty() ? AlertEvent.State.INBOX_ONLY : AlertEvent.State.PENDING,
                deliveries.isEmpty() ? null : now.plus(settings.claimLease()), dedupeKey, 0, held.size(), items, null, null, 0, null, ids);
        Optional<AlertEvent> stored = eventStore.insert(digest);
        if (stored.isEmpty()) {
            // The digest exists already: another runner sent it, or this one crashed
            // between the insert and the mark. Mark exactly the events that digest
            // covers, or they stay DIGEST_PENDING and every pass retries a digest that
            // never inserts; events held after it was written wait for the next one.
            eventStore.findByDedupeKey(dedupeKey).map(AlertEvent::digestEventIds).ifPresent(covered -> {
                if (!covered.isEmpty()) {
                    eventStore.markDigested(covered);
                }
            });
            return false;
        }
        eventStore.markDigested(ids);
        metrics.digest();
        if (!deliveries.isEmpty()) {
            deliveryService.deliver(stored.get(), rule);
        }
        return true;
    }

    static Duration interval(AlertRule.DigestMode mode) {
        return switch (mode) {
            case NONE -> null;
            case HOURLY -> Duration.ofHours(1);
            case DAILY -> Duration.ofDays(1);
        };
    }
}
