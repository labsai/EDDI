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
import ai.labs.eddi.engine.ratings.ConversationRatingWithdrawn;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The outcome evaluation — the first step of every {@code health-rollup} pass
 * ({@link HealthRollupService}), run under the same schedule claim, so one node
 * evaluates at a time and no second system job, claim or lease is needed. It
 * reads only turn rows and ratings, never conversation memory, and writes
 * {@link IHealthOutcomeStore} rows through {@link HealthSessionEvaluator}.
 * <p>
 * Per pass, in this order:
 * <ol>
 * <li><b>due sessions</b> — sessions whose window has closed since they were
 * judged ({@code dueAt}), and those a rating (saved or withdrawn) made due — at
 * most half of the pass's time, so they are never starved by a backfill;</li>
 * <li><b>the incremental feed</b> — conversations with turn rows captured since
 * the stored watermark <em>minus</em> {@code eddi.health.outcome-feed-overlap}
 * (15 minutes), up to one minute ago; paged by each conversation's newest
 * capture (then its id), oldest first, so a pass can stop anywhere and the next
 * resumes without a gap. Rows are ordered by their <em>capture</em> time, set
 * on the turn's node before the row waits on that node's health queue, so a row
 * can reach the store after the watermark passed its capture time; the overlap
 * re-reads every conversation of the last minutes each pass (the evaluation is
 * idempotent) and catches a row that late — the trade-off is that much
 * re-evaluation, bounded: the re-read of the overlap gets at most half of the
 * pass's time and conversations, then the feed goes on from the watermark, so a
 * busy overlap can never stop it from moving on. The first pass starts at the
 * turn-row retention, so turns recorded before outcomes existed are judged too,
 * a bounded batch per pass.</li>
 * </ol>
 * A conversation whose evaluation fails holds the watermark at its capture time
 * (never further back than the watermark), so the next passes retry it — for at
 * most {@link #MAX_RETRY} from its first failure, then it is left (a WARN; its
 * next change or due time evaluates it again). A failure is any store that
 * cannot be read — turn rows, outcomes, the agent's {@code healthConfig}, the
 * ratings — so no session is judged on what was merely unavailable.
 * <p>
 * Per conversation it reads the turn rows of a <em>range of steps</em>, oldest
 * first: from the latest stored session on (the feed), or one stored session's
 * own range up to the next stored session (a due session). Earlier sessions are
 * closed and keep their rows. Sessions are keyed by the step they genuinely
 * start at; a stored session whose first turns the retention removed keeps its
 * key. At most {@link #MAX_RANGE_TURNS} rows are read; when a range is longer,
 * its last (incomplete) session is left as stored. Sessions no longer found in
 * the range read (an undo removed a session's first turn) are deleted — never
 * one outside it. When every turn of the range was undone, the previous stored
 * session is judged again instead.
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
    /**
     * How long, from its first failure, a failed conversation holds the feed's
     * watermark back.
     */
    static final Duration MAX_RETRY = Duration.ofHours(1);
    /** Conversations read per page of the feed. */
    static final int FEED_PAGE = 500;
    /** Due sessions read per pass. */
    static final int DUE_PAGE = 1_000;
    /** The most conversations one pass evaluates. */
    static final int MAX_CONVERSATIONS = 5_000;
    /** The most turn rows read for one range of a conversation. */
    static final int MAX_RANGE_TURNS = 10_000;
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

    /**
     * Where rating events are handed off: the rating request must never wait for
     * Agent Health. One thread, a bounded queue; a full queue drops the mark (the
     * session is judged again with its next change).
     */
    private Executor ratingExecutor;
    private ExecutorService ownedExecutor;
    /**
     * When the feed first failed to evaluate a conversation, by this node's clock:
     * the retry limit runs from then, not from the turn's capture, so a backfilled
     * turn captured days ago is retried as long as a fresh one. Bounded and per
     * node — on another node the limit starts again, which only retries longer.
     */
    private final Cache<String, Instant> feedFailures = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(MAX_RETRY.multipliedBy(2))
            .build();
    /** {@link #MAX_RANGE_TURNS}, lowered by tests. */
    private int maxRangeTurns = MAX_RANGE_TURNS;

    @Inject
    public HealthOutcomeEvaluator(IHealthTurnStore turnStore, IHealthOutcomeStore outcomeStore, HealthConfigPolicy configPolicy,
            IConversationRatingStore ratingStore, HealthErasureFence fence, HealthSettings settings, HealthMetrics metrics) {
        this(turnStore, outcomeStore, configPolicy, ratingStore, fence, settings, metrics, Clock.systemUTC());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1_000), r -> {
            Thread thread = new Thread(r, "health-outcome-ratings");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardPolicy());
        this.ownedExecutor = executor;
        this.ratingExecutor = executor;
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
        this.ratingExecutor = Runnable::run;
        metrics.registerOutcomeLag(outcomeStore, clock);
    }

    /** For tests: where rating events are handed off. */
    void ratingExecutor(Executor executor) {
        this.ratingExecutor = executor;
    }

    /** For tests: the most turn rows read for one range. */
    void maxRangeTurns(int max) {
        this.maxRangeTurns = max;
    }

    @PreDestroy
    void stop() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    /** One pass, stopping at {@code deadline}. Never throws. */
    public Result run(Instant deadline) {
        int[] done = {0, 0, 0};
        Instant start = clock.instant();
        // Due sessions first, within half of the time: windows close and ratings are
        // re-checked even while a backfill fills the feed.
        Instant dueDeadline = start.plus(Duration.between(start, deadline).dividedBy(2));
        try {
            due(dueDeadline, done);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read the due Agent Health sessions; the next pass retries: %s", sanitize(e.getMessage()));
        }
        try {
            feed(deadline, done);
        } catch (RuntimeException e) {
            LOGGER.warnf("The Agent Health outcome feed failed; the next pass resumes from the same watermark: %s", sanitize(e.getMessage()));
        }
        metrics.outcomesEvaluated(done[1], done[2]);
        return new Result(done[0], done[1], done[2]);
    }

    private void feed(Instant deadline, int[] done) {
        Instant now = clock.instant();
        Instant until = now.minus(GRACE);
        Instant oldest = now.minus(settings.factsRetention());
        Instant watermark = outcomeStore.watermark().orElse(oldest);
        Instant since = watermark.minus(settings.outcomeFeedOverlap());
        if (since.isBefore(oldest)) {
            since = oldest;
        }
        // Re-reading the overlap gets at most half of what is left of the pass, so the
        // replay can never keep the feed from moving past the watermark.
        Instant overlapDeadline = now.plus(Duration.between(now, deadline).dividedBy(2));
        int overlapLimit = done[0] + Math.max(0, MAX_CONVERSATIONS - done[0]) / 2;
        String after = null;
        Set<String> seen = new HashSet<>();
        Instant failedAt = null;
        Instant reached = since;
        boolean complete = true;
        boolean read = false;
        while (since.isBefore(until)) {
            if (!clock.instant().isBefore(deadline) || done[0] >= MAX_CONVERSATIONS) {
                complete = false;
                break;
            }
            List<ConversationActivity> page = turnStore.conversationsUpdatedBetween(since, after, until, FEED_PAGE);
            read = true;
            Instant next = until;
            String nextAfter = null;
            boolean leftOverlap = false;
            for (ConversationActivity activity : page) {
                if (!clock.instant().isBefore(deadline) || done[0] >= MAX_CONVERSATIONS) {
                    // Resume at this conversation's newest capture next time.
                    next = activity.lastUpdatedAt();
                    complete = false;
                    break;
                }
                if (activity.lastUpdatedAt().isBefore(watermark) && (!clock.instant().isBefore(overlapDeadline) || done[0] >= overlapLimit)) {
                    // The overlap's share is used up: the rest of it is left, the feed
                    // goes on from the watermark.
                    next = watermark;
                    leftOverlap = true;
                    break;
                }
                if (seen.add(activity.conversationId())) {
                    failedAt = evaluateInFeed(activity, done, failedAt);
                }
            }
            if (complete && !leftOverlap && page.size() >= FEED_PAGE) {
                // Gapless: after the last entry's (capture, conversation) pair.
                ConversationActivity last = page.get(page.size() - 1);
                next = last.lastUpdatedAt();
                nextAfter = last.conversationId();
            }
            reached = next;
            since = next;
            after = nextAfter;
            if (!complete) {
                break;
            }
        }
        if (!read) {
            return;
        }
        Instant stored = reached.isAfter(watermark) ? reached : watermark;
        if (failedAt != null && failedAt.isBefore(stored)) {
            // Retried next pass, from here — never further back than the watermark: a
            // failure inside the overlap is in the next pass's overlap again.
            stored = failedAt.isBefore(watermark) ? watermark : failedAt;
        }
        outcomeStore.setWatermark(stored);
    }

    /**
     * Evaluates one conversation of the feed.
     *
     * @return the capture the watermark is held at for a retry — the earlier of
     *         {@code failedAt} and this conversation's, when it failed for less
     *         than {@link #MAX_RETRY} since its first failure
     */
    private Instant evaluateInFeed(ConversationActivity activity, int[] done, Instant failedAt) {
        String conversationId = activity.conversationId();
        if (evaluateSafely(conversationId, null, done)) {
            feedFailures.invalidate(conversationId);
            return failedAt;
        }
        Instant now = clock.instant();
        Instant first = feedFailures.get(conversationId, k -> now);
        if (Duration.between(first, now).compareTo(MAX_RETRY) >= 0) {
            feedFailures.invalidate(conversationId);
            LOGGER.warnf("Agent Health outcome evaluation kept failing for conversation %s (captured at %s) since %s; it is evaluated again "
                    + "with its next change or due time", sanitize(conversationId), activity.lastUpdatedAt(), first);
            return failedAt;
        }
        return failedAt == null || activity.lastUpdatedAt().isBefore(failedAt) ? activity.lastUpdatedAt() : failedAt;
    }

    private void due(Instant deadline, int[] done) {
        if (!clock.instant().isBefore(deadline)) {
            return;
        }
        Map<String, Set<Integer>> bySession = new LinkedHashMap<>();
        for (HealthOutcome due : outcomeStore.findDue(clock.instant(), DUE_PAGE)) {
            bySession.computeIfAbsent(due.conversationId(), k -> new TreeSet<>()).add(due.sessionNo());
        }
        for (Map.Entry<String, Set<Integer>> entry : bySession.entrySet()) {
            for (Integer sessionNo : entry.getValue()) {
                if (!clock.instant().isBefore(deadline) || done[0] >= MAX_CONVERSATIONS) {
                    return;
                }
                evaluateSafely(entry.getKey(), sessionNo, done);
            }
        }
    }

    /**
     * Evaluates one conversation from {@code fromSession} ({@code null}: from its
     * latest stored session on). Never throws.
     *
     * @return whether it succeeded
     */
    boolean evaluateSafely(String conversationId, Integer fromSession, int[] done) {
        try {
            int written = evaluate(conversationId, fromSession);
            done[0]++;
            done[1] += written;
            return true;
        } catch (RuntimeException e) {
            done[2]++;
            LOGGER.debugf("Could not evaluate the sessions of conversation %s: %s", sanitize(conversationId), sanitize(e.getMessage()));
            return false;
        }
    }

    /**
     * Judges one range of a conversation: one stored session's own steps (up to the
     * next stored session) when {@code fromSession} names it, else every step from
     * the latest stored session on.
     *
     * @return the session rows written
     */
    int evaluate(String conversationId, Integer fromSession) {
        Instant evaluatedAt = clock.instant();
        TreeMap<Integer, HealthOutcome> stored = new TreeMap<>();
        for (HealthOutcome o : outcomeStore.findByConversation(conversationId)) {
            stored.put(o.sessionNo(), o);
        }
        int from = fromSession != null ? fromSession : stored.isEmpty() ? 0 : stored.lastKey();
        Integer to = fromSession != null ? stored.higherKey(fromSession) : null;
        List<HealthTurn> rows = turnStore.findByConversationSteps(conversationId, from, to, maxRangeTurns + 1);
        // Every turn of the range undone: judge the previous session again, which may
        // now be the last one (an undo back into it).
        while (HealthSessionEvaluator.turnsOf(rows).isEmpty() && stored.lowerKey(from) != null) {
            outcomeStore.deleteSessions(conversationId, stored.subMap(from, true, to == null ? Integer.MAX_VALUE : to, false).keySet());
            from = stored.lowerKey(from);
            rows = turnStore.findByConversationSteps(conversationId, from, to, maxRangeTurns + 1);
        }
        boolean truncated = rows.size() > maxRangeTurns;
        if (truncated) {
            rows = rows.subList(0, maxRangeTurns);
        }
        Set<Integer> inRange = new HashSet<>(stored.subMap(from, true, to == null ? Integer.MAX_VALUE : to, false).keySet());
        List<HealthTurn> live = HealthSessionEvaluator.turnsOf(rows);
        if (live.isEmpty()) {
            // Nothing left of the conversation from here (an undo, an erasure, the
            // retention): neither are its sessions.
            outcomeStore.deleteSessions(conversationId, inRange);
            return 0;
        }
        HealthTurn newest = live.get(live.size() - 1);
        String userId = newest.userId();
        if (fence.covers(userId)) {
            return 0;
        }
        String origin = origin(stored.values(), from, live);
        String agentId = newest.agentId();
        Map<Integer, Resolved> configs = new HashMap<>();
        // A stored session whose first rows the retention removed (no row at all at
        // its first step, not even an undone one) keeps its key.
        int firstRead = rows.isEmpty() ? from : rows.get(0).stepIndex();
        // The first output as earlier evaluations saw it: every earlier session's, and
        // the re-read session's own when the step it names is no longer read (the
        // retention removed it) — a step that is read is judged from its row again.
        Integer knownFirstOutput = null;
        for (HealthOutcome o : stored.headMap(from, true).values()) {
            Integer step = o.firstOutputStep();
            if (step != null && (o.sessionNo() < from || step < firstRead) && (knownFirstOutput == null || step < knownFirstOutput)) {
                knownFirstOutput = step;
            }
        }
        Integer firstSessionNo = stored.containsKey(from) && firstRead > from ? Integer.valueOf(from) : null;
        List<HealthOutcome> sessions = new ArrayList<>(HealthSessionEvaluator.evaluate(new HealthSessionEvaluator.Input(conversationId, live,
                origin, version -> configPolicy.windowOf(origin, configOf(configs, agentId, version)), version -> configOf(configs, agentId, version),
                knownFirstOutput, firstSessionNo, new LazyRatings(conversationId), evaluatedAt)));
        int deleteBefore = to == null ? Integer.MAX_VALUE : to;
        if (truncated && !sessions.isEmpty()) {
            // The last session read may go on past the rows read: leave it as stored.
            HealthOutcome partial = sessions.remove(sessions.size() - 1);
            deleteBefore = partial.sessionNo();
        }
        outcomeStore.upsert(sessions);
        Set<Integer> stale = new HashSet<>();
        for (Integer sessionNo : inRange) {
            if (sessionNo < deleteBefore) {
                stale.add(sessionNo);
            }
        }
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

    private Resolved configOf(Map<Integer, Resolved> configs, String agentId, Integer version) {
        return configs.computeIfAbsent(version == null ? -1 : version, v -> configPolicy.require(agentId, version));
    }

    /**
     * The conversation's origin: its first turn row that is not {@code api} — the
     * stored one when an earlier session already found it, since the rows read now
     * may start later. Sessions stored before a later non-{@code api} row appeared
     * keep the origin they were judged with.
     */
    static String origin(Iterable<HealthOutcome> stored, int from, List<HealthTurn> live) {
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
            ConversationRating rating = saved.rating();
            // A changed conversation rating moves to the session of its new time; the
            // event does not say which session it was in before: mark them all, as a
            // withdrawal does.
            Instant at = rating.isConversationRating() && changed(rating) ? null : ratingTime(rating);
            handOff(() -> ratingChanged(rating.conversationId(), rating.stepIndex(), at));
        }
    }

    void onRatingWithdrawn(@Observes ConversationRatingWithdrawn withdrawn) {
        if (withdrawn != null) {
            // A withdrawn conversation rating has no time left to place it by.
            handOff(() -> ratingChanged(withdrawn.conversationId(), withdrawn.stepIndex(), null));
        }
    }

    private void handOff(Runnable task) {
        try {
            ratingExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LOGGER.debugf("Agent Health rating hand-off rejected: %s", e.getMessage());
        }
    }

    /** Whether the rating was saved before, and this save changed it. */
    private static boolean changed(ConversationRating rating) {
        return rating.createdAt() != null && rating.updatedAt() != null && rating.updatedAt().isAfter(rating.createdAt());
    }

    private static Instant ratingTime(ConversationRating rating) {
        return rating.updatedAt() != null ? rating.updatedAt() : rating.createdAt();
    }

    /**
     * A rating was saved or withdrawn: the session(s) it can change are judged
     * again at the next pass — a reply rating's session (by step), a conversation
     * rating's session (the last one started at or before its time; all of them
     * when withdrawn or changed, {@code at} {@code null}). Never throws.
     */
    void ratingChanged(String conversationId, int stepIndex, Instant at) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            Instant now = clock.instant();
            List<HealthOutcome> sessions = outcomeStore.findByConversation(conversationId);
            Integer target = null;
            if (stepIndex == ConversationRating.CONVERSATION_STEP_INDEX) {
                if (at == null) {
                    outcomeStore.markDue(conversationId, null, now);
                    return;
                }
                for (HealthOutcome s : sessions) {
                    if (target == null || s.startedAt() == null || !s.startedAt().isAfter(at)) {
                        target = s.sessionNo();
                    }
                }
            } else {
                for (HealthOutcome s : sessions) {
                    if (s.sessionNo() <= stepIndex) {
                        target = s.sessionNo();
                    }
                }
            }
            if (target != null) {
                outcomeStore.markDue(conversationId, List.of(target), now);
            }
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
                    // Unknown is not "not negative": fail the evaluation, so nothing is
                    // written and the session is retried rather than stored as silence.
                    throw new HealthStoreException("Could not read the ratings of a conversation", e);
                }
            }
            for (ConversationRating r : ratings) {
                if (r.normalizedScore() >= NEGATIVE_RATING_BELOW) {
                    continue;
                }
                if (r.isConversationRating()) {
                    Instant at = ratingTime(r);
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
