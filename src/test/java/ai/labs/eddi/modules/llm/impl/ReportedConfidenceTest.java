/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ReportedConfidence")
class ReportedConfidenceTest {

    @Test
    @DisplayName("0..1 is the score, 1 is 1.0")
    void unitInterval() {
        assertEquals(0.0, ReportedConfidence.of(0.0), 1e-12);
        assertEquals(0.42, ReportedConfidence.of(0.42), 1e-12);
        assertEquals(1.0, ReportedConfidence.of(1.0), 1e-12);
    }

    @Test
    @DisplayName("a whole number 2..100 is a percentage")
    void wholePercentages() {
        assertEquals(0.02, ReportedConfidence.of(2.0), 1e-12);
        assertEquals(0.85, ReportedConfidence.of(85.0), 1e-12);
        assertEquals(1.0, ReportedConfidence.of(100.0), 1e-12);
    }

    @Test
    @DisplayName("a fraction above 1, a negative, above 100, NaN and infinity are unusable")
    void unusable() {
        for (double value : new double[]{1.2, 1.5, 4.9, 100.5, 101, 250, -0.1, -3, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertNull(ReportedConfidence.of(value), String.valueOf(value));
        }
    }

    @Test
    @DisplayName("text: a trailing percent sign, exponent forms and surrounding spaces; words are unusable")
    void text() {
        assertEquals(0.85, ReportedConfidence.of(" 85% "), 1e-12);
        assertEquals(0.5, ReportedConfidence.of("50 %"), 1e-12);
        assertEquals(0.85, ReportedConfidence.of("8.5e-1"), 1e-12);
        assertEquals(0.85, ReportedConfidence.of("85"), 1e-12);
        assertNull(ReportedConfidence.of("101%"));
        assertNull(ReportedConfidence.of("-5%"));
        assertNull(ReportedConfidence.of("high"));
        assertNull(ReportedConfidence.of((String) null));
    }

    @Test
    @DisplayName("JSON: numbers and numeric text; objects, booleans and null are unusable")
    void json() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals(0.9, ReportedConfidence.of(mapper.readTree("0.9")), 1e-12);
        assertEquals(0.9, ReportedConfidence.of(mapper.readTree("\"90%\"")), 1e-12);
        assertNull(ReportedConfidence.of(mapper.readTree("true")));
        assertNull(ReportedConfidence.of(mapper.readTree("{}")));
        assertNull(ReportedConfidence.of(mapper.readTree("null")));
        assertNull(ReportedConfidence.of((JsonNode) null));
    }

    @Test
    @DisplayName("the values real models answer with: 0.85, 85, 85%, \"0.85\", 0.850000001, 1, 100, a decimal comma — all usable")
    void realWorldAnswers() throws Exception {
        var mapper = new ObjectMapper();
        for (String json : new String[]{"0.85", "85", "\"85%\"", "\"0.85\"", "\"85\"", "\"0,85\"", "\" 0,85 \""}) {
            assertEquals(0.85, ReportedConfidence.of(mapper.readTree(json)), 1e-12, json);
        }
        assertEquals(0.850000001, ReportedConfidence.of(mapper.readTree("0.850000001")), 1e-12);
        assertEquals(1.0, ReportedConfidence.of(mapper.readTree("1")), 1e-12, "1 is certain, not 1 %");
        assertEquals(1.0, ReportedConfidence.of(mapper.readTree("100")), 1e-12);
        assertEquals(0.07, ReportedConfidence.of(mapper.readTree("7")), 1e-12, "a 1-10 answer reads as a percentage — documented, and counted");
        assertNull(ReportedConfidence.of("1,0,5"), "not a decimal comma");
        assertNull(ReportedConfidence.of("0,8.5"));
    }
}
