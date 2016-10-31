package io.sls.core.behavior;

import io.sls.serialization.DeserializationException;

import java.io.IOException;

/**
 * @author ginccc
 */
public interface IBehaviorSerialization {
    BehaviorSet deserialize(String json) throws DeserializationException;

    String serialize(BehaviorSet set) throws IOException;
}
