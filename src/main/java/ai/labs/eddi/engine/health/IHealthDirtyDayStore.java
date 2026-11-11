/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDirtyDay;

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

    /** Marks the agent's days dirty (an existing entry gets a fresh token). */
    void mark(String agentId, Collection<LocalDate> days);

    /** The entries marked longest ago, at most {@code limit}. */
    List<HealthDirtyDay> oldest(int limit);

    /**
     * Removes the entry if it still carries {@code entry.token()}.
     *
     * @return whether it was removed
     */
    boolean clear(HealthDirtyDay entry);

    /** The earliest day of the agent still waiting for the rollup. */
    Optional<LocalDate> earliestPending(String agentId);

    /** How many entries are waiting. */
    long count();
}
