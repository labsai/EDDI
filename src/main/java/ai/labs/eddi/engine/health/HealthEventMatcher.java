/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.erasure.DrainBarrier;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard.Admission;
import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Matches pending business events to the sessions they are about — a step of
 * every {@code health-rollup} pass, run under its schedule claim right before
 * the outcome evaluation, so a match is judged in the same pass (plan §5, H5).
 * <p>
 * <b>The match rule.</b>
 * <ul>
 * <li>An event that names a <b>conversation</b> ({@code conversation_id_event})
 * belongs to that conversation's session that started last at or before the
 * event's {@code at} (plus {@link #CLOCK_SKEW}); when every session started
 * later, to the first one. A conversation of another agent never matches — the
 * event is bound to the agent its target named.</li>
 * <li>An event that names a <b>reference</b> ({@code external_event}) belongs,
 * among the agent's sessions carrying that reference, to the session that
 * <em>set</em> it: per conversation the earliest session carrying it (a
 * conversation-scoped property is carried into the conversation's later
 * sessions, which did not set it), and of those, the one that started last at
 * or before {@code at} (plus {@link #CLOCK_SKEW}) — the most recent
 * conversation about that business object before the event happened. A plain
 * reference also matches sessions that recorded it hashed (the agent switched
 * {@code hashRefs} on since).</li>
 * </ul>
 * An event that matches nothing yet stays pending — its session may not be
 * evaluated yet, or its conversation's rows not written — and is tried again
 * every pass until {@code eddi.health.event-match-window} (72 h) after its
 * receipt; then it is deleted and counted {@code expired}.
 * <p>
 * <b>GDPR.</b> A match writes the session's user and conversation onto the
 * event, so it is a guarded write of Agent Health's async writer
 * ({@link HealthErasureWriter}, the
 * {@linkplain ai.labs.eddi.engine.gdpr.erasure.AsyncErasureWriter erasure
 * guarantee}): every match runs through a {@link DrainBarrier}
 * ({@link #awaitDrained}), so an erasure or a permanent conversation delete
 * waits for the matches in flight; before the write it asks
 * {@link ErasureGuard#beforeWrite} — captured at the session's start, so a
 * session of an erased user that began before the erasure is refused — and a
 * refused match leaves the event pending: it names no user, and once the
 * erasure deleted the sessions it can no longer match and expires (a refusal
 * because the tombstones could not be read is retried with the next pass).
 * Right after the write it asks again ({@link ErasureGuard#afterWrite}) and
 * deletes the event when an erasure started meanwhile, or records a pending
 * removal ({@code event:<conversationId>|<id>}); such an event is counted
 * {@code erased}. A refused match leaves the event pending: unless a later pass
 * matches it, it ends as {@code expired}. When the user's processing is
 * restricted (Art. 18, as ratings honour it) the event is dropped
 * ({@code restricted}) — nothing is derived from a restricted user's
 * conversation. A restriction that cannot be read leaves the event pending
 * (fail closed, retried). An event for a purged conversation finds no session
 * and expires.
 * <p>
 * A matched event marks its session due, so the outcome evaluation that follows
 * judges it again — the external outcome overrides every other source
 * ({@link HealthSessionEvaluator}).
 */
@ApplicationScoped
public class HealthEventMatcher {

    private static final Logger LOGGER = Logger.getLogger(HealthEventMatcher.class);

    /** How far an event's {@code at} may precede the session it is about. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    /**
     * The most events one pass tries, oldest receipt first. A backlog larger than
     * this is worked through as the oldest match or expire.
     */
    static final int MAX_PER_PASS = 5_000;
    /** Sessions read per reference. */
    static final int MAX_SESSIONS_PER_REF = 200;

    /** What one pass did. */
    public record Result(int matched, int dropped, long expired) {
    }

    /** A session an event matched, and the evidence it rests on. */
    record Match(HealthOutcome session, String evidence) {
    }

    private final IHealthEventStore eventStore;
    private final IHealthOutcomeStore outcomeStore;
    private final HealthRefHasher hasher;
    /** Null only in tests that look at the matching alone. */
    private final ErasureGuard erasureGuard;
    private final Instance<GdprComplianceService> gdpr;
    private final HealthSettings settings;
    private final HealthMetrics metrics;
    private final Clock clock;
    /** Every match runs through it: the matcher's part of the drain barrier. */
    private final DrainBarrier barrier = new DrainBarrier();

    @Inject
    public HealthEventMatcher(IHealthEventStore eventStore, IHealthOutcomeStore outcomeStore, HealthRefHasher hasher, ErasureGuard erasureGuard,
            Instance<GdprComplianceService> gdpr, HealthSettings settings, HealthMetrics metrics) {
        this(eventStore, outcomeStore, hasher, erasureGuard, gdpr, settings, metrics, Clock.systemUTC());
    }

    HealthEventMatcher(IHealthEventStore eventStore, IHealthOutcomeStore outcomeStore, HealthRefHasher hasher, ErasureGuard erasureGuard,
            Instance<GdprComplianceService> gdpr, HealthSettings settings, HealthMetrics metrics, Clock clock) {
        this.eventStore = eventStore;
        this.outcomeStore = outcomeStore;
        this.hasher = hasher;
        this.erasureGuard = erasureGuard;
        this.gdpr = gdpr;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** One pass, stopping at {@code deadline}. Never throws. */
    public Result run(Instant deadline) {
        Instant now = clock.instant();
        long expired = 0;
        try {
            expired = eventStore.deletePendingReceivedBefore(now.minus(settings.eventMatchWindow()));
            metrics.events(HealthMetrics.EVENT_EXPIRED, expired);
            if (expired > 0) {
                LOGGER.infof("Dropped %d Agent Health business event(s) that matched no session within %s", expired, settings.eventMatchWindow());
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not drop unmatched Agent Health business events; the next pass retries: %s", sanitize(e.getMessage()));
        }
        int matched = 0;
        int dropped = 0;
        try {
            for (HealthEvent event : eventStore.findPending(MAX_PER_PASS)) {
                if (!clock.instant().isBefore(deadline)) {
                    break;
                }
                switch (tryMatch(event)) {
                    case MATCHED -> matched++;
                    case DROPPED -> dropped++;
                    default -> {
                        // still pending
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not match the pending Agent Health business events; the next pass retries: %s", sanitize(e.getMessage()));
        }
        return new Result(matched, dropped, expired);
    }

    enum Outcome {
        MATCHED, DROPPED, PENDING
    }

    /**
     * Waits until every match started before the call has finished — the matcher's
     * part of the drain barrier of {@link HealthErasureWriter}.
     *
     * @return {@code false} on timeout
     */
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        return barrier.awaitDrained(timeout);
    }

    /** Tries one event, tracked by the drain barrier. Never throws. */
    Outcome tryMatch(HealthEvent event) {
        Outcome[] outcome = {Outcome.PENDING};
        barrier.execute(Runnable::run, () -> outcome[0] = tryMatchTracked(event));
        return outcome[0];
    }

    private Outcome tryMatchTracked(HealthEvent event) {
        try {
            Match match = match(event);
            if (match == null) {
                return Outcome.PENDING;
            }
            HealthOutcome session = match.session();
            // Before the restriction: a refused write must not delete the event either.
            Admission admission = admit(session);
            if (admission != null && !admission.allowed()) {
                return Outcome.PENDING;
            }
            String gdprState = restricted(session.userId());
            if (gdprState == null) {
                return Outcome.PENDING;
            }
            if (!gdprState.isEmpty()) {
                eventStore.delete(List.of(event.id()));
                metrics.events(gdprState, 1);
                return Outcome.DROPPED;
            }
            Instant now = clock.instant();
            HealthEvent matched = event.matched(session.conversationId(), session.sessionNo(), session.agentVersion(), session.origin(),
                    session.userId(), match.evidence(), now);
            String conversationId = session.conversationId();
            boolean recorded = false;
            boolean withheld = false;
            try {
                recorded = eventStore.markMatched(matched);
            } finally {
                // Also after a failed write, whose update may have applied.
                if (admission != null) {
                    withheld = erasureGuard.afterWrite(HealthErasureWriter.NAME, HealthErasureWriter.eventRowId(conversationId, event.id()),
                            admission, () -> eventStore.deleteInConversation(event.id(), conversationId));
                }
            }
            if (!recorded) {
                return Outcome.PENDING;
            }
            if (withheld) {
                // An erasure started while the match was written: the event is gone (or
                // its removal pending), and its session is not judged on it.
                metrics.events(HealthMetrics.EVENT_ERASED, 1);
                return Outcome.DROPPED;
            }
            outcomeStore.markDue(session.conversationId(), List.of(session.sessionNo()), now);
            metrics.events(HealthMetrics.EVENT_MATCHED, 1);
            return Outcome.MATCHED;
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not match Agent Health event %s: %s", sanitize(event.id()), sanitize(e.getMessage()));
            return Outcome.PENDING;
        }
    }

    /**
     * The guard's admission of a match to {@code session}; {@code null} without a
     * guard (everything allowed). Captured at the session's start: a session of an
     * erased user that began before the erasure is refused, one of the user's later
     * conversations is not.
     */
    private Admission admit(HealthOutcome session) {
        if (erasureGuard == null) {
            return null;
        }
        return erasureGuard.beforeWrite(session.userId(), session.conversationId(), session.startedAt());
    }

    /**
     * The processing restriction a match must respect: {@code ""} when the event
     * may be kept, {@link HealthMetrics#EVENT_RESTRICTED} when it must be dropped,
     * {@code null} when the restriction cannot be read (the event stays pending).
     */
    String restricted(String userId) {
        if (userId == null) {
            return "";
        }
        if (gdpr == null || !gdpr.isResolvable()) {
            return "";
        }
        try {
            return gdpr.get().isProcessingRestricted(userId) ? HealthMetrics.EVENT_RESTRICTED : "";
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The session an event is about, or {@code null} (see the class comment). */
    Match match(HealthEvent event) {
        Instant latestStart = event.at() == null ? null : event.at().plus(CLOCK_SKEW);
        if (event.byConversation()) {
            if (event.conversationId() == null) {
                return null;
            }
            HealthOutcome chosen = null;
            HealthOutcome first = null;
            for (HealthOutcome s : outcomeStore.findByConversation(event.conversationId())) {
                if (!event.agentId().equals(s.agentId())) {
                    continue;
                }
                if (first == null || s.sessionNo() < first.sessionNo()) {
                    first = s;
                }
                if (startedBy(s, latestStart) && (chosen == null || s.sessionNo() > chosen.sessionNo())) {
                    chosen = s;
                }
            }
            HealthOutcome session = chosen != null ? chosen : first;
            return session == null ? null : new Match(session, HealthOutcomes.EVIDENCE_CONVERSATION_ID_EVENT);
        }
        Set<String> keys = keysOf(event);
        if (keys.isEmpty()) {
            return null;
        }
        // Per conversation, the earliest session carrying the reference: the one that
        // set it.
        Map<String, HealthOutcome> setting = new LinkedHashMap<>();
        for (HealthOutcome s : outcomeStore.findByRefs(event.agentId(), keys, MAX_SESSIONS_PER_REF)) {
            if (!event.agentId().equals(s.agentId())) {
                continue;
            }
            setting.merge(s.conversationId(), s, (a, b) -> a.sessionNo() <= b.sessionNo() ? a : b);
        }
        return setting.values().stream().filter(s -> startedBy(s, latestStart))
                .max(Comparator.comparing(HealthOutcome::startedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(HealthOutcome::conversationId))
                .map(s -> new Match(s, HealthOutcomes.EVIDENCE_EXTERNAL_EVENT)).orElse(null);
    }

    /** The stored forms an event's reference can have been recorded in. */
    Set<String> keysOf(HealthEvent event) {
        if (event.ref() == null) {
            return Set.of();
        }
        if (HealthRefs.isHashed(event.ref())) {
            return Set.of(event.ref());
        }
        String name = HealthRefs.nameOf(event.ref());
        String value = event.refValue() != null ? event.refValue() : HealthRefs.valueOf(event.ref());
        return HealthRefs.matchKeys(name, value, hasher);
    }

    private static boolean startedBy(HealthOutcome s, Instant latestStart) {
        return latestStart == null || s.startedAt() == null || !s.startedAt().isAfter(latestStart);
    }
}
