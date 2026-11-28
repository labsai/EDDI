/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests of the OpenAPI-to-argument-schema conversion the MCP API tool
 * builder stores on each generated httpcall.
 */
class OpenApiArgumentSchemasTest {

    private static OpenAPI withComponents(Map<String, Schema> schemas) {
        return new OpenAPI().components(new Components().schemas(schemas));
    }

    private static Schema<?> ref(String name) {
        return new Schema<>().$ref("#/components/schemas/" + name);
    }

    @Test
    void parameterKeepsScalarTypesAndTurnsEverythingElseIntoAString() {
        Map<String, Object> integer = OpenApiArgumentSchemas.parameterSchema(new IntegerSchema().minimum(BigDecimal.ONE), null);
        assertEquals("integer", integer.get("type"));
        assertEquals(1L, integer.get("minimum"));

        assertEquals(Map.of("type", "string"), OpenApiArgumentSchemas.parameterSchema(new ArraySchema().items(new StringSchema()), null));
        assertEquals(Map.of("type", "string"), OpenApiArgumentSchemas.parameterSchema(new Schema<>(), null));
    }

    @Test
    void nullOrTooDeepIsUntyped() {
        assertTrue(OpenApiArgumentSchemas.bodySchema(null, null).isEmpty());
        assertTrue(OpenApiArgumentSchemas.convert(new StringSchema(), null, new HashSet<>(), 25).isEmpty());
    }

    @Test
    void componentReferencesAreResolvedAndRecursionStopsAtAnUntypedNode() {
        Schema<?> node = new ObjectSchema().addProperty("name", new StringSchema()).addProperty("next", ref("Node"));
        OpenAPI api = withComponents(Map.of("Node", node));
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(ref("Node"), api);
        Map<?, ?> properties = (Map<?, ?>) converted.get("properties");
        assertEquals(Map.of("type", "string"), properties.get("name"));
        assertEquals(Map.of(), properties.get("next"), "the recursive reference accepts anything");
    }

    @Test
    void unresolvableReferencesAreUntyped() {
        assertTrue(OpenApiArgumentSchemas.bodySchema(new Schema<>().$ref("#/definitions/Old"), withComponents(Map.of())).isEmpty());
        assertTrue(OpenApiArgumentSchemas.bodySchema(ref("X"), null).isEmpty());
        assertTrue(OpenApiArgumentSchemas.bodySchema(ref("X"), new OpenAPI()).isEmpty());
        assertTrue(OpenApiArgumentSchemas.bodySchema(ref("X"), new OpenAPI().components(new Components())).isEmpty());
        assertTrue(OpenApiArgumentSchemas.bodySchema(ref("Missing"), withComponents(Map.of("X", new StringSchema()))).isEmpty());
    }

    @Test
    void anyOfAndOneOfKeepOnlyTheDescription() {
        Schema<?> anyOf = new Schema<>().anyOf(List.of(new StringSchema(), new IntegerSchema())).description("  either  ");
        assertEquals(Map.of("description", "either"), OpenApiArgumentSchemas.bodySchema(anyOf, null));
        Schema<?> oneOf = new Schema<>().oneOf(List.of(new StringSchema()));
        assertTrue(OpenApiArgumentSchemas.bodySchema(oneOf, null).isEmpty());
        assertTrue(OpenApiArgumentSchemas.bodySchema(new Schema<>().description(" "), null).isEmpty(), "a blank description is dropped");
    }

    @Test
    void scalarKeywordsAreCarriedOver() {
        Schema<String> s = new StringSchema().minLength(1).maxLength(5).nullable(true).description("d");
        s.setEnum(Arrays.asList("a", null, "b"));
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(s, null);
        assertEquals("string", converted.get("type"));
        assertEquals(true, converted.get("nullable"));
        assertEquals(List.of("a", "b"), converted.get("enum"));
        assertEquals(1, converted.get("minLength"));
        assertEquals(5, converted.get("maxLength"));
        assertEquals("d", converted.get("description"));
    }

    @Test
    void anEnumOfOnlyNullsIsDropped() {
        Schema<String> s = new StringSchema();
        s.setEnum(new ArrayList<>(Arrays.asList((String) null)));
        assertFalse(OpenApiArgumentSchemas.bodySchema(s, null).containsKey("enum"));
    }

    @Test
    void openApi30ExclusiveFlagsMoveTheBound() {
        Schema<?> n = new Schema<>().type("number").minimum(new BigDecimal("0.5")).exclusiveMinimum(true)
                .maximum(BigDecimal.TEN).exclusiveMaximum(false);
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(n, null);
        assertEquals(0.5, converted.get("exclusiveMinimum"));
        assertFalse(converted.containsKey("minimum"));
        assertEquals(10L, converted.get("maximum"));
    }

