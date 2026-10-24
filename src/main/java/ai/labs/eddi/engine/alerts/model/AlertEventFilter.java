/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import java.time.Instant;

/**
 * Filter of the alert event list. Every field is optional; {@code null} means
 * "any".
 *
 * @param agentId
 *            events about this agent (required of non-administrators by the
 *            REST layer, which checks EDIT on it)
 * @param acknowledged
 *            {@code false}: the inbox; {@code true}: acknowledged only
 * @param state
 *            the event state
 * @param from
 *            fired at or after
 * @param to
 *            fired before
 */
public record AlertEventFilter(String agentId, String ruleId, AlertSource source, Boolean acknowledged, AlertEvent.State state,
        String conversationId, Instant from, Instant to) {

    public static AlertEventFilter unacknowledged(String agentId) {
        return new AlertEventFilter(agentId, null, null, false, null, null, null, null);
    }

    public static AlertEventFilter all() {
        return new AlertEventFilter(null, null, null, null, null, null, null, null);
    }
}
