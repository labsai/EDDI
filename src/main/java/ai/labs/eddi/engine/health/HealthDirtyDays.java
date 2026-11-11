/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

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
 * that fails keeps its pairs for the next one; pairs noted on a node that dies
 * before its flush are the only loss (the day is re-rolled by the next write to
 * it).
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
        Set<LocalDate> set = pending.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet());
        for (LocalDate day : days) {
            if (day != null) {
                set.add(day);
            }
        }
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
        return pending.values().stream().mapToInt(Set::size).sum();
    }

    /** Writes the noted pairs to the store. */
    @Scheduled(every = "5s", delayed = "5s", identity = "health-dirty-flush", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void flush() {
        for (String agentId : List.copyOf(pending.keySet())) {
            Set<LocalDate> set = pending.remove(agentId);
            if (set == null || set.isEmpty()) {
                continue;
            }
            List<LocalDate> days = List.copyOf(set);
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
