/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import java.util.List;

/**
 * The agents a rule watches: every agent ({@code allAgents}), or the listed
 * ones. A rule over every agent, or over more than one, is an administrator's
 * rule; an editor writes rules for one agent they hold {@code EDIT} on.
 */
public record AlertScope(boolean allAgents, List<String> agentIds) {

    public AlertScope {
        agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
    }

    public static AlertScope agent(String agentId) {
        return new AlertScope(false, List.of(agentId));
    }

    public boolean covers(String agentId) {
        return allAgents || (agentId != null && agentIds.contains(agentId));
    }

    /** The one agent of a single-agent scope, else {@code null}. */
    public String singleAgent() {
        return !allAgents && agentIds.size() == 1 ? agentIds.get(0) : null;
    }
}
