package ai.labs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class SerializationUtilities {
    public static ObjectMapper configureObjectMapper(boolean prettyPrint, ObjectMapper objectMapper) {
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(SerializationFeature.INDENT_OUTPUT, prettyPrint);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return objectMapper;
    }

    public static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
        return configureObjectMapper(false, objectMapper);
    }
}
