/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.GdprComplianceService;
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
 * <b>GDPR.</b> A match names the session's user. When an erasure of that user
 * is in progress ({@link HealthErasureFence}) the event is dropped
 * ({@code erased}); when the user's processing is restricted (Art. 18, as
 * ratings honour it) it is dropped too ({@code restricted}) — nothing is
 * derived from a restricted user's conversation. A restriction that cannot be
 * read leaves the event pending (fail closed, retried). An event for a purged
 * conversation finds no session and expires. <i>Hook for the erasure-guarantee
 * work:</i> {@link #erasedOrRestricted} is the one place a match consults a
 * user's GDPR state.
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
    private final HealthErasureFence fence;
    private final Instance<GdprComplianceService> gdpr;
    private final HealthSettings settings;
    private final HealthMetrics metrics;
    private final Clock clock;

    @Inject
    public HealthEventMatcher(IHealthEventStore eventStore, IHealthOutcomeStore outcomeStore, HealthRefHasher hasher, HealthErasureFence fence,
            Instance<GdprComplianceService> gdpr, HealthSettings settings, HealthMetrics metrics) {
        this(eventStore, outcomeStore, hasher, fence, gdpr, settings, metrics, Clock.systemUTC());
    }

    HealthEventMatcher(IHealthEventStore eventStore, IHealthOutcomeStore outcomeStore, HealthRefHasher hasher, HealthErasureFence fence,
            Instance<GdprComplianceService> gdpr, HealthSettings settings, HealthMetrics metrics, Clock clock) {
        this.eventStore = eventStore;
        this.outcomeStore = outcomeStore;
        this.hasher = hasher;
        this.fence = fence;
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

    /** Tries one event. Never throws. */
    Outcome tryMatch(HealthEvent event) {
        try {
            Match match = match(event);
            if (match == null) {
                return Outcome.PENDING;
            }
            HealthOutcome session = match.session();
            String gdprState = erasedOrRestricted(session.userId());
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
            if (!eventStore.markMatched(matched)) {
                return Outcome.PENDING;
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
     * The GDPR state a match must respect: {@code ""} when the event may be kept,
     * {@link HealthMetrics#EVENT_ERASED} or {@link HealthMetrics#EVENT_RESTRICTED}
     * when it must be dropped, {@code null} when the restriction cannot be read
     * (the event stays pending).
     */
    String erasedOrRestricted(String userId) {
        if (userId == null) {
            return "";
        }
        if (fence != null && fence.covers(userId)) {
            return HealthMetrics.EVENT_ERASED;
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
