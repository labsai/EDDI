/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class JsonSchemaCreator implements IJsonSchemaCreator {
    private final ObjectMapper objectMapper;

    /**
     * A class's JSON schema is a pure function of its (immutable) structure, so it
     * is computed once per class and kept.
     * <p>
     * Every call used to build a fresh JacksonModule, config builder and
     * SchemaGenerator and then reflect over the whole type graph —
     * {@code AgentConfiguration} alone has a dozen nested classes — and the Manager
     * hits {@code GET /{store}/jsonSchema} every time an editor opens, across
     * fifteen sibling endpoints.
     */
    private final Map<Class<?>, String> schemaCache = new ConcurrentHashMap<>();

    @Inject
    public JsonSchemaCreator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String generateSchema(Class<?> clazz) throws JsonProcessingException {
        String cached = schemaCache.get(clazz);
        if (cached != null) {
            return cached;
        }

        // Generated outside computeIfAbsent: reflecting over a large type graph while
        // holding a ConcurrentHashMap bin lock would block unrelated schema requests,
        // and a lost race only means the same schema was built twice.
        String schema = buildSchema(clazz);
        String raced = schemaCache.putIfAbsent(clazz, schema);
        return raced != null ? raced : schema;
    }

    private String buildSchema(Class<?> clazz) throws JsonProcessingException {
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
