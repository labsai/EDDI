/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthMarker;

import java.time.Instant;
import java.util.List;

/**
 * Append-only change markers — MongoDB {@code healthmarkers}, PostgreSQL
 * {@code health_markers}. Deployment records carry no timestamps and are
 * overwritten (H0), so the changes worth showing on a chart are written here as
 * they happen. Kept as long as the daily rows. Every method throws
 * {@link HealthStoreException} when the store fails.
 */
public interface IHealthMarkerStore {

    /** Stores a marker. */
    void append(HealthMarker marker);

    /**
     * The markers of the agent and the deployment-wide ones (no agent) between
     * {@code from} (inclusive) and {@code to} (exclusive), oldest first, at most
     * {@code limit}.
     */
    List<HealthMarker> find(String agentId, Instant from, Instant to, int limit);

    /** Deletes the markers before {@code before} (retention). */
    long deleteOlderThan(Instant before);
}
