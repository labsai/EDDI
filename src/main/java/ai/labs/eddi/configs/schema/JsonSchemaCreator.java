/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class JsonSchemaCreator implements IJsonSchemaCreator {
    private final ObjectMapper objectMapper;

    @Inject
    public JsonSchemaCreator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String generateSchema(Class<?> clazz) throws Exception {
        JacksonModule jacksonModule = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_ORDER, JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE);

        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(objectMapper, SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON).with(jacksonModule).with(Option.DEFINITIONS_FOR_ALL_OBJECTS).with(Option.NULLABLE_FIELDS_BY_DEFAULT);

        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator generator = new SchemaGenerator(config);

        var jsonSchema = generator.generateSchema(clazz);
        return objectMapper.writeValueAsString(jsonSchema);
    }
}
