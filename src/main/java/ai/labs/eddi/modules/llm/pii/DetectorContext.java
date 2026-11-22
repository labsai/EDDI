/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import ai.labs.eddi.utils.RegexDeadline;

import java.util.Map;
import java.util.Set;

/**
 * What a {@link PiiDetector} is asked for on one call.
 *
 * @param entities
 *            the entity types wanted; empty means every type the detector
 *            supports
 * @param parameters
 *            the detector's configuration (plain strings, never templates)
 * @param deadlineNanos
 *            an absolute {@link System#nanoTime()} instant the detector must
 *            finish by
 */
public record DetectorContext(Set<String> entities, Map<String, String> parameters, long deadlineNanos) {

    public DetectorContext {
        entities = entities == null ? Set.of() : Set.copyOf(entities);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /** A context for {@code entities} with {@code budgetMs} from now. */
    public static DetectorContext of(Set<String> entities, long budgetMs) {
        return new DetectorContext(entities, Map.of(), RegexDeadline.deadlineIn(budgetMs));
    }

    /** Whether {@code entity} is wanted. */
    public boolean wants(String entity) {
        return entities.isEmpty() || entities.contains(entity);
    }
}
