/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;
import java.util.List;

/**
 * What one {@code HEALTH} alert rule last saw of one agent — so an alert fires
 * when something <em>changes</em> (a status entered, a burn or a trend
 * starting), not again on every evaluation while it lasts.
 *
 * @param status
 *            the status at the last evaluation
 * @param active
 *            the conditions that held then: {@code burn},
 *            {@code trend:<measure>}
 */
public record HealthAlertState(String ruleId, String agentId, String status, List<String> active, Instant evaluatedAt) {

    public HealthAlertState {
        active = active == null ? List.of() : List.copyOf(active);
    }
}
