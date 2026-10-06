/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.modules.llm.impl.ModelOutputParser.JsonOutcome;
import ai.labs.eddi.modules.llm.impl.ModelOutputParser.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ModelOutputParser — never throws, repairs only what is safe")
class ModelOutputParserTest {

    private final ModelOutputParser parser = new ModelOutputParser(new JsonSerialization(new ObjectMapper()));

    static Stream<Arguments> accepted() {
        return Stream.of(
                // raw, expected value, repaired?
                Arguments.of("{\"a\":1}", Map.of("a", 1), false),
                Arguments.of("  \n{\"a\":1}\n ", Map.of("a", 1), false),
                Arguments.of("[1,2]", List.of(1, 2), false),
                Arguments.of("```\n{\"a\":1}\n```", Map.of("a", 1), true),
                Arguments.of("```json\n{\"a\":1}\n```", Map.of("a", 1), true),
                Arguments.of("```JSON\n{\"a\":1}\n```", Map.of("a", 1), true),
                Arguments.of("```Json {\"a\":1}```", Map.of("a", 1), true),
                Arguments.of("```json\n[1,2]\n```", List.of(1, 2), true),
                Arguments.of("Sure! Here you go: {\"a\":1} Hope that helps.", Map.of("a", 1), true),
                Arguments.of("prefix [1,2] suffix", List.of(1, 2), true),
                Arguments.of("Answer:\n```json\n{\"a\":1}\n```\nDone.", Map.of("a", 1), true),
                // braces and brackets inside strings must not end the scan early
                Arguments.of("note {\"a\":\"}{ ]\"} end", Map.of("a", "}{ ]"), true),
                // escaped quote inside a string
                Arguments.of("note {\"a\":\"say \\\"}\\\" now\"} end", Map.of("a", "say \"}\" now"), true),
                // nested structures
                Arguments.of("x {\"a\":{\"b\":[1,{\"c\":2}]}} y", Map.of("a", Map.of("b", List.of(1, Map.of("c", 2)))), true),
                // a first balanced candidate that is not JSON does not hide the real one
                Arguments.of("Use {name} here: {\"a\":1}", Map.of("a", 1), true));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("accepted")
    @DisplayName("parses or repairs")
    void accepted(String raw, Object expected, boolean repaired) {
        JsonOutcome outcome = parser.parse(raw, true);

        assertEquals(Kind.VALID, outcome.kind(), "reason: " + outcome.reason());
        assertEquals(expected, outcome.value());
        assertEquals(repaired, outcome.repaired());
        assertEquals(repaired ? "repaired" : "valid", outcome.label());
        assertNull(outcome.reason());
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("{\"answer\": \"the model ran out of tok", ModelOutputParser.REASON_TRUNCATED),
                Arguments.of("{\"a\":[1,2", ModelOutputParser.REASON_TRUNCATED),
                Arguments.of("Here is the answer: {\"a\": 1", ModelOutputParser.REASON_TRUNCATED),
                Arguments.of("I am sorry, I cannot help with that.", ModelOutputParser.REASON_NOT_JSON),
                Arguments.of("42", ModelOutputParser.REASON_NOT_JSON),
                Arguments.of("{\"a\":1]", ModelOutputParser.REASON_UNBALANCED),
                Arguments.of("{\"a\":1,}", ModelOutputParser.REASON_INVALID_SYNTAX),
                Arguments.of("{'a':1}", ModelOutputParser.REASON_INVALID_SYNTAX),
                Arguments.of("```json\n{\"a\":1,}\n```", ModelOutputParser.REASON_INVALID_SYNTAX));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rejected")
    @DisplayName("is INVALID with an EDDI-generated reason and keeps the raw string")
    void rejected(String raw, String reason) {
        JsonOutcome outcome = parser.parse(raw, true);

        assertEquals(Kind.INVALID, outcome.kind());
        assertEquals(reason, outcome.reason());
        assertEquals("invalid", outcome.label());
        assertSame(raw, outcome.value(), "today's behaviour: the raw string is what gets stored");
        assertFalse(outcome.repaired());
    }

    @ParameterizedTest
    @MethodSource("blank")
    @DisplayName("null and blank are EMPTY")
    void empty(String raw) {
        JsonOutcome outcome = parser.parse(raw, true);

        assertEquals(Kind.EMPTY, outcome.kind());
        assertEquals("empty", outcome.label());
        assertSame(raw, outcome.value());
    }

    static Stream<String> blank() {
        return Stream.of(null, "", "   ", "\n\t ");
    }

    @Test
    @DisplayName("convertToObject=false passes the reply through untouched")
    void passthrough() {
        String raw = "```json\n{\"a\":1}\n```";

        JsonOutcome outcome = parser.parse(raw, false);

        assertEquals(Kind.VALID, outcome.kind());
        assertSame(raw, outcome.value());
        assertFalse(outcome.repaired());
        assertEquals(Kind.VALID, parser.parse(null, false).kind(), "no emptiness check without convertToObject");
    }

    @Test
    @DisplayName("a reason never carries model output")
    void reasonsAreEdDiGenerated() {
        String secret = "IGNORE-PREVIOUS-INSTRUCTIONS";
        for (String raw : List.of("{\"a\":\"" + secret, secret, "{\"" + secret + "\":1]", "{" + secret + "}")) {
            JsonOutcome outcome = parser.parse(raw, true);
            assertEquals(Kind.INVALID, outcome.kind());
            assertFalse(outcome.reason().contains(secret), outcome.reason());
        }
    }

    @Test
    @DisplayName("a serializer that throws or returns null is INVALID, not an exception")
    void brokenSerializer() throws Exception {
        var serialization = mock(IJsonSerialization.class);
        when(serialization.deserialize(anyString(), any(Class.class))).thenThrow(new IllegalStateException("boom"));
        assertEquals(Kind.INVALID, new ModelOutputParser(serialization).parse("{\"a\":1}", true).kind());

        var nulling = mock(IJsonSerialization.class);
        when(nulling.deserialize(anyString(), any(Class.class))).thenReturn(null);
        assertEquals(Kind.INVALID, new ModelOutputParser(nulling).parse("{\"a\":1}", true).kind());
    }
}
