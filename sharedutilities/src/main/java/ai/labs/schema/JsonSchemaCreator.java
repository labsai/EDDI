package ai.labs.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.SchemaGenerator;

import javax.inject.Inject;

public class JsonSchemaCreator implements IJsonSchemaCreator {
    private final SchemaGenerator schemaGenerator;

    @Inject
    public JsonSchemaCreator(SchemaGenerator schemaGenerator) {
        this.schemaGenerator = schemaGenerator;
    }

    @Override
    public JsonNode generateSchema(Class<?> clazz) {
        return schemaGenerator.generateSchema(clazz);
    }
}
