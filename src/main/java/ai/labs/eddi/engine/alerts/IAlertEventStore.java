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

    /**
     * GDPR: blanks the comment excerpt of every event about these ratings — except
     * an event whose send claim is live at {@code now}, which is left for the next
     * pass (see {@link #claimSend}).
     */
    long redactRatingComments(Collection<String> ratingIds, Instant now);

    /** How many events about these ratings still hold a comment excerpt. */
    long countRatingComments(Collection<String> ratingIds);

    /** GDPR: forgets {@code userId} as the acknowledger of events. */
    long clearAcknowledger(String userId);

    /**
     * GDPR export: the events {@code userId} acknowledged, most recently
     * acknowledged first, at most {@code limit}.
     */
    List<AlertEvent> findAcknowledgedBy(String userId, int limit);

    /** The event holding this dedupe key, if any (a digest's recovery). */
    Optional<AlertEvent> findByDedupeKey(String dedupeKey);

    /**
     * GDPR erasure: blanks the stored {@code OUTPUT_MATCH} value of every event
     * that came from a conversation of {@code userId}, and forgets the subject.
     *
     * An event whose send claim is live at {@code now} is skipped.
     *
     * @return how many events changed
     */
    long redactOutputValues(String userId, Instant now);

    /**
     * How many events still hold an output value from a conversation of
     * {@code userId}.
     */
    long countOutputValues(String userId);

    /**
     * GDPR export: the events holding a stored {@code OUTPUT_MATCH} value from a
     * conversation of {@code userId}, newest first, at most {@code limit}. The
     * subject itself is not part of the returned events.
     */
    List<AlertEvent> findOutputValuesBySubject(String userId, int limit);

    /**
     * Blanks one event's rating comment excerpt — a rating erased while its alert
     * was being written.
     */
    long redactRatingComment(String eventId, Instant now);

    /**
     * Claims the right to send {@code eventId} until {@code until}: an atomic
     * conditional write that succeeds only when no claim is live at {@code now}
     * (none, or an expired one). While the claim is live, the GDPR redactions skip
     * the event, so a send never starts from content an erasure has already
     * finished with; the erasure waits for the release (or the expiry) instead.
     *
     * @return {@code true} when this caller holds the claim
     */
    boolean claimSend(String eventId, String token, Instant now, Instant until);

    /** Releases the claim — only if it is still {@code token}'s. */
    boolean releaseSend(String eventId, String token);
}
