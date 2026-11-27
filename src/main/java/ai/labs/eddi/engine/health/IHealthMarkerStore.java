/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthMarker;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Append-only change markers — MongoDB {@code healthmarkers}, PostgreSQL
 * {@code health_markers}. Deployment records carry no timestamps and are
 * overwritten (H0), so the changes worth showing on a chart are written here as
 * they happen. Kept as long as the daily rows. Every method throws
 * {@link HealthStoreException} when the store fails.
 */
public interface IHealthMarkerStore {

    /**
     * Stores a marker. A marker whose id is already stored is ignored — that is how
     * deployment-wide markers coalesce: their id names the kind, the tenant and the
     * hour.
     */
    void append(HealthMarker marker);

    /**
     * The agent's own markers between {@code from} (inclusive) and {@code to}
     * (exclusive), newest first, at most {@code limit}.
     */
    List<HealthMarker> findForAgent(String agentId, Instant from, Instant to, int limit);

    /**
     * The deployment-wide markers (no agent) of the given tenants in the same
     * range, newest first, at most {@code limit}.
     */
    List<HealthMarker> findGlobal(Collection<String> tenantIds, Instant from, Instant to, int limit);

    /** Deletes the markers before {@code before} (retention). */
    long deleteOlderThan(Instant before);
}