    @Test
    void openApi31ExclusiveValuesAndNullTypes() {
        Schema<?> n = new Schema<>();
        n.setTypes(new HashSet<>(Set.of("integer", "null")));
        n.setExclusiveMinimumValue(BigDecimal.ZERO);
        n.setExclusiveMaximumValue(new BigDecimal("99.5"));
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(n, null);
        assertEquals("integer", converted.get("type"));
        assertEquals(true, converted.get("nullable"));
        assertEquals(0L, converted.get("exclusiveMinimum"));
        assertEquals(99.5, converted.get("exclusiveMaximum"));
    }

    @Test
    void typeOfReadsDeclaredListsAndShapes() {
        Schema<?> two = new Schema<>();
        two.setTypes(new HashSet<>(Set.of("string", "integer")));
        assertNull(OpenApiArgumentSchemas.typeOf(two), "two concrete types cannot be expressed");

        Schema<?> onlyNull = new Schema<>();
        onlyNull.setTypes(new HashSet<>(Set.of("null")));
        assertNull(OpenApiArgumentSchemas.typeOf(onlyNull));

        Schema<?> props = new Schema<>().addProperty("a", new StringSchema());
        assertEquals("object", OpenApiArgumentSchemas.typeOf(props));

        Schema<?> items = new Schema<>().items(new StringSchema());
        assertEquals("array", OpenApiArgumentSchemas.typeOf(items));
    }

    @Test
    void arraysCarryItemsAndSizeBounds() {
        Schema<?> a = new ArraySchema().items(new IntegerSchema()).minItems(1).maxItems(3);
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(a, null);
        assertEquals(1, converted.get("minItems"));
        assertEquals(3, converted.get("maxItems"));
        assertEquals("integer", ((Map<?, ?>) converted.get("items")).get("type"));
    }

    @Test
    void objectsDropReadOnlyPropertiesAndTheirRequiredNames() {
        StringSchema id = new StringSchema();
        id.setReadOnly(true);
        Schema<?> o = new ObjectSchema().addProperty("id", id).addProperty("name", new StringSchema());
        o.setRequired(List.of("id", "name"));
        o.setAdditionalProperties(false);
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(o, null);
        assertEquals(Set.of("name"), ((Map<?, ?>) converted.get("properties")).keySet());
        assertEquals(List.of("name"), converted.get("required"));
        assertEquals(false, converted.get("additionalProperties"));
    }

    @Test
    void requiredNamesThatAreAllReadOnlyAreDroppedAndASchemaValuedAdditionalIsTrue() {
        StringSchema id = new StringSchema();
        id.setReadOnly(true);
        Schema<?> o = new ObjectSchema().addProperty("id", id);
        o.setRequired(List.of("id"));
        o.setAdditionalProperties(new StringSchema());
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(o, null);
        assertFalse(converted.containsKey("required"));
        assertEquals(true, converted.get("additionalProperties"));
        assertTrue(((Map<?, ?>) converted.get("properties")).isEmpty());
    }

    @Test
    void anObjectWithoutPropertiesHasNoPropertiesKey() {
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(new ObjectSchema(), null);
        assertEquals(Map.of("type", "object"), converted);
    }

    @Test
    void allOfOfObjectsMergesPropertiesRequiredAndAdditional() {
        Schema<?> base = new ObjectSchema().addProperty("id", new IntegerSchema());
        base.setRequired(List.of("id"));
        Schema<?> extra = new ObjectSchema().addProperty("tag", new StringSchema());
        extra.setRequired(List.of("tag", "id"));
        extra.setAdditionalProperties(true);
        Schema<?> composed = new Schema<>().allOf(List.of(ref("Base"), extra)).description("merged")
                .addProperty("own", new StringSchema());
        composed.setRequired(List.of("own"));
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(composed, withComponents(Map.of("Base", base)));
        assertEquals("object", converted.get("type"));
        assertEquals("merged", converted.get("description"));
        assertEquals(List.of("id", "tag", "own"), new ArrayList<>(((Map<?, ?>) converted.get("properties")).keySet()));
        assertEquals(List.of("id", "tag", "own"), converted.get("required"));
        assertEquals(true, converted.get("additionalProperties"));
    }

