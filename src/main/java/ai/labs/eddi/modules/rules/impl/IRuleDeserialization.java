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
    RuleSet deserialize(String json) throws DeserializationException;
}
