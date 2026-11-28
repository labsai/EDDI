/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.datastore.serialization.DeserializationException;

/**
 * @author ginccc
 */
public interface IRuleDeserialization {

    /**
     * Reads a stored ruleset so that it can run. A retired condition config (see
     * {@code IRuleCondition#retiredConfiguration()}) is accepted with a WARN and
     * evaluates as it always did; every other invalid config is refused.
     */
    default RuleSet deserialize(String json) throws DeserializationException {
        return deserialize(json, null);
    }

    /**
     * As {@link #deserialize(String)}, naming {@code rulesetName} (a URI or other
     * label, may be {@code null}) in the WARN for a retired config.
     */
    RuleSet deserialize(String json, String rulesetName) throws DeserializationException;

    /**
     * Checks a ruleset an author is saving: everything {@link #deserialize} checks,
     * and a retired condition config is refused too, with the rule named, so that
     * no new ruleset is written with one.
     *
     * @throws IllegalArgumentException
     *             if a condition is invalid or carries a retired config
     */
    void validateForSave(String json) throws DeserializationException;
}
