/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The daily Agent Health housekeeping: deletes turn rows older than
 * {@code eddi.health.facts-retention} (90 days by default), daily rows and
 * change markers older than {@code eddi.health.daily-retention} (730 days), and
 * removes the rows of conversations that no longer exist (the orphan sweep).
 * <p>
 * <b>Why a plain {@code @Scheduled} sweep and not a
 * {@code ScheduleFireExecutor} system job</b> (the {@code alerts-maintenance}
 * pattern): the system-job path exists for work that must run on exactly one
 * node — a CAS claim, a fire log, retries. This sweep needs none of it. It is
 * one indexed {@code delete where day < cutoff}, idempotent (a second node
 * deleting the same rows deletes nothing), cheap, and harmless when missed (the
 * next day's sweep takes what was left; nothing reads past the retention
 * anyway). That is exactly the shape {@code ToolQuotaMaintenance} uses for the
 * same reason. The H2b rollups, which recompute and must not run twice, are the
 * ones that will take the system-job path.
 * <p>
 * <b>The orphan sweep</b> has the same shape, so it runs in the same job. A row
 * can land after its conversation was deleted — by a purge or by a user's
 * erasure, which deletes the user's conversations — when it waited on a node's
 * health queue; {@code HealthTurnRecorder} deletes such a row right after the
 * write, but when that check or that delete fails the row would otherwise stay
 * until the retention. So once a day this reads the distinct conversations of
 * rows written within {@code eddi.health.orphan-sweep-window} (48 hours by
 * default: such a write is recent by construction, and the slack covers a
 * missed day), asks the conversation store which of them still exist — one
 * projected batch read per {@value #BATCH} ids
 * ({@link IConversationMemoryStore#loadListingSummaries}) — and deletes the
 * rows of the ones that do not, counting them as
 * {@code eddi_health_dropped_total{reason="orphaned"}}. Only a definite "does
 * not exist" deletes: a batch the conversation store cannot answer keeps its
 * rows (a WARN; the next sweep asks again while the rows are inside the window,
 * and the retention takes them in the end). At most
 * {@code eddi.health.orphan-sweep-max} conversations per run, with a WARN when
 * there were more. Every node runs it; two nodes deleting the same orphan's
 * rows is one delete and one no-op.
 * <p>
 * Runs whether or not {@code eddi.health.enabled} is set: switching collection
 * off must not keep old rows forever.
 */
@ApplicationScoped
public class HealthTurnRetention {

    private static final Logger LOGGER = Logger.getLogger(HealthTurnRetention.class);

    /** Conversations checked per conversation-store read. */
    static final int BATCH = 500;

    private final IHealthTurnStore store;
    private final Instance<IConversationMemoryStore> conversations;
    private final IHealthDailyStore dailyStore;
    private final IHealthMarkerStore markerStore;
    private final HealthSettings settings;
    private final HealthMetrics metrics;
    private final Clock clock;

    /** Marks the days of orphaned rows for a re-roll; null when built by hand. */
    @Inject
    Instance<HealthDirtyDays> dirtyDays;

    /**
     * The session outcomes, which share the turn rows' retention and orphan sweep;
     * null when built by hand.
     */
    @Inject
    Instance<IHealthOutcomeStore> outcomeStore;

    private IHealthOutcomeStore outcomes() {
        return outcomeStore != null && outcomeStore.isResolvable() ? outcomeStore.get() : null;
    }

    @Inject
    public HealthTurnRetention(IHealthTurnStore store, Instance<IConversationMemoryStore> conversations, IHealthDailyStore dailyStore,
            IHealthMarkerStore markerStore, HealthSettings settings, HealthMetrics metrics) {
        this(store, conversations, dailyStore, markerStore, settings, metrics, Clock.systemUTC());
    }

    /** Turn rows and orphans only — for tests. */
    HealthTurnRetention(IHealthTurnStore store, Instance<IConversationMemoryStore> conversations, HealthSettings settings, HealthMetrics metrics,
            Clock clock) {
        this(store, conversations, null, null, settings, metrics, clock);
    }

    HealthTurnRetention(IHealthTurnStore store, Instance<IConversationMemoryStore> conversations, IHealthDailyStore dailyStore,
            IHealthMarkerStore markerStore, HealthSettings settings, HealthMetrics metrics, Clock clock) {
        this.store = store;
        this.conversations = conversations;
        this.dailyStore = dailyStore;
        this.markerStore = markerStore;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(every = "24h", delayed = "15m", identity = "health-turn-retention")
    void sweep() {
        deleteExpired();
        removeOrphans();
    }

    void deleteExpired() {
        LocalDate cutoff = cutoff();
        try {
            long deleted = store.deleteOlderThan(cutoff);
            if (deleted > 0) {
                LOGGER.infof("Deleted %d Agent Health turn row(s) from before %s", deleted, cutoff);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Failed to delete expired Agent Health turn rows; the next daily sweep retries");
        }
        IHealthOutcomeStore sessions = outcomes();
        if (sessions != null) {
            try {
                long deleted = sessions.deleteOlderThan(cutoff);
                if (deleted > 0) {
                    LOGGER.infof("Deleted %d Agent Health session outcome(s) that ended before %s", deleted, cutoff);
                }
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Failed to delete expired Agent Health session outcomes; the next daily sweep retries");
            }
        }
        LocalDate dailyCutoff = dailyCutoff();
        if (dailyStore != null) {
            try {
                long deleted = dailyStore.deleteOlderThan(dailyCutoff);
                if (deleted > 0) {
                    LOGGER.infof("Deleted %d Agent Health daily row(s) from before %s", deleted, dailyCutoff);
                }
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Failed to delete expired Agent Health daily rows; the next daily sweep retries");
            }
        }
        if (markerStore != null) {
            try {
                markerStore.deleteOlderThan(dailyCutoff.atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Failed to delete expired Agent Health change markers; the next daily sweep retries");
            }
        }
    }

    /** The first UTC day whose daily rows and markers are kept. */
    LocalDate dailyCutoff() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(settings.dailyRetentionDays());
    }

    /**
     * The orphan sweep (see the class comment).
     *
     * @return the rows removed
     */
    long removeOrphans() {
        if (conversations == null || !conversations.isResolvable()) {
            LOGGER.warn("Agent Health orphan sweep skipped: no conversation store to check against; rows of deleted "
                    + "conversations expire with eddi.health.facts-retention");
            return 0L;
        }
        Instant since = clock.instant().minus(settings.orphanSweepWindow());
        int max = settings.orphanSweepMax();
        // Conversation -> the agents of its recent rows, so an orphan whose days cannot
        // be read at delete time can still have its agents' days re-rolled.
        Map<String, Set<String>> agentsOf = new LinkedHashMap<>();
        List<String> candidates = new ArrayList<>();
        boolean overflowed = false;
        IHealthOutcomeStore sessions = outcomes();
        try {
            // One more than the cap, to tell "exactly the cap" from "more than it".
            // With session outcomes the cap is shared fairly: evaluated conversations
            // first fill up to half of it, the turn rows the rest, and evaluated ones
            // fill whatever the turn rows leave unused. The turn limit counts
            // (conversation, agent) pairs; nearly always one per conversation.
            Set<String> unique = new LinkedHashSet<>();
            List<String> evaluated = new ArrayList<>();
            int share = Math.max(1, max / 2);
            if (sessions != null) {
                // An evaluation that read a conversation's rows before its delete can
                // write its sessions after it.
                sessions.forEachConversationEvaluatedSince(since, (int) Math.min(Integer.MAX_VALUE, max + 1L), evaluated::add);
                unique.addAll(evaluated.subList(0, Math.min(evaluated.size(), share)));
            }
            int turnLimit = (int) Math.min(Integer.MAX_VALUE, max - unique.size() + 1L);
            int[] pairs = {0};
            store.forEachConversationAgentUpdatedSince(since, turnLimit, (conversationId, agentId) -> {
                pairs[0]++;
                Set<String> agents = agentsOf.computeIfAbsent(conversationId, k -> new LinkedHashSet<>());
                if (agentId != null) {
                    agents.add(agentId);
                }
            });
            overflowed = pairs[0] >= turnLimit;
            for (String conversationId : agentsOf.keySet()) {
                if (unique.size() > max) {
                    break;
                }
                unique.add(conversationId);
            }
            // Capacity the turn rows left unused goes to the remaining evaluated ones.
            for (int i = Math.min(evaluated.size(), share); i < evaluated.size() && unique.size() <= max; i++) {
                unique.add(evaluated.get(i));
            }
            overflowed |= evaluated.size() > max;
            candidates.addAll(unique);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read the conversations of recent Agent Health turn rows; the next daily sweep retries: %s",
                    sanitize(e.getMessage()));
            return 0L;
        }
        if (candidates.size() > max || overflowed) {
            LOGGER.warnf("The Agent Health orphan sweep checks %d conversations, not every conversation written since %s; the rest wait for "
                    + "the next daily sweep (raise eddi.health.orphan-sweep-max)", max, since);
            candidates = candidates.subList(0, Math.min(candidates.size(), max));
        }

        IConversationMemoryStore conversationStore = conversations.get();
        long removed = 0;
        int unanswered = 0;
        int[] counts = new int[2];
        for (int from = 0; from < candidates.size(); from += BATCH) {
            List<String> batch = candidates.subList(from, Math.min(candidates.size(), from + BATCH));
            Map<String, ConversationListingSummary> existing;
            try {
                existing = conversationStore.loadListingSummaries(batch);
            } catch (Exception e) {
                // Cannot tell: keep the rows. Never delete on uncertainty.
                unanswered += batch.size();
                continue;
            }
            if (existing == null) {
                unanswered += batch.size();
                continue;
            }
            for (String conversationId : batch) {
                if (existing.containsKey(conversationId)) {
                    continue;
                }
                // A conversation found only by its sessions has no recent turn rows, but
                // may have older ones a failed purge left: they are deleted here too.
                long turnRows = deleteOrphanTurns(conversationId, agentsOf.getOrDefault(conversationId, Set.of()), counts);
                if (turnRows < 0) {
                    // Turn rows kept: so are the sessions, the next sweep's way back to them.
                    continue;
                }
                removed += turnRows;
                if (sessions != null) {
                    // Session outcomes are not rolled up into daily rows: deleting them
                    // marks no day, and a failure here cannot skip the turn rows' marks.
                    try {
                        removed += sessions.deleteByConversationId(conversationId);
                    } catch (RuntimeException e) {
                        counts[FAILED_DELETES]++;
                    }
                }
            }
        }
        metrics.dropped(HealthMetrics.REASON_ORPHANED, removed);
        if (removed > 0) {
            LOGGER.infof("Removed %d Agent Health row(s) (turns and sessions) of conversations that no longer exist", removed);
        }
        int noAgent = counts[NO_AGENT];
        int failedDeletes = counts[FAILED_DELETES];
        if (noAgent > 0) {
            LOGGER.warnf("The Agent Health orphan sweep left %d orphaned conversation(s) whose rows name no agent and whose days could not "
                    + "be read: their daily rows could not be re-rolled, so the rows and sessions are kept for the next sweep", noAgent);
        }
        if (unanswered > 0 || failedDeletes > 0) {
            LOGGER.warnf("The Agent Health orphan sweep could not check %d conversation(s) and could not clean up %d; the next daily sweep "
                    + "retries while their rows are within eddi.health.orphan-sweep-window, after that they stay until "
                    + "eddi.health.facts-retention", unanswered, failedDeletes);
        }
        return removed;
    }

    private static final int NO_AGENT = 0;
    private static final int FAILED_DELETES = 1;

    /**
     * Deletes an orphan's turn rows and marks their days for re-roll at once. With
     * no agent known (the recent rows name none, or there are no recent rows — a
     * conversation found by its sessions) the rows are deleted only if their days
     * can be read; otherwise nothing could be re-rolled, so they are kept for the
     * next sweep (or the retention).
     *
     * @return the rows removed, or {@code -1} when they were kept or the delete
     *         failed
     */
    private long deleteOrphanTurns(String conversationId, Set<String> agents, int[] counts) {
        HealthDeletedRows deleted;
        try {
            deleted = agents.isEmpty() ? store.deleteConversationIfDaysKnown(conversationId) : store.deleteConversationReturningDays(conversationId);
        } catch (RuntimeException e) {
            counts[FAILED_DELETES]++;
            return -1L;
        }
        if (deleted == null && agents.isEmpty()) {
            counts[NO_AGENT]++;
            return -1L;
        }
        if (dirtyDays != null && dirtyDays.isResolvable()) {
            dirtyDays.get().markDeletedRows(conversationId, agents, deleted);
        }
        return deleted == null ? 0L : deleted.deleted();
    }

    /**
     * The first UTC day kept: today minus the retention in days. Rows of earlier
     * days are deleted.
     */
    LocalDate cutoff() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(settings.factsRetentionDays());
    }
}
