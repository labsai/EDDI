package ai.labs.behavior.impl;

import ai.labs.serialization.DeserializationException;

/**
 * @author ginccc
 */
public interface IBehaviorDeserialization {
    BehaviorSet deserialize(String json) throws DeserializationException;
}
