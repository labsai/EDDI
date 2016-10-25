package io.sls.serialization;

import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;
import java.io.StringWriter;

/**
 * @author ginccc
 */
public final class JSONSerialization {
    private static ObjectMapper objectMapper;

    private JSONSerialization() {
        //utility class
    }

    public static String serialize(Object model) throws IOException {
        return serialize(model, true);
    }

    public static String serialize(Object model, boolean prettyPrint) throws IOException {
        StringWriter writer = new StringWriter();
        ObjectMapper objectMapper = createObjectMapper(prettyPrint);
        objectMapper.writeValue(writer, model);

        return writer.toString();
    }

    public static <T> T deserialize(String json, TypeReference<T> typeReference) throws IOException {
        return getObjectMapper(false).reader(typeReference).readValue(json);
    }

    private static ObjectMapper createObjectMapper(boolean prettyPrint) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(SerializationConfig.Feature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, prettyPrint);
        objectMapper.configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper;
    }

    public static ObjectMapper getObjectMapper() {
        return getObjectMapper(false);
    }

    public static ObjectMapper getObjectMapper(boolean prettyPrint) {
        if (objectMapper == null) {
            objectMapper = createObjectMapper(prettyPrint);
        }

        return objectMapper;
    }
}
