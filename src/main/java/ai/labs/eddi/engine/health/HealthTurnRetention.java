/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

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
        List<String> candidates = new ArrayList<>();
        try {
            // One more than the cap, to tell "exactly the cap" from "more than it".
            store.forEachConversationUpdatedSince(since, (int) Math.min(Integer.MAX_VALUE, max + 1L), candidates::add);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read the conversations of recent Agent Health turn rows; the next daily sweep retries: %s",
                    sanitize(e.getMessage()));
            return 0L;
        }
        if (candidates.size() > max) {
            LOGGER.warnf("The Agent Health orphan sweep checks %d conversations, not every conversation written since %s; the rest wait for "
                    + "the next daily sweep (raise eddi.health.orphan-sweep-max)", max, since);
            candidates = candidates.subList(0, max);
        }

        IConversationMemoryStore conversationStore = conversations.get();
        long removed = 0;
        int unanswered = 0;
        int failedDeletes = 0;
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
                try {
                    Map<String, Set<LocalDate>> days = store.agentDaysOfConversation(conversationId);
                    removed += store.deleteByConversationId(conversationId);
                    if (dirtyDays != null && dirtyDays.isResolvable()) {
                        dirtyDays.get().noteAll(days);
                    }
                } catch (RuntimeException e) {
                    failedDeletes++;
                }
            }
        }
        metrics.dropped(HealthMetrics.REASON_ORPHANED, removed);
        if (removed > 0) {
            LOGGER.infof("Removed %d Agent Health turn row(s) of conversations that no longer exist", removed);
        }
        if (unanswered > 0 || failedDeletes > 0) {
            LOGGER.warnf("The Agent Health orphan sweep could not check %d conversation(s) and could not clean up %d; the next daily sweep "
                    + "retries while their rows are within eddi.health.orphan-sweep-window, after that they stay until "
                    + "eddi.health.facts-retention", unanswered, failedDeletes);
        }
        return removed;
    }

    /**
     * The first UTC day kept: today minus the retention in days. Rows of earlier
     * days are deleted.
     */
    LocalDate cutoff() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(settings.factsRetentionDays());
    }
}
