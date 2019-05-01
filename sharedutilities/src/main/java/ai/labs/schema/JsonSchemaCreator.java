package ai.labs.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kjetland.jackson.jsonSchema.JsonSchemaConfig;
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator;

import javax.inject.Inject;

public class JsonSchemaCreator implements IJsonSchemaCreator {

    private final ObjectMapper objectMapper;

    @Inject
    public JsonSchemaCreator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode generateSchema(Class clazz) {
        JsonSchemaConfig config = JsonSchemaConfig.vanillaJsonSchemaDraft4();
        JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(objectMapper, config);

        // If using JsonSchema to generate HTML5 GUI:
        // JsonSchemaCreator html5 = new JsonSchemaCreator(objectMapper, JsonSchemaConfig.html5EnabledSchema() );

        return jsonSchemaGenerator.generateJsonSchema(clazz);
    }
}
