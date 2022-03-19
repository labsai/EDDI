package ai.labs.eddi.modules.behavior.impl;

import ai.labs.eddi.datastore.serialization.DeserializationException;

/**
 * @author ginccc
 */
public interface IBehaviorDeserialization {
    BehaviorSet deserialize(String json) throws DeserializationException;
}
