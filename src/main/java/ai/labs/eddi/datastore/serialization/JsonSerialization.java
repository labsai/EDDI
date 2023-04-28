package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.StringWriter;

/**
 * @author ginccc
 */
@ApplicationScoped
public final class JsonSerialization implements IJsonSerialization {
    private final ObjectMapper objectMapper;

    @Inject
    public JsonSerialization(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(Object model) throws IOException {
        StringWriter writer = new StringWriter();
        objectMapper.writeValue(writer, model);
        return writer.toString();
    }

    @Override
    public <T> T deserialize(String json, Class<T> type) throws IOException {
        return objectMapper.readerFor(type).readValue(json);
    }
}
