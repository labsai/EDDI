/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.modules.llm.guardrails.ToolArgumentSchemaValidator.UnknownFields;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branches {@link ToolArgumentSchemaValidatorTest} leaves out: the
 * keyword-reduction rules of the effective schema, nullable type lists, the
 * violation cap, and the wording of each violation kind.
 */
class ToolArgumentSchemaValidatorBranchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    private static ObjectNode effective(String json, UnknownFields mode) throws Exception {
        return ToolArgumentSchemaValidator.effectiveSchema(MAPPER.readTree(json), mode, 0);
    }

    @Test
    void unknownFieldsPolicyParsesCaseInsensitivelyAndDefaultsToReject() {
        assertEquals(UnknownFields.REJECT, UnknownFields.of(null));
        assertEquals(UnknownFields.REJECT, UnknownFields.of(""));
        assertEquals(UnknownFields.REJECT, UnknownFields.of("nonsense"));
        assertEquals(UnknownFields.DROP, UnknownFields.of("  DROP "));
        assertEquals(UnknownFields.ALLOW, UnknownFields.of("Allow"));
    }

    @Test
    void resultTreatsNullViolationsAsNone() {
        var result = new ToolArgumentSchemaValidator.Result(null, null);
        assertTrue(result.valid());
        assertEquals(List.of(), result.violations());
    }

    @Test
    void nullableTextTypeBecomesATypeListButNullStaysAlone() throws Exception {
        ObjectNode nullableString = effective("{\"type\":\"string\",\"nullable\":true}", UnknownFields.REJECT);
        assertEquals("[\"string\",\"null\"]", nullableString.get("type").toString());

        ObjectNode nullType = effective("{\"type\":\"null\",\"nullable\":true}", UnknownFields.REJECT);
        assertEquals("null", nullType.get("type").asText());
    }

    @Test
    void typeListKeepsOnlyNamesAndAddsNullWhenNullable() throws Exception {
        ObjectNode list = effective("{\"type\":[\"string\",5,\"integer\"],\"nullable\":true}", UnknownFields.REJECT);
        assertEquals("[\"string\",\"integer\",\"null\"]", list.get("type").toString());

        ObjectNode notNullable = effective("{\"type\":[\"string\"]}", UnknownFields.REJECT);
        assertEquals("[\"string\"]", notNullable.get("type").toString());

        ObjectNode numericType = effective("{\"type\":7}", UnknownFields.REJECT);
        assertFalse(numericType.has("type"), "a type that is neither a name nor a list is dropped");
    }

    @Test
    void enumIsCopiedWithNullAddedWhenNullableAndAnEmptyEnumIsDropped() throws Exception {
        assertEquals("[\"a\",\"b\",null]", effective("{\"enum\":[\"a\",\"b\"],\"nullable\":true}", UnknownFields.REJECT).get("enum").toString());
        assertEquals("[1,2]", effective("{\"enum\":[1,2]}", UnknownFields.REJECT).get("enum").toString());
        assertFalse(effective("{\"enum\":[]}", UnknownFields.REJECT).has("enum"));
        assertFalse(effective("{\"enum\":\"a\"}", UnknownFields.REJECT).has("enum"));
    }

    @Test
    void boundKeywordsAreCopiedOnlyWhenNumeric() throws Exception {
        ObjectNode out = effective("{\"minLength\":2,\"maxLength\":\"9\",\"minimum\":1.5,\"maximum\":\"10\","
                + "\"exclusiveMinimum\":0,\"minItems\":1,\"maxItems\":3}", UnknownFields.REJECT);
        assertEquals(2, out.get("minLength").asInt());
        assertEquals(1, out.get("minItems").asInt());
        assertEquals(3, out.get("maxItems").asInt());
        assertEquals(1.5, out.get("minimum").asDouble());
        assertEquals(0, out.get("exclusiveMinimum").asInt());
        assertFalse(out.has("maxLength"), "a textual length is not a bound");
        assertFalse(out.has("maximum"), "a textual maximum is not a bound");
    }

    @Test
    void unsupportedKeywordsAreRemoved() throws Exception {
        ObjectNode out = effective("{\"type\":\"string\",\"pattern\":\"^a$\",\"format\":\"email\",\"$ref\":\"#/x\"}", UnknownFields.REJECT);
        assertEquals(1, out.size(), out.toString());
    }

    @Test
    void requiredKeepsOnlyNamesAndANonArrayIsIgnored() throws Exception {
        assertEquals("[\"a\"]", effective("{\"required\":[\"a\",3]}", UnknownFields.REJECT).get("required").toString());
        assertFalse(effective("{\"required\":\"a\"}", UnknownFields.REJECT).has("required"));
    }

    @Test
    void additionalPropertiesFollowsTheDeclarationThenThePolicy() throws Exception {
        String props = "\"properties\":{\"a\":{\"type\":\"string\"}}";
        assertFalse(effective("{" + props + "}", UnknownFields.REJECT).get("additionalProperties").asBoolean());
        assertFalse(effective("{" + props + "}", UnknownFields.ALLOW).has("additionalProperties"));
        assertFalse(effective("{" + props + "}", UnknownFields.DROP).has("additionalProperties"));
        assertTrue(effective("{" + props + ",\"additionalProperties\":{\"type\":\"string\"}}", UnknownFields.REJECT)
                .get("additionalProperties").asBoolean(), "a map-like object admits any key");
        assertTrue(effective("{" + props + ",\"additionalProperties\":true}", UnknownFields.REJECT).get("additionalProperties").asBoolean());
        assertFalse(effective("{\"type\":\"object\"}", UnknownFields.REJECT).has("additionalProperties"),
                "an object without declared properties is left open");
        assertFalse(effective("{\"properties\":[1]}", UnknownFields.REJECT).has("properties"));
    }

    @Test
    void itemsAreReducedRecursivelyButOnlyWhenAnObject() throws Exception {
        ObjectNode out = effective("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"pattern\":\"x\"}}", UnknownFields.REJECT);
        assertEquals("{\"type\":\"string\"}", out.get("items").toString());
        assertFalse(effective("{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}", UnknownFields.REJECT).has("items"));
    }

    @Test
    void nonObjectNodesAndTooDeepSchemasReduceToNothing() throws Exception {
        assertEquals(0, ToolArgumentSchemaValidator.effectiveSchema(MAPPER.readTree("\"string\""), UnknownFields.REJECT, 0).size());
        assertEquals(0, ToolArgumentSchemaValidator.effectiveSchema(MAPPER.readTree("{\"type\":\"string\"}"), UnknownFields.REJECT, 33).size());
        assertEquals(0, ToolArgumentSchemaValidator.effectiveSchema(null, UnknownFields.REJECT, 0).size());
    }

    @Test
    void blankArgumentsAreAnEmptyObject() throws Exception {
        var result = ToolArgumentSchemaValidator.check(schema("{\"type\":\"object\",\"required\":[\"a\"],\"properties\":{\"a\":{}}}"),
                null, "   ");
        assertEquals(0, result.arguments().size());
        assertEquals(List.of("$.a: required"), result.violations());
        assertTrue(ToolArgumentSchemaValidator.check(null, null, null).valid());
    }

    @Test
    void trailingTextAfterTheObjectIsNotJson() {
        var result = ToolArgumentSchemaValidator.check(null, null, "{\"a\":1} trailing");
        assertNull(result.arguments());
        assertEquals(List.of("$: the arguments are not valid JSON"), result.violations());
    }

    @Test
    void aTypeListThatAdmitsArrayDecodesAJsonStringArray() throws Exception {
        var schema = schema("{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":[\"array\",\"null\"],\"items\":{\"type\":\"integer\"}},"
                + "\"name\":{\"type\":\"string\"},\"free\":{}}}");
        var result = ToolArgumentSchemaValidator.check(schema, null, "{\"ids\":\"[1,2]\",\"name\":\"[1]\",\"free\":\"x\"}");
        assertTrue(result.valid(), result.violations().toString());
        assertTrue(result.arguments().get("ids").isArray());
        assertTrue(result.arguments().get("name").isTextual(), "a string parameter is never decoded");
    }

    @Test
    void aStringOfTheWrongContainerKindIsLeftAsTheString() throws Exception {
        var schema = schema("{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"object\"}}}");
        var result = ToolArgumentSchemaValidator.check(schema, null, "{\"body\":\"[1,2]\"}");
        assertTrue(result.arguments().get("body").isTextual());
        assertEquals(List.of("$.body: must be an object"), result.violations());
    }

    @Test
    void dropDescendsIntoArrayItemsAndNestedObjects() throws Exception {
        var schema = schema("{\"type\":\"object\",\"properties\":{\"rows\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
                + "\"properties\":{\"id\":{\"type\":\"integer\"}}}},\"meta\":{\"type\":\"object\"}}}");
        var result = ToolArgumentSchemaValidator.check(schema, "drop",
                "{\"rows\":[{\"id\":1,\"x\":2},{\"id\":3,\"y\":4}],\"meta\":{\"anything\":true},\"top\":1}");
        assertTrue(result.valid(), result.violations().toString());
        JsonNode rows = result.arguments().get("rows");
        assertEquals("{\"id\":1}", rows.get(0).toString());
        assertEquals("{\"id\":3}", rows.get(1).toString());
        assertFalse(result.arguments().has("top"));
        assertTrue(result.arguments().get("meta").has("anything"), "an object without declared properties keeps its fields");
    }

    @Test
    void moreThanTheCapOfViolationsIsSummarised() throws Exception {
        StringBuilder props = new StringBuilder();
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            props.append(i == 0 ? "" : ",").append("\"f").append(i).append("\":{\"type\":\"integer\"}");
            args.append(i == 0 ? "" : ",").append("\"f").append(i).append("\":\"x\"");
        }
        var result = ToolArgumentSchemaValidator.check(schema("{\"type\":\"object\",\"properties\":{" + props + "}}"), null, "{" + args + "}");
        assertEquals(ToolArgumentSchemaValidator.MAX_VIOLATIONS + 1, result.violations().size());
        assertEquals("(and 5 more)", result.violations().getLast());
    }

    @Test
    void anUnusualPropertyNameIsBracketedInThePath() throws Exception {
        var result = ToolArgumentSchemaValidator.check(schema("{\"type\":\"object\",\"required\":[\"x-id\"],\"properties\":{\"x-id\":{}}}"),
                null, "{}");
        assertEquals(List.of("$['x-id']: required"), result.violations());

        var unknown = ToolArgumentSchemaValidator.check(schema("{\"type\":\"object\",\"properties\":{}}"), null, "{\"a b\":1}");
        assertEquals(List.of("$['a b']: unknown field"), unknown.violations(), "no declared fields means no list");
    }

    @Test
    void nullableTypeIsWordedAsAlternatives() throws Exception {
        var result = ToolArgumentSchemaValidator.check(schema("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"string\",\"nullable\":true},"
                + "\"a\":{\"type\":\"array\"},\"o\":{\"type\":[\"null\"]}}}"), null, "{\"n\":1,\"a\":1,\"o\":1}");
        assertEquals(List.of("$.a: must be an array", "$.n: must be a string or null", "$.o: must be null"), result.violations());
    }

    @Test
    void nonStringEnumValuesAreQuotedAsJson() throws Exception {
        var result = ToolArgumentSchemaValidator.check(schema("{\"type\":\"object\",\"properties\":{\"level\":{\"enum\":[1,2,\"three\"]}}}"),
                null, "{\"level\":4}");
        assertEquals(List.of("$.level: must be one of 1, 2, three"), result.violations());
    }

    @Test
    void otherKeywordsUseTheValidatorsOwnMessage() throws Exception {
        var result = ToolArgumentSchemaValidator.check(
                schema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"maxLength\":2}}}"),
                null, "{\"name\":\"long\"}");
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().getFirst().contains("$.name"), result.violations().toString());
        assertTrue(result.violations().getFirst().contains("2"), result.violations().toString());
    }

    @Test
    void unknownFieldInsideAnArrayElementListsTheItemFields() throws Exception {
        var result = ToolArgumentSchemaValidator.check(schema("{\"type\":\"object\",\"properties\":{\"rows\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"label\":{\"type\":\"string\"}}}}}}"),
                null, "{\"rows\":[{\"id\":1,\"lable\":\"x\"}]}");
        assertEquals(List.of("$.rows[0].lable: unknown field (declared fields: id, label)"), result.violations());
    }

    @Test
    void theCompiledSchemaIsReusedForTheSameSchemaAndPolicy() throws Exception {
        var schema = schema("{\"type\":\"object\",\"properties\":{\"cached\":{\"type\":\"boolean\"}}}");
        var first = ToolArgumentSchemaValidator.check(schema, "reject", "{\"cached\":1}");
        var second = ToolArgumentSchemaValidator.check(schema, "reject", "{\"cached\":2}");
        assertEquals(first.violations(), second.violations());
        assertTrue(ToolArgumentSchemaValidator.check(schema, "allow", "{\"cached\":true,\"other\":1}").valid(),
                "the policy is part of the cache key");
    }
}
