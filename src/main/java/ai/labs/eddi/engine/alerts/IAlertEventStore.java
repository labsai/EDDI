/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEventFilter;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The alert outbox and inbox — MongoDB ({@code alertevents}) or PostgreSQL
 * ({@code alert_events}).
 * <p>
 * <b>Every operation that could run on two replicas at once is a single
 * conditional write</b>, so the throttle, the dedupe, delivery claims and
 * digests hold across a cluster without locks:
 * <ul>
 * <li>{@link #insert} relies on a unique index over {@code dedupeKey};</li>
 * <li>{@link #incrementWindow} is an atomic upsert-increment;</li>
 * <li>{@link #claimDue} and {@link #updateDelivery} are conditional on the
 * event's {@code version};</li>
 * <li>{@link #markDigested} only moves events still
 * {@code DIGEST_PENDING}.</li>
 * </ul>
 */
public interface IAlertEventStore {

    /**
     * Inserts the event under a generated id.
     *
     * @return the stored event, or empty when an event with the same
     *         {@code dedupeKey} exists — whose {@code duplicates} is incremented
     *         instead
     */
    Optional<AlertEvent> insert(AlertEvent event);

    /**
     * Atomically counts one more alert of {@code ruleId} in the window starting at
     * {@code windowStart} and returns the new count. The counter may be removed
     * after {@code expiresAt}.
     */
    long incrementWindow(String ruleId, Instant windowStart, Instant expiresAt);

    Optional<AlertEvent> read(String id);

    /**
     * Replaces the event's deliveries, state and next attempt — only if its version
     * is still {@code expectedVersion}.
     *
     * @return the updated event (version + 1), or empty when another writer got
     *         there first
     */
    Optional<AlertEvent> updateDelivery(AlertEvent event, long expectedVersion);

    /**
     * Claims up to {@code limit} events whose next attempt is due: each is moved to
     * {@code leaseUntil} by a conditional write, so two runners never claim the
     * same event.
     */
    List<AlertEvent> claimDue(Instant now, Instant leaseUntil, int limit);

    List<AlertEvent> list(AlertEventFilter filter, int offset, int limit);

    long count(AlertEventFilter filter);

    /**
     * Acknowledges one event; {@code false} when it does not exist or already was.
     */
    boolean acknowledge(String id, String by, Instant at);

    /** Acknowledges every unacknowledged event matching the filter. */
    long acknowledgeMatching(AlertEventFilter filter, String by, Instant at);

    /** Acknowledges the listed events that are not yet acknowledged. */
    long acknowledgeIds(Collection<String> ids, String by, Instant at);

    /** The rule's events held for its digest, oldest first. */
    List<AlertEvent> findDigestPending(String ruleId, int limit);

    /**
     * Moves the listed events from {@code DIGEST_PENDING} to {@code DIGESTED}; an
     * event in any other state is left alone.
     *
     * @return how many moved
     */
    long markDigested(Collection<String> ids);

    /** Deletes events fired before {@code cutoff}; returns how many. */
    long deleteFiredBefore(Instant cutoff);

    /** GDPR: blanks the comment excerpt of every event about these ratings. */
    long redactRatingComments(Collection<String> ratingIds);

    /** GDPR: forgets {@code userId} as the acknowledger of events. */
    long clearAcknowledger(String userId);
}
