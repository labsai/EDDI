/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
    private final ConversationReviewPolicy reviewPolicy;
    private final IConversationRatingStore ratingStore;
    private final ErasureGuard erasureGuard;
    private final Clock clock;

    @Inject
    public AlertEngine(AlertRuleCache ruleCache, IAlertEventStore eventStore, AlertDeliveryService deliveryService, AlertSettings settings,
            AlertMetrics metrics, ConversationReviewPolicy reviewPolicy, IConversationRatingStore ratingStore, ErasureGuard erasureGuard) {
        this(ruleCache, eventStore, deliveryService, settings, metrics, reviewPolicy, ratingStore, erasureGuard, Clock.systemUTC());
    }

    AlertEngine(AlertRuleCache ruleCache, IAlertEventStore eventStore, AlertDeliveryService deliveryService, AlertSettings settings,
            AlertMetrics metrics, ConversationReviewPolicy reviewPolicy, IConversationRatingStore ratingStore, ErasureGuard erasureGuard,
            Clock clock) {
        this.erasureGuard = erasureGuard;
        this.ratingStore = ratingStore;
        this.ruleCache = ruleCache;
        this.eventStore = eventStore;
        this.deliveryService = deliveryService;
        this.settings = settings;
        this.metrics = metrics;
        this.reviewPolicy = reviewPolicy;
        this.clock = clock;
    }

    public void evaluateRating(ConversationRating rating) {
        // The work may have waited in the queue while the rating was erased (GDPR) or
        // changed: evaluate only the rating as it still is, and re-check after the
        // event is written, because an erasure can fall between the two.
        if (!stillCurrent(rating)) {
            return;
        }
        for (AlertRule rule : ruleCache.rulesFor(rating.agentId())) {
            AlertMatchers.matchRating(rule, rating).ifPresent(match -> fire(rule, match, rating.agentId(), rating.agentVersion(),
                    rating.conversationId(), null, stored -> afterRatingInsert(rating, stored)));
        }
    }

    /**
     * {@code true} when the rating still exists unchanged; without a rating store,
     * always.
     */
    boolean stillCurrent(ConversationRating rating) {
        // A rating of an erased user or conversation (GDPR) is not evaluated at all.
        if (!guardAllows(rating.userId(), rating.conversationId(), rating.updatedAt())) {
            return false;
        }
        if (ratingStore == null || rating.id() == null) {
            return true;
        }
        Optional<ConversationRating> now = ratingStore.readById(rating.id());
        return now.isPresent() && Objects.equals(now.get().updatedAt(), rating.updatedAt());
    }

    /**
     * After the event is written: if the rating has been erased meanwhile, the
     * erasure's own sweep may have run before this event existed, so the excerpt is
     * blanked here — before anything is delivered.
     */
    private AlertEvent afterRatingInsert(ConversationRating rating, AlertEvent stored) {
        if (stored.match() == null || stored.match().commentExcerpt() == null) {
            return stored;
        }
        // The erasure guarantee first: an erasure of the user or conversation that
        // started while the event was written removes the excerpt (or leaves a
        // pending removal that keeps that erasure failed until it is gone).
        if (guardRemoved(stored, rating.userId(), rating.conversationId(), rating.updatedAt())) {
            return stored.withMatch(stored.match().withoutCommentExcerpt(), stored.subjectUserId());
        }
        if (ratingStore == null || rating.id() == null || ratingStore.readById(rating.id()).isPresent()) {
            return stored;
        }
        // The rating alone was erased. Runs before this event's first delivery and
        // before the maintenance pass may retry it (its nextAttemptAt is a claim lease
        // away), so no send claim exists yet: the conditional redaction cannot be
        // skipped, and nothing waits on itself.
        eventStore.redactRatingComment(stored.id(), clock.instant());
        return stored.withMatch(stored.match().withoutCommentExcerpt(), stored.subjectUserId());
    }

    public void evaluateTurn(TurnOutcome outcome) {
        for (AlertRule rule : ruleCache.rulesFor(outcome.agentId())) {
            AlertMatchers.matchTurn(rule, outcome)
                    .ifPresent(match -> fire(rule, match, outcome.agentId(), outcome.agentVersion(), outcome.conversationId()));
        }
    }

    /**
     * Matches a finished turn against the agent's OUTPUT_MATCH rules. The runtime
     * value goes into the alert only when the rule asks for it and the agent
     * version the conversation ran on has conversation review enabled; otherwise
     * the alert names the path, the comparison and the rule's constant only.
     */
    public void evaluateOutput(TurnSnapshot snapshot) {
        ruleCache.loadOutputNeeds(snapshot.agentId());
        for (AlertRule rule : ruleCache.rulesFor(snapshot.agentId())) {
            if (!AlertMatchers.applies(rule, AlertSource.OUTPUT_MATCH, snapshot.agentId()) || rule.condition() == null
                    || !OutputMatcher.matches(rule.condition(), snapshot)) {
                continue;
            }
            String value = null;
            // Content of an erased conversation, or captured before an erasure of its
            // user, is never stored (the durable tombstones of ErasureGuard).
            if (Boolean.TRUE.equals(rule.condition().includeValue()) && reviewEnabled(snapshot)
                    && guardAllows(snapshot.userId(), snapshot.conversationId(), snapshot.capturedAt())) {
                value = OutputMatcher.resolve(rule.condition().path(), snapshot)
                        .map(v -> OutputMatcher.render(v, AlertMatch.MAX_COMMENT_EXCERPT)).orElse(null);
            }
            // Whose conversation a stored value came from — internal, so erasure can find
            // it.
            String subject = value == null ? null : snapshot.userId();
            fire(rule, AlertMatch.output(OutputMatcher.describe(rule.condition()), value), snapshot.agentId(), snapshot.agentVersion(),
                    snapshot.conversationId(), subject, stored -> afterOutputInsert(snapshot, stored));
        }
    }

    /**
     * After the event is written: an erasure that started while it was being
     * written may have swept before the event existed, so the value and its subject
     * are removed here, before delivery (see {@link ErasureGuard#afterWrite}).
     */
    private AlertEvent afterOutputInsert(TurnSnapshot snapshot, AlertEvent stored) {
        if (stored.match() == null || stored.match().value() == null
                || !guardRemoved(stored, snapshot.userId(), snapshot.conversationId(), snapshot.capturedAt())) {
            return stored;
        }
        return stored.withMatch(stored.match().withoutValue(), null);
    }

    private boolean guardAllows(String userId, String conversationId, Instant capturedAt) {
        return erasureGuard == null || erasureGuard.beforeWrite(userId, conversationId, capturedAt);
    }

    /**
     * {@code true} when the guard removed the event's personal content (or recorded
     * its removal as pending). Before delivery, so no send claim on the event can
     * exist yet and the conditional redaction is not skipped.
     */
    private boolean guardRemoved(AlertEvent stored, String userId, String conversationId, Instant capturedAt) {
        return erasureGuard != null && erasureGuard.afterWrite(AlertErasureWriter.NAME, stored.id(), userId, conversationId, capturedAt,
                () -> AlertErasureWriter.removeContent(eventStore, stored.id(), clock.instant()));
    }

    private boolean reviewEnabled(TurnSnapshot snapshot) {
        return reviewPolicy != null && reviewPolicy.reviewOf(snapshot.agentId(), snapshot.agentVersion()).isPresent();
    }

    /**
     * Records and, within the throttle, delivers one alert.
     *
     * @return the event as stored after this fire, or empty when it was a duplicate
     */
    Optional<AlertEvent> fire(AlertRule rule, AlertMatch match, String agentId, Integer agentVersion, String conversationId) {
        return fire(rule, match, agentId, agentVersion, conversationId, null, UnaryOperator.identity());
    }

    /**
     * @param subjectUserId
     *            internal: the conversation owner of a stored output value
     * @param afterInsert
     *            runs on the stored event before anything is delivered and returns
     *            the event to continue with
     */
    Optional<AlertEvent> fire(AlertRule rule, AlertMatch match, String agentId, Integer agentVersion, String conversationId,
                              String subjectUserId, UnaryOperator<AlertEvent> afterInsert) {
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
                null, null, null, null, 0, subjectUserId, null);

        Optional<AlertEvent> inserted = eventStore.insert(event);
        if (inserted.isEmpty()) {
            metrics.deduplicated();
            return Optional.empty();
        }
        AlertEvent stored = afterInsert.apply(inserted.get());
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
