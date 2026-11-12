/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Marks the days whose daily rows a write made stale — the writer side of the
 * {@code healthdirty} queue.
 * <p>
 * <b>Debounced.</b> A busy agent writes many turn rows a second, all on the
 * same (agent, day); marking that entry on every write would make it a hot
 * document. So the turn worker only {@link #note notes} the pair here, and the
 * pairs are written to the store every few seconds ({@link #flush}), each once.
 * Nothing is lost by the delay: an entry is marked <em>after</em> the rows it
 * covers were written, and the rollup clears an entry only while it still
 * carries the token it read before reading the rows, so rows written while a
 * day is being rolled up always leave the day dirty for the next pass. A flush
 * that fails keeps its pairs for the next one.
 * <p>
 * <b>Atomic against the flush.</b> {@link #note} adds inside
 * {@code ConcurrentHashMap.compute}, and {@link #flush} takes an agent's set
 * out with {@code remove}: both run under the map's per-key lock, so a set the
 * flush took can no longer be added to, and a day noted meanwhile goes into a
 * fresh set for the next flush.
 * <p>
 * <b>Shutdown and crashes.</b> The recorder flushes after its worker drained
 * (CDI destroys beans in no defined order, so this bean's own pre-destroy flush
 * may run first). Pairs of <em>written</em> rows noted on a node that is killed
 * before its next flush — at most a few seconds of them — are recovered by the
 * rollup's self-heal pass, which re-marks the days of turn rows written since
 * its last pass ({@code HealthRollupService}). The self-heal cannot see a
 * <em>deleted</em> row, which is why deletions are marked at once
 * ({@link #markDeleted}), with one retry.
 * <p>
 * <b>The one remaining loss.</b> Only when both attempts of that write fail is
 * a deletion's mark held in memory for the next flush (with a WARN naming the
 * agent). If the node then stops before a flush succeeds, the mark is lost: the
 * daily rows of those days keep the deleted rows' counts until the day is
 * re-rolled for another reason (a later turn of the agent on that day, a
 * {@link #markRange}) or the row ages out at
 * {@code eddi.health.daily-retention}. When a delete cannot tell which days its
 * rows were on, every retained day of the conversation's agent is marked
 * instead ({@link #markDeletedRows}). A purge that knows neither the days nor
 * the agent does not delete, and leaves the rows to the orphan sweep, which
 * knows the agent. Days older than the turn-row retention cannot be recomputed
 * at all.
 */
@ApplicationScoped
public class HealthDirtyDays {

    private static final Logger LOGGER = Logger.getLogger(HealthDirtyDays.class);
    private static final long WARN_INTERVAL_MILLIS = 60_000;

    private final Instance<IHealthDirtyDayStore> store;
    private final HealthSettings settings;
    private final Clock clock;
    private final Map<String, Set<LocalDate>> pending = new ConcurrentHashMap<>();
    private final AtomicLong lastWarning = new AtomicLong();

    @Inject
    public HealthDirtyDays(Instance<IHealthDirtyDayStore> store, HealthSettings settings) {
        this(store, settings, Clock.systemUTC());
    }

    HealthDirtyDays(Instance<IHealthDirtyDayStore> store, HealthSettings settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    /** Notes the agent's days as dirty; written by the next {@link #flush}. */
    public void note(String agentId, Collection<LocalDate> days) {
        if (agentId == null || days == null || days.isEmpty()) {
            return;
        }
        pending.compute(agentId, (k, set) -> {
            Set<LocalDate> target = set == null ? new HashSet<>() : set;
            for (LocalDate day : days) {
                if (day != null) {
                    target.add(day);
                }
            }
            return target.isEmpty() ? null : target;
        });
    }

    /**
     * Marks the (agent, day) pairs of rows just deleted — by an erasure, a
     * conversation delete, the orphan sweep or the recorder's post-write cleanup —
     * dirty <b>at once</b>, in the store, so their daily rows are recomputed
     * without them. Not debounced: deletions are rare, and unlike a written row a
     * deleted one leaves nothing the rollup's self-heal could find again, so a mark
     * held in memory and lost with the node would leave the deleted counts in the
     * daily rows until their retention. A failed write is retried once; only when
     * that fails too are the pairs noted for the next flush, with a WARN (the
     * residual loss the class comment describes).
     */
    public void markDeleted(Map<String, Set<LocalDate>> agentDays) {
        markAtOnce(agentDays, "deleted Agent Health rows");
    }

    /**
     * Marks the days of a row written late — after the rollup's self-heal grace had
     * passed its capture time — dirty <b>at once</b>, like {@link #markDeleted}.
     * The self-heal finds rows by their capture time, and its watermark may already
     * be past this one's: a mark held in memory and lost with the node could then
     * not be found again.
     */
    public void markLateWrite(String agentId, Collection<LocalDate> days) {
        if (agentId == null || days == null) {
            return;
        }
        markAtOnce(Map.of(agentId, new HashSet<>(days)), "late-written Agent Health rows");
    }

    private void markAtOnce(Map<String, Set<LocalDate>> agentDays, String what) {
        if (agentDays == null) {
            return;
        }
        agentDays.forEach((agentId, days) -> {
            if (agentId == null || days == null || days.isEmpty()) {
                return;
            }
            RuntimeException failure = null;
            // One retry before falling back: the fallback lives in memory, so a crash
            // before the next successful flush would lose the mark.
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    store.get().mark(agentId, days);
                    return;
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            note(agentId, days);
            LOGGER.warnf("Could not mark %d day(s) of %s of agent %s dirty at once; they are queued for the next flush, and lost if "
                    + "this node stops before one succeeds: %s", days.size(), what, sanitize(agentId), sanitize(failure.getMessage()));
        });
    }

    /**
     * Marks the days of a conversation's deleted rows dirty: the days themselves
     * when the delete could answer them; otherwise — rare, the store could not be
     * read — every retained day of each agent the rows belonged to
     * ({@link #markRetained}), since a deleted row leaves nothing the self-heal
     * could find. With neither the days nor an agent nothing can be re-rolled: a
     * WARN, and the daily rows keep the counts until their own retention.
     */
    public void markDeletedRows(String conversationId, Collection<String> agentIds, HealthDeletedRows deleted) {
        markDeletedRows("conversation " + sanitize(conversationId), agentIds, deleted, true);
    }

    /**
     * As {@link #markDeletedRows(String, Collection, HealthDeletedRows)}, for the
     * rows of a GDPR erasure; the user is not named in the log.
     *
     * @return {@code false} when neither the days nor an agent were known, so
     *         nothing could be marked (logged as a WARN)
     */
    public boolean markErasedRows(Collection<String> agentIds, HealthDeletedRows deleted) {
        return markDeletedRows("an erased user", agentIds, deleted, false);
    }

    private boolean markDeletedRows(String whose, Collection<String> agentIds, HealthDeletedRows deleted, boolean conversation) {
        if (deleted != null && deleted.daysKnown()) {
            markDeleted(deleted.agentDays());
            return true;
        }
        List<String> agents = agentIds == null ? List.of() : agentIds.stream().filter(a -> a != null && !a.isBlank()).distinct().toList();
        if (agents.isEmpty()) {
            LOGGER.warnf("The days of the deleted Agent Health rows of %s are unknown and so %s: the daily rows keep the deleted counts "
                    + "until eddi.health.daily-retention", whose, conversation ? "is its agent" : "are the agents");
            return false;
        }
        LOGGER.warnf("The days of the deleted Agent Health rows of %s could not be read; re-rolling every retained day of agent(s) %s "
                + "instead", whose, sanitize(String.join(", ", agents)));
        agents.forEach(this::markRetained);
        return true;
    }

    /**
     * Marks every day of the agent within the turn-row retention dirty at once
     * (write-through like {@link #markDeleted}; queued for the next flush only if
     * the write fails).
     */
    public void markRetained(String agentId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate from = today.minusDays(settings.factsRetentionDays());
        Set<LocalDate> days = new HashSet<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            days.add(day);
        }
        markDeleted(Map.of(agentId, days));
    }

    /**
     * Marks every day from {@code from} to {@code to} (inclusive) of the agent
     * dirty at once — for a change that alters how stored turns are counted (an
     * outcome mapping, H3/H4). Days older than the turn-row retention are skipped:
     * their turns are gone, so they cannot be recomputed.
     *
     * @return how many days were marked
     */
    public int markRange(String agentId, LocalDate from, LocalDate to) {
        if (agentId == null || from == null || to == null || to.isBefore(from)) {
            return 0;
        }
        LocalDate oldest = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(settings.factsRetentionDays());
        LocalDate start = from.isBefore(oldest) ? oldest : from;
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(day);
        }
        if (!days.isEmpty()) {
            store.get().mark(agentId, days);
        }
        return days.size();
    }

    /** Pairs waiting for the next flush — for tests. */
    int pendingCount() {
        int count = 0;
        for (String agentId : List.copyOf(pending.keySet())) {
            Set<LocalDate> copy = new HashSet<>();
            pending.computeIfPresent(agentId, (k, set) -> {
                copy.addAll(set);
                return set;
            });
            count += copy.size();
        }
        return count;
    }

    /** Writes the noted pairs to the store. */
    @Scheduled(every = "5s", delayed = "5s", identity = "health-dirty-flush", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void flush() {
        for (String agentId : List.copyOf(pending.keySet())) {
            // Taken out under the key's lock: no note can add to this set any more.
            Set<LocalDate> set = pending.remove(agentId);
            if (set == null || set.isEmpty()) {
                continue;
            }
            List<LocalDate> days = new ArrayList<>(set);
            try {
                store.get().mark(agentId, days);
            } catch (RuntimeException e) {
                note(agentId, days);
                long now = System.currentTimeMillis();
                long previous = lastWarning.get();
                if (now - previous >= WARN_INTERVAL_MILLIS && lastWarning.compareAndSet(previous, now)) {
                    LOGGER.warnf("Could not mark Agent Health days dirty for agent %s (kept for the next attempt): %s", sanitize(agentId),
                            sanitize(e.getMessage()));
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        flush();
    }
}
