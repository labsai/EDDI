/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresTextTest {

    private static final String FFFD = "\uFFFD";

    @Test
    void text_replacesRawNulAndKeepsEverythingElse() {
        assertEquals("a" + FFFD + "b", PostgresText.text("a\u0000b"));
        assertEquals(FFFD + FFFD, PostgresText.text("\u0000\u0000"));
        assertEquals("caf\u00e9 \uD83D\uDE00 \\u0000 \t\n", PostgresText.text("caf\u00e9 \uD83D\uDE00 \\u0000 \t\n"));
    }

    @Test
    void text_returnsTheSameInstanceWhenNothingChanges() {
        String clean = "hello \uD83D\uDE00";
        assertSame(clean, PostgresText.text(clean));
        assertSame(null, PostgresText.text(null));
        assertEquals("", PostgresText.text(""));
    }

    @Test
    void text_replacesUnpairedSurrogatesButKeepsPairs() {
        assertEquals("x" + FFFD + "y", PostgresText.text("x\uD83Dy"));
        assertEquals("x" + FFFD + "y", PostgresText.text("x\uDE00y"));
        assertEquals("x" + FFFD, PostgresText.text("x\uD83D"));
        assertEquals("\uD83D\uDE00" + FFFD, PostgresText.text("\uD83D\uDE00\uD83D"));
    }

    @Test
    void text_leavesALiteralBackslashUSequenceAlone() {
        // six characters typed by a user, not a NUL
        assertEquals("\\u0000", PostgresText.text("\\u0000"));
    }

    @Test
    void json_replacesTheEscapedNulInsideNestedJson() {
        String json = "{\"a\":\"x\\u0000y\",\"b\":[{\"c\":\"\\u0000\"}],\"\\u0000k\":1}";
        assertEquals("{\"a\":\"x\\uFFFDy\",\"b\":[{\"c\":\"\\uFFFD\"}],\"\\uFFFDk\":1}", PostgresText.json(json));
    }

    @Test
    void json_replacesARawNulToo() {
        assertEquals("{\"a\":\"x" + FFFD + "y\"}", PostgresText.json("{\"a\":\"x\u0000y\"}"));
    }

    @Test
    void json_leavesAnEscapedBackslashBeforeU0000Alone() {
        // JSON text "\\u0000" is the value \u0000 (backslash, u, 0, 0, 0, 0): valid in
        // jsonb
        String json = "{\"a\":\"\\\\u0000\"}";
        assertSame(json, PostgresText.json(json));
    }

    @Test
    void json_countsBackslashesInPairs() {
        // \\\u0000 = escaped backslash followed by an escaped NUL: the NUL must go
        assertEquals("{\"a\":\"\\\\\\uFFFD\"}", PostgresText.json("{\"a\":\"\\\\\\u0000\"}"));
        // \\\\u0000 = two escaped backslashes then plain text u0000: untouched
        String untouched = "{\"a\":\"\\\\\\\\u0000\"}";
        assertSame(untouched, PostgresText.json(untouched));
    }

    @Test
    void json_handlesEscapedSurrogates() {
        // a valid escaped pair stays, an unpaired high or low escape does not
        String pair = "{\"a\":\"\\ud83d\\ude00\"}";
        assertSame(pair, PostgresText.json(pair));
        assertEquals("{\"a\":\"\\uFFFDx\"}", PostgresText.json("{\"a\":\"\\ud83dx\"}"));
        assertEquals("{\"a\":\"\\uFFFD\"}", PostgresText.json("{\"a\":\"\\ude00\"}"));
        assertEquals("{\"a\":\"\\uFFFD\\uFFFD\"}", PostgresText.json("{\"a\":\"\\ud83d\\ud83d\"}"));
    }

    @Test
    void json_replacesRawUnpairedSurrogatesAndKeepsPairs() {
        assertEquals("{\"a\":\"" + FFFD + "\"}", PostgresText.json("{\"a\":\"\uD83D\"}"));
        String pair = "{\"a\":\"\uD83D\uDE00\"}";
        assertSame(pair, PostgresText.json(pair));
    }

    @Test
    void json_typicalJacksonOutputIsTheSameInstance() throws Exception {
        // quotes, newlines, escaped control characters, an emoji (a surrogate pair):
        // nothing to
        // replace, so no copy of the document
        String json = new ObjectMapper()
                .writeValueAsString(Map.of("input", "he said \"hi\"\nline two \t \uD83D\uDE00", "k", List.of("\\u0000 stays", "caf\u00e9")));
        assertTrue(json.contains("\\\"") && json.contains("\\n"), json);
        assertSame(json, PostgresText.json(json));
        String escapedPair = "{\"a\":\"\\ud83d\\ude00 \\n \\\"\"}";
        assertSame(escapedPair, PostgresText.json(escapedPair));
    }

    @Test
    void json_aProblemLateInALongDocumentKeepsEverythingBeforeIt() {
        String prefix = "{\"a\":\"" + "x\\n\\\"y\uD83D\uDE00".repeat(1000);
        assertEquals(prefix + "\\uFFFD\"}", PostgresText.json(prefix + "\\u0000\"}"));
    }

    @Test
    void json_leavesOrdinaryEscapesAndUnicodeAlone() {
        String json = "{\"a\":\"line\\nbreak \\\"q\\\" \\u00e9 \\u0001 caf\u00e9\"}";
        assertSame(json, PostgresText.json(json));
        // a backslash at the very end or a short escape must not throw
        assertEquals("abc\\", PostgresText.json("abc\\"));
        assertEquals("\\u00", PostgresText.json("\\u00"));
    }
}
