/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The shape McpApiToolBuilder generates: a path id plus a whole-body variable.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema() throws Exception {
        return MAPPER.readValue("""
                {"type":"object","required":["teamId","requestBody"],"properties":{
                  "teamId":{"type":"integer"},
                  "requestBody":{"type":"object","required":["emails","role"],"properties":{
                    "emails":{"type":"array","items":{"type":"string"},"minItems":1},
                    "role":{"type":"string","enum":["manager","curator"]},
                    "note":{"type":"string","nullable":true},
                    "settings":{"type":"object","properties":{"notify":{"type":"boolean"}}}
                  }}
                }}""", Map.class);
    }

    private static ToolArgumentSchemaValidator.Result check(String mode, String args) throws Exception {
        return ToolArgumentSchemaValidator.check(schema(), mode, args);
    }

    @Test
    void validCallPasses() throws Exception {
        var result = check(null, "{\"teamId\":7,\"requestBody\":{\"emails\":[\"a@x\"],\"role\":\"manager\",\"note\":null}}");
        assertTrue(result.valid(), result.violations().toString());
    }

    @Test
    void missingRequiredFieldIsNamed() throws Exception {
        var result = check(null, "{\"teamId\":7,\"requestBody\":{\"role\":\"manager\"}}");
        assertEquals(List.of("$.requestBody.emails: required"), result.violations());
    }

    @Test
    void valueOutsideEnumListsTheAllowedValues() throws Exception {
        var result = check(null, "{\"teamId\":7,\"requestBody\":{\"emails\":[\"a@x\"],\"role\":\"admin\"}}");
        assertEquals(List.of("$.requestBody.role: must be one of manager, curator"), result.violations());
    }

    @Test
    void wrongTypeIsNamed() throws Exception {
        var result = check(null, "{\"teamId\":\"seven\",\"requestBody\":{\"emails\":[\"a@x\"],\"role\":\"manager\","
                + "\"settings\":{\"notify\":\"yes\"}}}");
        assertEquals(List.of("$.requestBody.settings.notify: must be a boolean", "$.teamId: must be an integer"), result.violations());
    }

    @Test
    void boundsAreEnforced() throws Exception {
        var result = check(null, "{\"teamId\":7,\"requestBody\":{\"emails\":[],\"role\":\"manager\"}}");
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().getFirst().startsWith("$.requestBody.emails"), result.violations().toString());
    }

    @Test
    void unknownFieldIsRejectedByDefaultAndTheDeclaredFieldsAreListed() throws Exception {
        var result = check(null, "{\"teamId\":7,\"requestBody\":{\"emials\":[\"a@x\"],\"role\":\"manager\"}}");
        assertTrue(result.violations().contains("$.requestBody.emails: required"), result.violations().toString());
        assertTrue(result.violations().contains("$.requestBody.emials: unknown field (declared fields: emails, role, note, settings)"),
                result.violations().toString());
    }

    @Test
    void unknownTopLevelArgumentIsRejected() throws Exception {
        var result = check("reject", "{\"teamId\":7,\"extra\":1,\"requestBody\":{\"emails\":[\"a@x\"],\"role\":\"manager\"}}");
        assertEquals(List.of("$.extra: unknown field (declared fields: teamId, requestBody)"), result.violations());
    }

    @Test
    void dropRemovesUnknownFieldsAndPasses() throws Exception {
        var result = check("drop", "{\"teamId\":7,\"requestBody\":{\"emails\":[\"a@x\"],\"role\":\"manager\",\"colour\":\"red\"}}");
        assertTrue(result.valid(), result.violations().toString());
        assertFalse(result.arguments().get("requestBody").has("colour"));
        assertTrue(result.arguments().get("requestBody").has("role"));
    }

    @Test
    void allowPassesUnknownFieldsThrough() throws Exception {
        var result = check("allow", "{\"teamId\":7,\"requestBody\":{\"emails\":[\"a@x\"],\"role\":\"manager\",\"colour\":\"red\"}}");
        assertTrue(result.valid(), result.violations().toString());
        assertEquals("red", result.arguments().get("requestBody").get("colour").asText());
    }

    @Test
    void explicitAdditionalPropertiesFalseWinsOverAllow() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> closed = MAPPER.readValue("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"additionalProperties\":false}", Map.class);
        var result = ToolArgumentSchemaValidator.check(closed, "allow", "{\"a\":\"x\",\"b\":1}");
        assertEquals(1, result.violations().size(), result.violations().toString());
    }

    @Test
    void aBodyEncodedAsAJsonStringIsDecodedAndValidated() throws Exception {
        var ok = check(null, "{\"teamId\":7,\"requestBody\":\"{\\\"emails\\\":[\\\"a@x\\\"],\\\"role\\\":\\\"curator\\\"}\"}");
        assertTrue(ok.valid(), ok.violations().toString());
        assertTrue(ok.arguments().get("requestBody").isObject());

        var bad = check(null, "{\"teamId\":7,\"requestBody\":\"{\\\"role\\\":\\\"boss\\\"}\"}");
        assertTrue(bad.violations().contains("$.requestBody.role: must be one of manager, curator"), bad.violations().toString());
    }

    @Test
    void anUnparseableBodyStringIsATypeViolation() throws Exception {
        var result = check(null, "{\"teamId\":7,\"requestBody\":\"not json\"}");
        assertEquals(List.of("$.requestBody: must be an object"), result.violations());
    }

    @Test
    void argumentsThatAreNotAnObjectAreRefused() throws Exception {
        var result = check(null, "[1,2]");
        assertNull(result.arguments());
        assertFalse(result.valid());
        assertFalse(check(null, "{broken").valid());
    }

    @Test
    void noSchemaMeansEveryCallIsValid() {
        assertTrue(ToolArgumentSchemaValidator.check(null, null, "{\"anything\":1}").valid());
        assertTrue(ToolArgumentSchemaValidator.check(Map.of(), "reject", "{\"anything\":1}").valid());
    }

    @Test
    void refsAndPatternsAreIgnoredNeverLoadedOrRun() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = MAPPER.readValue("{\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"$ref\":\"https://example.invalid/schema.json\"},"
                + "\"b\":{\"type\":\"string\",\"pattern\":\"^(a+)+$\"}}}", Map.class);
        var result = ToolArgumentSchemaValidator.check(schema, null, "{\"a\":{\"x\":1},\"b\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!\"}");
        assertTrue(result.valid(), result.violations().toString());
    }

    @Test
    void refusalIsANotExecutedEnvelopeListingEachViolation() throws Exception {
        String refusal = ToolArgumentSchemaValidator.refusal(List.of("$.emails: required", "$.role: must be one of manager, curator"));
        var node = MAPPER.readTree(refusal);
        assertEquals("NOT_EXECUTED", node.get("status").asText());
        assertEquals(2, node.get("violations").size());
        assertEquals("$.emails: required", node.get("violations").get(0).asText());
    }
}
