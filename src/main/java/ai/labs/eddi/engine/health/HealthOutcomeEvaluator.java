/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.model.ConversationActivity;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.ratings.ConversationRatingSaved;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The outcome evaluation — the first step of every {@code health-rollup} pass
 * ({@link HealthRollupService}), run under the same schedule claim, so one node
 * evaluates at a time and no second system job, claim or lease is needed. It
 * reads only turn rows and ratings, never conversation memory, and writes
 * {@link IHealthOutcomeStore} rows through {@link HealthSessionEvaluator}.
 * <p>
 * Which conversations, per pass:
 * <ol>
 * <li><b>the incremental feed</b> — conversations with turn rows written since
 * the stored watermark, up to one minute ago; paged by each conversation's
 * newest write, oldest first, so a pass can stop anywhere and the next resumes
 * without a gap ({@link #GRACE}, so rows still on a node's queue are taken next
 * time). The first pass starts at the turn-row retention, so turns recorded
 * before outcomes existed are judged too, a bounded batch per pass;</li>
 * <li><b>due sessions</b> — sessions whose window has closed since they were
 * judged ({@code dueAt}), and those a new rating made due
 * ({@link #ratingSaved}). A late row is also caught here: a session is read
 * again in full when its window closes.</li>
 * </ol>
 * Per conversation it reads the turn rows from the latest stored session on (or
 * from the earliest due one), so a long conversation is not re-read from its
 * start: earlier sessions are closed and keep their rows. At most
 * {@link #MAX_SESSION_TURNS} rows are read — the newest; a longer session
 * counts its turns and spend from those. Sessions it no longer finds from that
 * step on (an undo removed a session's first turn) are deleted.
 * <p>
 * GDPR: nothing is written for a user an erasure is fencing
 * ({@link HealthErasureFence}), and a conversation whose user became fenced
 * while it was evaluated has its rows deleted again; a conversation deleted
 * meanwhile is caught by the daily orphan sweep, which covers outcome rows.
 */
@ApplicationScoped
public class HealthOutcomeEvaluator {

    private static final Logger LOGGER = Logger.getLogger(HealthOutcomeEvaluator.class);

    /** Rows younger than this are left for the next pass. */
    static final Duration GRACE = Duration.ofMinutes(1);
    /** Conversations read per page of the feed. */
    static final int FEED_PAGE = 500;
    /** Due sessions read per pass. */
    static final int DUE_PAGE = 1_000;
    /** The most conversations one pass evaluates. */
    static final int MAX_CONVERSATIONS = 5_000;
    /** The most turn rows read for one conversation. */
    static final int MAX_SESSION_TURNS = 1_000;
    /** A rating below this normalised score is negative. */
    static final double NEGATIVE_RATING_BELOW = 0.5;

    /** What one pass did. */
    public record Result(int conversations, int sessions, int failed) {
        public boolean isEmpty() {
            return conversations == 0 && failed == 0;
        }
    }

    private final IHealthTurnStore turnStore;
    private final IHealthOutcomeStore outcomeStore;
    private final HealthConfigPolicy configPolicy;
    private final IConversationRatingStore ratingStore;
    private final HealthErasureFence fence;
    private final HealthSettings settings;
    private final HealthMetrics metrics;
    private final Clock clock;

    @Inject
    public HealthOutcomeEvaluator(IHealthTurnStore turnStore, IHealthOutcomeStore outcomeStore, HealthConfigPolicy configPolicy,
            IConversationRatingStore ratingStore, HealthErasureFence fence, HealthSettings settings, HealthMetrics metrics) {
        this(turnStore, outcomeStore, configPolicy, ratingStore, fence, settings, metrics, Clock.systemUTC());
    }

    HealthOutcomeEvaluator(IHealthTurnStore turnStore, IHealthOutcomeStore outcomeStore, HealthConfigPolicy configPolicy,
            IConversationRatingStore ratingStore, HealthErasureFence fence, HealthSettings settings, HealthMetrics metrics, Clock clock) {
        this.turnStore = turnStore;
        this.outcomeStore = outcomeStore;
        this.configPolicy = configPolicy;
        this.ratingStore = ratingStore;
        this.fence = fence;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** One pass, stopping at {@code deadline}. Never throws. */
    public Result run(Instant deadline) {
        int[] done = {0, 0, 0};
        Set<String> seen = new HashSet<>();
        try {
            feed(deadline, done, seen);
        } catch (RuntimeException e) {
            LOGGER.warnf("The Agent Health outcome feed failed; the next pass resumes from the same watermark: %s", sanitize(e.getMessage()));
        }
        try {
            due(deadline, done, seen);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read the due Agent Health sessions; the next pass retries: %s", sanitize(e.getMessage()));
        }
        metrics.outcomesEvaluated(done[1], done[2]);
        return new Result(done[0], done[1], done[2]);
    }

    private void feed(Instant deadline, int[] done, Set<String> seen) {
        Instant now = clock.instant();
        Instant until = now.minus(GRACE);
        Instant since = outcomeStore.watermark().orElse(now.minus(settings.factsRetention()));
        while (since.isBefore(until) && clock.instant().isBefore(deadline) && done[0] < MAX_CONVERSATIONS) {
            List<ConversationActivity> page = turnStore.conversationsUpdatedBetween(since, until, FEED_PAGE);
            Instant next = until;
            boolean complete = true;
            for (ConversationActivity activity : page) {
                if (!clock.instant().isBefore(deadline) || done[0] >= MAX_CONVERSATIONS) {
                    // Resume at this conversation's newest write next time.
                    next = activity.lastUpdatedAt();
                    complete = false;
                    break;
                }
                if (seen.add(activity.conversationId())) {
                    evaluate(activity.conversationId(), null, done);
                }
            }
            if (complete && page.size() >= FEED_PAGE) {
                Instant last = page.get(page.size() - 1).lastUpdatedAt();
                // More than a page shares one millisecond: step past it rather than loop.
                next = last.isAfter(since) ? last : since.plusMillis(1);
            }
            outcomeStore.setWatermark(next);
            since = next;
            if (!complete) {
                return;
            }
        }
    }

    private void due(Instant deadline, int[] done, Set<String> seen) {
        if (!clock.instant().isBefore(deadline)) {
            return;
        }
        Map<String, Integer> earliest = new LinkedHashMap<>();
        for (HealthOutcome due : outcomeStore.findDue(clock.instant(), DUE_PAGE)) {
            earliest.merge(due.conversationId(), due.sessionNo(), Math::min);
        }
        for (Map.Entry<String, Integer> entry : earliest.entrySet()) {
            if (!clock.instant().isBefore(deadline) || done[0] >= MAX_CONVERSATIONS) {
                return;
            }
            evaluate(entry.getKey(), entry.getValue(), done);
            seen.add(entry.getKey());
        }
    }

    /**
     * Evaluates one conversation from {@code fromSession} on ({@code null}: from
     * its latest stored session). Never throws.
     */
    void evaluate(String conversationId, Integer fromSession, int[] done) {
        try {
            int written = evaluate(conversationId, fromSession);
            done[0]++;
            done[1] += written;
        } catch (RuntimeException e) {
            done[2]++;
            LOGGER.debugf("Could not evaluate the sessions of conversation %s: %s", sanitize(conversationId), sanitize(e.getMessage()));
        }
    }

    /** @return the session rows written */
    int evaluate(String conversationId, Integer fromSession) {
        List<HealthOutcome> stored = outcomeStore.findByConversation(conversationId);
        int from = fromSession != null ? fromSession : stored.isEmpty() ? 0 : stored.get(stored.size() - 1).sessionNo();
        List<HealthTurn> rows = turnStore.findByConversationFromStep(conversationId, from, MAX_SESSION_TURNS);
        List<HealthTurn> live = rows.stream().filter(t -> t.recorded() && !t.discarded()).toList();
        Set<Integer> stale = new HashSet<>();
        for (HealthOutcome o : stored) {
            if (o.sessionNo() >= from) {
                stale.add(o.sessionNo());
            }
        }
        if (live.isEmpty()) {
            // Every turn from there on was undone or is gone: so are its sessions.
            outcomeStore.deleteSessions(conversationId, stale);
            return 0;
        }
        HealthTurn newest = live.get(live.size() - 1);
        String userId = newest.userId();
        if (fence.covers(userId)) {
            return 0;
        }
        String origin = origin(stored, from, live);
        Resolved current = configPolicy.of(newest.agentId(), newest.agentVersion());
        Duration window = configPolicy.windowOf(origin, current);
        Integer knownFirstOutput = null;
        for (HealthOutcome o : stored) {
            if (o.sessionNo() < from && o.firstOutputStep() != null && (knownFirstOutput == null || o.firstOutputStep() < knownFirstOutput)) {
                knownFirstOutput = o.firstOutputStep();
            }
        }
        // Read only all MAX_SESSION_TURNS when there were more: the first session then
        // keeps its stored key although its first turns were not read.
        Integer firstSessionNo = rows.size() >= MAX_SESSION_TURNS && !stale.isEmpty() ? Integer.valueOf(from) : null;
        Map<Integer, Resolved> configs = new HashMap<>();
        String agentId = newest.agentId();
        List<HealthOutcome> sessions = HealthSessionEvaluator.evaluate(new HealthSessionEvaluator.Input(conversationId, live, origin, window,
                version -> configs.computeIfAbsent(version == null ? -1 : version, v -> configPolicy.of(agentId, version)), knownFirstOutput,
                firstSessionNo, new LazyRatings(conversationId), clock.instant()));
        outcomeStore.upsert(sessions);
        for (HealthOutcome s : sessions) {
            stale.remove(s.sessionNo());
        }
        outcomeStore.deleteSessions(conversationId, stale);
        if (fence.covers(userId)) {
            // An erasure started while this conversation was evaluated.
            outcomeStore.deleteByConversationId(conversationId);
            return 0;
        }
        return sessions.size();
    }

    /**
     * The conversation's origin: its first turn row that is not {@code api} — the
     * stored one when an earlier session already found it, since the rows read now
     * may start later.
     */
    static String origin(List<HealthOutcome> stored, int from, List<HealthTurn> live) {
        for (HealthOutcome o : stored) {
            if (o.sessionNo() <= from && o.origin() != null && !HealthOrigins.API.equals(o.origin())) {
                return o.origin();
            }
        }
        for (HealthTurn t : live) {
            if (t.origin() != null && !HealthOrigins.API.equals(t.origin())) {
                return t.origin();
            }
        }
        return HealthOrigins.API;
    }

    void onRatingSaved(@Observes ConversationRatingSaved saved) {
        if (saved != null && saved.rating() != null) {
            ratingSaved(saved.rating().conversationId());
        }
    }

    /**
     * A rating was saved: the conversation's sessions are evaluated again at the
     * next pass, since a negative rating turns a silence into
     * {@code ENDED_QUIETLY}. Never throws.
     */
    public void ratingSaved(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            outcomeStore.markDue(conversationId, clock.instant());
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not mark the sessions of conversation %s for another look: %s", sanitize(conversationId),
                    sanitize(e.getMessage()));
        }
    }

    /**
     * The conversation's ratings, read once and only if a session needs them. A
     * reply rating counts for the session it rated; the conversation rating for the
     * session it was given in (its start to its window's close).
     */
    final class LazyRatings implements HealthSessionEvaluator.Ratings {
        private final String conversationId;
        private List<ConversationRating> ratings;

        LazyRatings(String conversationId) {
            this.conversationId = conversationId;
        }

        @Override
        public boolean negative(int fromStep, int toStep, Instant from, Instant until) {
            if (ratings == null) {
                try {
                    ratings = ratingStore == null ? List.of() : new ArrayList<>(ratingStore.readByConversation(conversationId));
                } catch (RuntimeException e) {
                    // Unknown is not negative: the silence rule applies as without ratings.
                    ratings = List.of();
                }
            }
            for (ConversationRating r : ratings) {
                if (r.normalizedScore() >= NEGATIVE_RATING_BELOW) {
                    continue;
                }
                if (r.isConversationRating()) {
                    Instant at = r.updatedAt() != null ? r.updatedAt() : r.createdAt();
                    if (at != null && !at.isBefore(from) && at.isBefore(until)) {
                        return true;
                    }
                } else if (r.stepIndex() >= fromStep && r.stepIndex() <= toStep) {
                    return true;
                }
            }
            return false;
        }
    }
}
