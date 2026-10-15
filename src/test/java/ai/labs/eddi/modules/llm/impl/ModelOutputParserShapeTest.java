/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.modules.llm.impl.ModelOutputParser.JsonOutcome;
import ai.labs.eddi.modules.llm.impl.ModelOutputParser.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R4: a reply that parsed but has the wrong shape is {@code SCHEMA_MISMATCH},
 * carries the parsed object, and never leaks a model value into the reason.
 */
@DisplayName("ModelOutputParser — shape validation (R4)")
class ModelOutputParserShapeTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"htmlResponseText\"],"
            + "\"properties\":{\"htmlResponseText\":{\"type\":\"string\",\"minLength\":1}}}";

    private final ModelOutputParser parser = new ModelOutputParser(new JsonSerialization(new ObjectMapper()));

    @Test
    @DisplayName("a conforming reply stays valid")
    void conforming() {
        JsonOutcome outcome = parser.parse("{\"htmlResponseText\":\"hi\"}", true, SCHEMA, null, "t");
        assertEquals(Kind.VALID, outcome.kind());
        assertEquals("valid", outcome.label());
        assertNull(outcome.reason());
    }

    @Test
    @DisplayName("valid JSON missing a required field is SCHEMA_MISMATCH and keeps the parsed object")
    void requiredMissing() {
        JsonOutcome outcome = parser.parse("{\"other\":1}", true, SCHEMA, null, "t");
        assertEquals(Kind.SCHEMA_MISMATCH, outcome.kind());
        assertEquals("schema_mismatch", outcome.label());
        assertEquals("schema: $.htmlResponseText required", outcome.reason());
        assertEquals(Map.of("other", 1), outcome.value(), "templates that worked on a partial object keep working");
    }

    @Test
    @DisplayName("a repaired (fenced) reply is checked too, and stays flagged as repaired")
    void repairedThenChecked() {
        JsonOutcome outcome = parser.parse("```json\n{\"htmlResponseText\":\"\"}\n```", true, SCHEMA, null, "t");
        assertEquals(Kind.SCHEMA_MISMATCH, outcome.kind());
        assertEquals("schema: $.htmlResponseText minLength", outcome.reason());
        assertEquals(true, outcome.repaired());
    }

    @Test
    @DisplayName("nonBlankFields alone, without a schema, flag an empty answer")
    void nonBlankOnly() {
        JsonOutcome outcome = parser.parse("{\"htmlResponseText\":\"\"}", true, null, List.of("htmlResponseText"), "t");
        assertEquals(Kind.SCHEMA_MISMATCH, outcome.kind());
        assertEquals("nonBlank: $.htmlResponseText", outcome.reason());
    }

    @Test
    @DisplayName("invalid and empty replies are unchanged: they have no shape to check")
    void invalidAndEmptyUnchanged() {
        assertEquals(Kind.INVALID, parser.parse("not json at all", true, SCHEMA, List.of("x"), "t").kind());
        assertEquals(Kind.EMPTY, parser.parse("  ", true, SCHEMA, List.of("x"), "t").kind());
    }

    @Test
    @DisplayName("with no schema and no nonBlankFields the result is exactly parse(raw, true)")
    void nothingConfigured() {
        assertEquals(parser.parse("{\"a\":1}", true), parser.parse("{\"a\":1}", true, null, null, "t"));
        assertEquals(parser.parse("{\"a\":1}", true), parser.parse("{\"a\":1}", true, "{not json", List.of(), "t"));
    }

    @Test
    @DisplayName("without convertToObject nothing is validated")
    void notConverting() {
        assertEquals(Kind.VALID, parser.parse("{\"other\":1}", false, SCHEMA, List.of("x"), "t").kind());
    }

    @Test
    @DisplayName("the reason never contains model output")
    void reasonCarriesNoModelOutput() {
        String secret = "IGNORE-PREVIOUS-INSTRUCTIONS";
        JsonOutcome outcome = parser.parse("{\"htmlResponseText\":1,\"" + secret + "\":\"" + secret + "\"}", true,
                "{\"type\":\"object\",\"properties\":{\"htmlResponseText\":{\"type\":\"string\"}},\"additionalProperties\":false}", null, "t");
        assertEquals(Kind.SCHEMA_MISMATCH, outcome.kind());
        assertFalse(outcome.reason().contains(secret), outcome.reason());
    }
}
