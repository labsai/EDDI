/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthTurnAggregate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The Agent Health daily rows — MongoDB {@code healthdaily}, PostgreSQL
 * {@code health_daily}: one {@link HealthTurnAggregate} per ({@code agentId},
 * {@code agentVersion}, {@code origin}, {@code day}).
 * <p>
 * Derived data: every row is recomputed from the turn rows by the rollup and
 * written by {@link #replaceDay}, never incremented. They hold no personal data
 * and are kept longer than the turn rows ({@code eddi.health.daily-retention}).
 * Every method throws {@link HealthStoreException} when the store fails.
 */
public interface IHealthDailyStore {

    /**
     * Makes {@code rows} the daily rows of the agent's day: each is inserted or
     * replaced, then the agent-day's rows not among them are deleted — so a reader
     * never sees the day empty, and a second run with the same rows changes
     * nothing.
     */
    void replaceDay(String agentId, LocalDate day, List<HealthTurnAggregate> rows);

    /**
     * An agent's rows of the UTC days {@code from} to {@code to}, both inclusive.
     */
    List<HealthTurnAggregate> find(String agentId, LocalDate from, LocalDate to);

    /** The rows of several agents (the portfolio) over the days given. */
    List<HealthTurnAggregate> findForAgents(Collection<String> agentIds, LocalDate from, LocalDate to);

    /**
     * The first day each version of the agent has a row — what the
     * {@code VERSION_FIRST_TURN} markers are derived from.
     */
    Map<Integer, LocalDate> firstDayByVersion(String agentId);

    /** Deletes the rows of every day before {@code day} (retention). */
    long deleteOlderThan(LocalDate day);
}
