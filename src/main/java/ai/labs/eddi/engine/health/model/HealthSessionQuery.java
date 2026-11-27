/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * A page of the session drill-down: one agent's sessions that ended on the UTC
 * days {@code from} to {@code to}, newest first.
 *
 * @param versions
 *            the agent versions to include; never empty — the caller has
 *            already narrowed it to versions whose review is enabled
 * @param origins
 *            the origins to include; empty matches none
 * @param outcomes
 *            the outcomes to include; empty matches every one
 * @param evidence
 *            the evidence values to include; empty matches every one
 * @param offset
 *            sessions skipped (the cursor)
 */
public record HealthSessionQuery(String agentId, LocalDate from, LocalDate to, Set<Integer> versions, List<String> origins, List<String> outcomes,
        List<String> evidence, int offset, int limit) {

    public HealthSessionQuery {
        versions = versions == null ? Set.of() : Set.copyOf(versions);
        origins = origins == null ? List.of() : List.copyOf(origins);
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