    @Test
    void allOfWithoutRequiredOrAdditionalLeavesThemOut() {
        Schema<?> composed = new Schema<>().allOf(List.of(new ObjectSchema(), new ObjectSchema().addProperty("a", new StringSchema())));
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(composed, null);
        assertEquals(new LinkedHashMap<>(Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string")))), converted);
    }

    @Test
    void allOfWithANonObjectPartIsUntyped() {
        Schema<?> composed = new Schema<>().allOf(List.of(new ObjectSchema(), new StringSchema())).description("mixed");
        assertEquals(Map.of("description", "mixed"), OpenApiArgumentSchemas.bodySchema(composed, null));
    }

    @Test
    void nonDecimalEnumValuesAreKeptOrStringified() {
        Schema<Object> s = new Schema<>().type("string");
        s.setEnum(new ArrayList<>(List.of(true, 3, new StringBuilder("sb"))));
        assertEquals(List.of(true, 3, "sb"), OpenApiArgumentSchemas.bodySchema(s, null).get("enum"));
    }

    // --- review follow-ups ---

    @Test
    void onlyAnEmptyNotIsAFalseSchema() {
        assertTrue(OpenApiArgumentSchemas.isFalseSchema(new Schema<>().not(new Schema<>())));
        assertFalse(OpenApiArgumentSchemas.isFalseSchema(new Schema<>().not(new Schema<>().minimum(BigDecimal.ZERO))),
                "not: {minimum: 0} admits every negative number");
        assertFalse(OpenApiArgumentSchemas.isFalseSchema(new Schema<>().not(new Schema<>().maxLength(3))));
    }

    @Test
    void aConstrainedNotKeepsARequiredPropertySendable() {
        Schema<?> body = new ObjectSchema().addProperty("delta", new IntegerSchema().not(new Schema<>().minimum(BigDecimal.ZERO)));
        body.setRequired(List.of("delta"));

        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(body, null);

        assertTrue(((Map<?, ?>) converted.get("properties")).containsKey("delta"), converted.toString());
        assertEquals(List.of("delta"), converted.get("required"));
        assertFalse(Boolean.FALSE.equals(converted.get("additionalProperties")), converted.toString());
    }

    @Test
    void aForbiddenPropertyDoesNotMakeARequiredAdditionalKeyUnsendable() {
        // Through the parser, as a spec reaches the converter.
        String template = """
                {"openapi":"%s","info":{"title":"T","version":"1"},"paths":{},
                 "components":{"schemas":{"S":{"type":"object","required":["tenant"],
                   "properties":{"legacy":%s},"additionalProperties":{"type":"string"}}}}}
                """;
        for (String[] variant : new String[][]{{"3.0.3", "{\"not\":{}}"}, {"3.1.0", "false"}}) {
            OpenAPI openAPI = McpApiToolBuilder.parseSpec(template.formatted(variant[0], variant[1]), null);

            Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(openAPI.getComponents().getSchemas().get("S"), openAPI);

            Map<?, ?> properties = (Map<?, ?>) converted.get("properties");
            assertFalse(properties.containsKey("legacy"), variant[0] + ": " + converted);
            assertEquals(Map.of("type", "string"), properties.get("tenant"),
                    variant[0] + ": declared under the additionalProperties schema: " + converted);
            assertEquals(List.of("tenant"), converted.get("required"), variant[0] + ": " + converted);
            assertEquals(Boolean.FALSE, converted.get("additionalProperties"), variant[0] + ": " + converted);
        }
    }

    @Test
    void aPropertyOnePartOfAnAllOfForbidsStaysForbidden() {
        Schema<?> closing = new ObjectSchema().addProperty("name", new StringSchema()).addProperty("legacy", new Schema<>().not(new Schema<>()));
        Schema<?> open = new ObjectSchema().addProperty("legacy", new StringSchema()).addProperty("name", new StringSchema());
        open.setAdditionalProperties(true);
        Schema<?> composed = new Schema<>().allOf(List.of(closing, open));

        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(composed, null);

        Map<?, ?> properties = (Map<?, ?>) converted.get("properties");
        assertFalse(properties.containsKey("legacy"), "another part cannot reopen a forbidden name: " + converted);
        assertTrue(properties.containsKey("name"), converted.toString());
        assertEquals(Boolean.FALSE, converted.get("additionalProperties"), converted.toString());
    }

    @Test
    void aClosedAllOfPartDoesNotDropTheOtherPartsProperties() {
        Schema<?> base = new ObjectSchema().addProperty("id", new StringSchema());
        base.setRequired(List.of("id"));
        Schema<?> closed = new ObjectSchema().addProperty("note", new StringSchema());
        closed.setAdditionalProperties(false);
        Map<String, Object> converted = OpenApiArgumentSchemas.bodySchema(new Schema<>().allOf(List.of(ref("Base"), closed)),
                withComponents(Map.of("Base", base)));

        Map<?, ?> properties = (Map<?, ?>) converted.get("properties");
        assertTrue(properties.containsKey("id") && properties.containsKey("note"), converted.toString());
        assertEquals(List.of("id"), converted.get("required"));
    }

    @Test
    void aParsedEmptyNotIsAFalseSchemaUnder30And31() {
        for (String version : new String[]{"3.0.3", "3.1.0"}) {
            OpenAPI openAPI = McpApiToolBuilder.parseSpec("""
                    {"openapi":"%s","info":{"title":"T","version":"1"},"paths":{},
                     "components":{"schemas":{"S":{"type":"object","properties":{"gone":{"not":{}},"kept":{"type":"string"}}}}}}
                    """.formatted(version), null);
            Schema<?> s = openAPI.getComponents().getSchemas().get("S");
            assertTrue(OpenApiArgumentSchemas.isFalseSchema((Schema<?>) s.getProperties().get("gone")), version);
            assertFalse(OpenApiArgumentSchemas.isFalseSchema((Schema<?>) s.getProperties().get("kept")), version);
        }
    }
}
