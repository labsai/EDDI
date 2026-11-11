/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthProfile;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The agents' health profiles — MongoDB {@code healthprofiles}, PostgreSQL
 * {@code health_profiles}; one row per agent, keyed by its id. Unversioned and
 * not part of agent export or sync (plan §4.5). Every method throws
 * {@link HealthStoreException} when the store fails.
 */
public interface IHealthProfileStore {

    /** The agent's stored profile, or empty. */
    Optional<HealthProfile> read(String agentId);

    /** The stored profiles of the given agents; agents without one are absent. */
    Map<String, HealthProfile> readAll(Collection<String> agentIds);

    /**
     * Inserts or replaces the profile of {@code profile.agentId()} — with its
     * {@code updatedAt} and {@code updatedBy} as given.
     */
    void write(HealthProfile profile);

    /** Deletes the agent's profile; whether one was stored. */
    boolean delete(String agentId);

    /**
     * GDPR erasure: blanks {@code updatedBy} wherever it is {@code userId}.
     *
     * @return the profiles changed
     */
    long clearAuthor(String userId);
}
