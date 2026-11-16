/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import java.util.List;
import java.util.Optional;

/**
 * Earned-autonomy state, one compare-and-set row per (agent, category). MongoDB
 * ({@code autonomy_state} collection) and PostgreSQL ({@code autonomy_state}
 * table) implementations, selected by {@code DataStoreProducers}.
 *
 * @since 6.7.0
 */
public interface IAutonomyStateStore {

    Optional<AutonomyState> read(String agentId, String category);

    /** The states of an agent, or of every agent when {@code agentId} is null. */
    List<AutonomyState> list(String agentId, int limit);

    /** The agent's states with an active suspension. */
    List<AutonomyState> listSuspended(String agentId);

    /** How many states have an active suspension (all agents). */
    long countSuspended();

    /**
     * Writes a state, compare-and-set: inserted when {@code expectedVersion} is 0
     * and no row exists, updated when the stored row is still at
     * {@code expectedVersion}. The stored version becomes
     * {@code expectedVersion + 1}.
     *
     * @return the stored state, or empty when another writer got there first
     */
    Optional<AutonomyState> save(AutonomyState state, long expectedVersion);
}
