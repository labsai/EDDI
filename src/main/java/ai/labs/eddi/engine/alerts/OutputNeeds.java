/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the enabled {@code OUTPUT_MATCH} rules of one agent read from a finished
 * turn — so the turn hook copies only that, and nothing at all for an agent
 * without such rules.
 *
 * @param any
 *            the agent has at least one enabled OUTPUT_MATCH rule
 * @param properties
 *            the property names the rules read; {@code null} means all (the
 *            conservative cache-miss snapshot)
 * @param output
 *            a rule reads {@code output.json.*}
 * @param actions
 *            a rule reads {@code actions}
 */
public record OutputNeeds(boolean any, Set<String> properties, boolean output, boolean actions) {

    public static final OutputNeeds NONE = new OutputNeeds(false, Set.of(), false, false);

    /** Not known yet (cache miss): copy everything a rule could read. */
    public static final OutputNeeds ALL = new OutputNeeds(true, null, true, true);

    public static OutputNeeds of(List<AlertRule> rules) {
        Set<String> properties = new HashSet<>();
        boolean any = false;
        boolean output = false;
        boolean actions = false;
        for (AlertRule rule : rules) {
            if (!rule.enabled() || rule.source() != AlertSource.OUTPUT_MATCH || rule.condition() == null || rule.condition().path() == null) {
                continue;
            }
            any = true;
            String path = rule.condition().path();
            if (OutputMatcher.ACTIONS.equals(path)) {
                actions = true;
            } else if (path.startsWith(OutputMatcher.OUTPUT_JSON_PREFIX)) {
                output = true;
            } else if (path.startsWith(OutputMatcher.PROPERTIES_PREFIX)) {
                properties.add(path.substring(OutputMatcher.PROPERTIES_PREFIX.length()).split("\\.")[0]);
            }
        }
        return any ? new OutputNeeds(true, Set.copyOf(properties), output, actions) : NONE;
    }
}
