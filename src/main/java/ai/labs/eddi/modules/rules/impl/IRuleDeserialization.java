package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.datastore.serialization.DeserializationException;

/**
 * @author ginccc
 */
public interface IRuleDeserialization {
    RuleSet deserialize(String json) throws DeserializationException;
}
