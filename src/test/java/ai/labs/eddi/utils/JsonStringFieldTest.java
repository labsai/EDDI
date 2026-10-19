/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("JsonStringField — a linear scan for a JSON string member")
class JsonStringFieldTest {

    @Test
    @DisplayName("finds the raw value, escapes left in place, across whitespace")
    void findsTheRawValue() {
        assertEquals("say \\\"hi\\\"", JsonStringField.firstRawValue("{\"message\" :\n \"say \\\"hi\\\"\"}", "message"));
        assertEquals("", JsonStringField.firstRawValue("{\"message\":\"\"}", "message"));
        assertEquals("a\\\\", JsonStringField.firstRawValue("{\"message\":\"a\\\\\"}", "message"));
    }

    @Test
    @DisplayName("skips an occurrence that is not followed by a string, as the regex did")
    void skipsNonStringOccurrences() {
        assertEquals("second", JsonStringField.firstRawValue("{\"message\":42,\"inner\":{\"message\":\"second\"}}", "message"));
        assertEquals("v", JsonStringField.firstRawValue("{\"x\":\"message\",\"message\":\"v\"}", "message"));
    }

    @Test
    @DisplayName("no member, an unterminated string or null input yields null")
    void absent() {
        assertNull(JsonStringField.firstRawValue("{\"other\":\"v\"}", "message"));
        assertNull(JsonStringField.firstRawValue("{\"message\":\"never closed", "message"));
        assertNull(JsonStringField.firstRawValue("{\"message\":\"ends in a backslash\\", "message"));
        assertNull(JsonStringField.firstRawValue(null, "message"));
    }

    @Test
    @DisplayName("a 200 kB value is read without recursion")
    void longValue() {
        String value = "ab\\\"c".repeat(40_000);
        assertEquals(value, JsonStringField.firstRawValue("{\"message\":\"" + value + "\"}", "message"));
    }
}
