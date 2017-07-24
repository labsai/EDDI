package ai.labs.core.behavior;

import ai.labs.serialization.DeserializationException;

import java.io.IOException;

/**
 * @author ginccc
 */
public interface IBehaviorSerialization {
    BehaviorSet deserialize(String json) throws DeserializationException;

    String serialize(BehaviorSet set) throws IOException;
}
