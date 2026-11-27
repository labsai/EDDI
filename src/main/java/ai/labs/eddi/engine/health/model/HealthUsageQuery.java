/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Which turn rows an adoption count reads: one agent, the UTC days {@code from}
 * to {@code to} (inclusive), optionally one version, and the given origins.
 *
 * @param version
 *            one agent version, or {@code null} for all
 * @param origins
 *            the origins to count; an empty list counts none
 */
public record HealthUsageQuery(String agentId, LocalDate from, LocalDate to, Integer version, List<String> origins) {

    public HealthUsageQuery {
        origins = origins == null ? List.of() : List.copyOf(origins);
    }
}
