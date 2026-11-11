/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDirtyDay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The queue of (agent, day) pairs whose daily rows are stale — MongoDB
 * {@code healthdirty}, PostgreSQL {@code health_dirty}. Every writer of turn
 * rows marks the days it touched; the rollup job drains the queue.
 * <p>
 * One entry per (agent, day): marking an entry again only gives it a new
 * {@code token}. The rollup reads an entry, recomputes the day, and
 * {@link #clear clears} the entry only if its token is still the one it read —
 * a mark that arrived while the day was being recomputed (rows the rollup may
 * not have seen) keeps the day dirty for the next pass. Every method throws
 * {@link HealthStoreException} when the store fails.
 */
public interface IHealthDirtyDayStore {

    /**
     * Marks the agent's days dirty (an existing entry gets a fresh token, and its
     * failed attempts are reset).
     */
    void mark(String agentId, Collection<LocalDate> days);

    /** The entries marked longest ago, at most {@code limit}. */
    List<HealthDirtyDay> oldest(int limit);

    /**
     * Removes the entry if it still carries {@code entry.token()}.
     *
     * @return whether it was removed
     */
    boolean clear(HealthDirtyDay entry);

    /**
     * Moves an entry whose rollup failed to the back of the queue (its
     * {@code markedAt} becomes {@code now}) and counts the attempt — only while it
     * still carries {@code entry.token()}.
     *
     * @return the failed attempts now recorded, or 0 when the entry was marked
     *         again meanwhile (or is gone)
     */
    int defer(HealthDirtyDay entry, Instant now);

    /**
     * The earliest day of the agent that is waiting for the rollup and was marked
     * before {@code markedBefore} — a day marked more recently is merely waiting
     * for the next pass, not behind.
     */
    Optional<LocalDate> earliestPending(String agentId, Instant markedBefore);

    /** Where the rollup's self-heal pass stopped last, if it ever ran. */
    Optional<Instant> selfHealWatermark();

    /** Records where the self-heal pass stopped. */
    void setSelfHealWatermark(Instant at);

    /** How many entries are waiting. */
    long count();
}
