/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.ratings.RatingScales;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CSV export escaping and score normalization")
class RatingCsvTest {

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+cmd", "-2+3", "@SUM(A1)", "\tx", "\rx"})
    @DisplayName("cells a spreadsheet would read as a formula get an apostrophe")
    void formulaInjection(String value) {
        String cell = RatingCsv.text(value);
        assertTrue(cell.startsWith("\"'"), cell);
    }

    @Test
    @DisplayName("quotes, commas and line breaks stay inside one quoted cell")
    void quoting() {
        assertEquals("\"a \"\"b\"\", c\nd\"", RatingCsv.text("a \"b\", c\nd"));
        assertEquals("", RatingCsv.text(null));
        assertEquals("\"plain\"", RatingCsv.text("plain"));
    }

    @Test
    @DisplayName("a row has one cell per header column; numbers EDDI writes are not escaped")
    void row() {
        Instant at = Instant.parse("2026-10-24T10:00:00Z");
        ConversationRating rating = new ConversationRating("id", "c", -1, "a", 2, "production", "api", "u", Scale.FIVE_STAR, 1, 0.0,
                List.of("Too slow", "Wrong answer"), "line1,\nline2", at, at);

        String row = RatingCsv.row(rating, true);
        String header = RatingCsv.header(true);

        assertTrue(row.contains(",-1,"), "the conversation step index stays a number: " + row);
        assertTrue(row.contains("\"Too slow|Wrong answer\""));
        assertTrue(row.endsWith("\"u\"\r\n"));
        assertEquals(header.split(",").length, RatingCsv.header(false).split(",").length + 1);
        assertFalse(RatingCsv.header(false).contains("userId"));
    }

    @Test
    @DisplayName("scores normalize onto 0..1 per scale, and invalid ones are refused")
    void normalization() {
        assertEquals(0.0, RatingScales.normalize(Scale.FIVE_STAR, 1));
        assertEquals(0.5, RatingScales.normalize(Scale.FIVE_STAR, 3));
        assertEquals(1.0, RatingScales.normalize(Scale.FIVE_STAR, 5));
        assertEquals(1.0, RatingScales.normalize(Scale.THUMBS, 1));
        assertEquals(0.0, RatingScales.normalize(Scale.THUMBS, 0));
        assertThrows(IllegalArgumentException.class, () -> RatingScales.normalize(Scale.THUMBS, 3));
        assertFalse(RatingScales.isValid(Scale.FIVE_STAR, null));
        assertFalse(RatingScales.isValid(null, 1));
        assertEquals("low", RatingScales.bucket(0.25));
        assertEquals("neutral", RatingScales.bucket(0.5));
        assertEquals("high", RatingScales.bucket(0.75));
    }
}
