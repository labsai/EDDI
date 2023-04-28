package ai.labs.eddi.configs.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kjetland.jackson.jsonSchema.JsonSchemaConfig;
import com.kjetland.jackson.jsonSchema.JsonSchemaDraft;
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedList;

@ApplicationScoped
public class JsonSchemaCreator implements IJsonSchemaCreator {
    private final ObjectMapper objectMapper;

    @Inject
    public JsonSchemaCreator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String generateSchema(Class<?> clazz) throws Exception {
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
                config.failOnUnknownProperties(),
                new LinkedList<Class<?>>().toArray(new Class[0]),
                JsonSchemaDraft.DRAFT_04);
        JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(objectMapper, eddiJsonSchemaConfig);

        var jsonSchema = jsonSchemaGenerator.generateJsonSchema(clazz);
        return objectMapper.writeValueAsString(jsonSchema);
    }
}
