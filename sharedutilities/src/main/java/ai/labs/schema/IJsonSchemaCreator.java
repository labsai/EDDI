package ai.labs.schema;

import com.fasterxml.jackson.databind.JsonNode;

public interface IJsonSchemaCreator {
    JsonNode generateSchema(Class<?> clazz);
}
