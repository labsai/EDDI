package ai.labs.eddi.modules.templating.impl.dialects.json;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

import static ai.labs.eddi.datastore.serialization.SerializationCustomizer.configureObjectMapper;

public class JsonWrapper {
    private final JsonSerialization jsonSerialization;

    public JsonWrapper() {
        jsonSerialization = new JsonSerialization(configureObjectMapper(new ObjectMapper(), false));
    }

    public String serialize(Object obj) throws IOException {
        return jsonSerialization.serialize(obj);
    }

    public Object deserialize(String obj) throws IOException {
        return jsonSerialization.deserialize(obj);
    }
}
