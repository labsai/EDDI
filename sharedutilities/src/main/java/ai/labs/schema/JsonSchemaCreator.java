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
    public JsonNode generateSchema(Class<?> clazz) {
        JsonSchemaConfig config = JsonSchemaConfig.vanillaJsonSchemaDraft4();
        JsonSchemaConfig eddiJsonSchemaConfig = new JsonSchemaConfig(
                config.autoGenerateTitleForProperties(),
                config.defaultArrayFormat(),
                config.useOneOfForOption(),
                config.useOneOfForNullables(),
                config.usePropertyOrdering(),
                config.hidePolymorphismTypeProperty(),
                true,
                config.useMinLengthForNotNull(),
                config.useTypeIdForDefinitionName(),
                config.customType2FormatMapping(),
                config.useMultipleEditorSelectViaProperty(),
                config.uniqueItemClasses(),
                config.classTypeReMapping(),
                config.jsonSuppliers(),
                config.subclassesResolver(),
                config.failOnUnknownProperties());
        JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(objectMapper, eddiJsonSchemaConfig);

        return jsonSchemaGenerator.generateJsonSchema(clazz);
    }
}
