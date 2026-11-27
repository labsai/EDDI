/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthAlertState;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * The last state each {@code HEALTH} alert rule saw of each agent — MongoDB
 * {@code healthalertstate}, PostgreSQL {@code health_alert_state}, keyed by
 * (rule, agent). Holds statuses and condition names only. Every method throws
 * {@link HealthStoreException} when the store fails.
 */
public interface IHealthAlertStateStore {

    /** The states of one rule for the given agents, by agent id. */
    Map<String, HealthAlertState> read(String ruleId, Collection<String> agentIds);

    /** Inserts or replaces each state by its (rule, agent). */
    void write(Collection<HealthAlertState> states);

    /**
     * Deletes the states not evaluated since {@code before} — of rules deleted,
     * disabled or narrowed since.
     */
    long deleteEvaluatedBefore(Instant before);

    /** Deletes every state of an agent — on the agent's deletion. */
    long deleteByAgent(String agentId);
}
