/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.schemafixture.model.SchemaFixtures;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.openapi.api.SmallRyeOpenAPI;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published schema must refuse exactly what the strict configuration
 * boundary refuses: closed where an unknown key is a 400, open where the server
 * genuinely accepts more.
 */
class StrictConfigurationSchemaFilterTest {

    /**
     * Shaped like SmallRye's output for {@link SchemaFixtures}: one component per
     * class, maps as {@code additionalProperties}, {@code Object} as an empty
     * schema. The {@code Nested1} decoy has the name a collision would produce.
     */
    private static final String DOCUMENT = """
            {
              "openapi": "3.1.0",
              "paths": {
                "/fixture/strict": {
                  "post": {
                    "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/StrictConfig"}}}},
                    "responses": {"204": {"description": "ok"}}
                  }
                },
                "/fixture/tolerant/{id}": {
                  "post": {
                    "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/TolerantConfig"}}}},
                    "responses": {"204": {"description": "ok"}}
                  }
                }
              },
              "components": {
                "schemas": {
                  "StrictConfig": {
                    "type": "object",
                    "properties": {
                      "name": {"type": "string"},
                      "nested": {"$ref": "#/components/schemas/Nested"},
                      "nestedList": {"type": "array", "items": {"$ref": "#/components/schemas/Nested"}},
                      "parameters": {"type": "object", "additionalProperties": {"type": "string"}},
                      "nestedByKey": {"type": "object", "additionalProperties": {"$ref": "#/components/schemas/Nested"}},
                      "tolerant": {"$ref": "#/components/schemas/Tolerant"},
                      "polymorphic": {"$ref": "#/components/schemas/Polymorphic"},
                      "anything": {},
                      "withIgnoredName": {"$ref": "#/components/schemas/WithIgnoredName"},
                      "withAlias": {"$ref": "#/components/schemas/WithAlias"},
                      "emitsUnlistedKey": {"$ref": "#/components/schemas/EmitsUnlistedKey"}
                    }
                  },
                  "Nested": {"type": "object", "properties": {"value": {"type": "string"}}},
                  "Nested1": {"type": "object", "properties": {"value": {"type": "string"}}},
                  "Tolerant": {"type": "object", "properties": {"value": {"type": "string"}}},
                  "Polymorphic": {"type": "object", "properties": {"type": {"type": "string"}}},
                  "WithIgnoredName": {"type": "object", "properties": {"value": {"type": "string"}}},
                  "WithAlias": {"type": "object", "properties": {"value": {"type": "string"}}},
                  "EmitsUnlistedKey": {"type": "object", "properties": {"value": {"type": "string"}}},
                  "TolerantConfig": {"type": "object", "properties": {"value": {"type": "string"}}}
                }
              }
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode schemas;

    @BeforeAll
    static void filterFixtureDocument() throws IOException {
        IndexView index = Index.of(SchemaFixtures.FixtureResource.class, SchemaFixtures.StrictConfig.class,
                SchemaFixtures.TolerantConfig.class);

        String json = SmallRyeOpenAPI.builder()
                .withCustomStaticFile(() -> new ByteArrayInputStream(DOCUMENT.getBytes(StandardCharsets.UTF_8)))
                .enableModelReader(false)
                .enableStandardStaticFiles(false)
                .enableAnnotationScan(false)
                .enableStandardFilter(false)
                .withFilters(List.of(new StrictConfigurationSchemaFilter(index)))
                .build()
                .toJSON();

        schemas = MAPPER.readTree(json).path("components").path("schemas");
    }

    @Test
    void strictConfigurationBodyIsClosed() {
        assertClosed("StrictConfig");
    }

    @Test
    void nestedObjectsReachedFromAStrictBodyAreClosed() {
        // Reached directly, through a list and as a map value.
        assertClosed("Nested");
    }

    @Test
    void openMapStaysOpen() {
        JsonNode parameters = schemas.path("StrictConfig").path("properties").path("parameters");
        assertEquals("string", parameters.path("additionalProperties").path("type").asText(),
                "a free-form map keeps its additionalProperties schema");
    }

    @Test
    void untypedPropertyStaysOpen() {
        assertFalse(schemas.path("StrictConfig").path("properties").path("anything").has("additionalProperties"));
    }

    @Test
    void typeThatIgnoresUnknownKeysStaysOpen() {
        assertOpen("Tolerant");
        assertOpen("TolerantConfig");
    }

    @Test
    void polymorphicBaseStaysOpen() {
        // Closing it would reject {"type":"text","text":"…"}, which the server accepts.
        assertOpen("Polymorphic");
    }

    @Test
    void inputOnlyNamesDoNotKeepASchemaOpen() {
        // The server still accepts `legacy` (ignored) and `oldValue` (an alias), but
        // the schema publishes the canonical shape: narrower than the server, never
        // wider.
        assertClosed("WithIgnoredName");
        assertClosed("WithAlias");
    }

    @Test
    void typeWritingAKeyItsSchemaOmitsStaysOpen() {
        // The component is the response schema too; closing it would reject the
        // server's own output.
        assertOpen("EmitsUnlistedKey");
    }

    @Test
    void schemaNotReachedFromAStrictBodyIsUntouched() {
        assertOpen("Nested1");
    }

    @Test
    void realIndexFindsTheConfigurationStoreBodies() throws IOException {
        Path indexFile = Path.of("target", "classes", "META-INF", "jandex.idx");
        assertTrue(Files.exists(indexFile), "jandex-maven-plugin writes the index in process-classes");
        IndexView index;
        try (InputStream in = Files.newInputStream(indexFile)) {
            index = new IndexReader(in).read();
        }

        var roots = new StrictConfigurationSchemaFilter(index).strictRoots();

        assertTrue(roots.contains(LlmConfiguration.class), roots::toString);
        assertTrue(roots.contains(RuleSetConfiguration.class), roots::toString);
        assertTrue(roots.contains(AgentConfiguration.class), roots::toString);
        assertTrue(roots.stream().allMatch(StrictConfigurationBodyInterceptor::isConfigurationModel), roots::toString);
    }

    private static void assertClosed(String name) {
        JsonNode additional = schemas.path(name).path("additionalProperties");
        assertTrue(additional.isBoolean() && !additional.asBoolean(), name + " should be closed: " + schemas.path(name));
    }

    private static void assertOpen(String name) {
        assertFalse(schemas.path(name).has("additionalProperties"), name + " should stay open: " + schemas.path(name));
    }
}
