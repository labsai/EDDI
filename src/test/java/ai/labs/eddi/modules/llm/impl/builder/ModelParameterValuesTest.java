/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.doubleValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.longValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ModelParameterValues}.
 * <p>
 * These values are strings a user types into the Manager, so an unparseable one
 * is a configuration mistake. Parsing them directly threw an uncaught
 * {@link NumberFormatException} out of the model builders, which failed every
 * conversation the agent served; returning null lets each builder fall back to
 * the model default instead.
 */
class ModelParameterValuesTest {

    private static Map<String, String> params(String key, String value) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(key, value);
        return parameters;
    }

    @Nested
    @DisplayName("intValue")
    class IntValues {

        @Test
        @DisplayName("parses a plain integer")
        void parsesInteger() {
            assertEquals(16384, intValue(params("maxTokens", "16384"), "maxTokens"));
        }

        @Test
        @DisplayName("tolerates surrounding whitespace")
        void trimsWhitespace() {
            assertEquals(42, intValue(params("topK", "  42 "), "topK"));
        }

        @Test
        @DisplayName("parses a negative value")
        void parsesNegative() {
            assertEquals(-1, intValue(params("topK", "-1"), "topK"));
        }

        @Test
        @DisplayName("returns null for text, decimals and overflow rather than throwing")
        void returnsNullForUnparseable() {
            assertNull(intValue(params("maxTokens", "not-a-number"), "maxTokens"));
            assertNull(intValue(params("topK", "3.7"), "topK"));
            assertNull(intValue(params("maxTokens", "99999999999999999999"), "maxTokens"));
        }

        @Test
        @DisplayName("returns null for absent, empty and blank values")
        void returnsNullForMissing() {
            assertNull(intValue(params("maxTokens", "16384"), "somethingElse"));
            assertNull(intValue(params("maxTokens", ""), "maxTokens"));
            assertNull(intValue(params("maxTokens", null), "maxTokens"));
            assertNull(intValue(null, "maxTokens"));
        }
    }

    @Nested
    @DisplayName("longValue")
    class LongValues {

        @Test
        @DisplayName("parses a value beyond the int range")
        void parsesLong() {
            assertEquals(3_000_000_000L, longValue(params("timeout", "3000000000"), "timeout"));
        }

        @Test
        @DisplayName("returns null for text rather than throwing")
        void returnsNullForUnparseable() {
            assertNull(longValue(params("timeout", "soon"), "timeout"));
        }

        @Test
        @DisplayName("returns null for absent and blank values")
        void returnsNullForMissing() {
            assertNull(longValue(params("timeout", "  "), "timeout"));
            assertNull(longValue(params("timeout", "1000"), "other"));
        }
    }

    @Nested
    @DisplayName("doubleValue")
    class DoubleValues {

        @Test
        @DisplayName("parses a decimal")
        void parsesDouble() {
            assertEquals(0.3d, doubleValue(params("temperature", "0.3"), "temperature"));
        }

        @Test
        @DisplayName("accepts an integer-shaped decimal")
        void parsesIntegerShaped() {
            assertEquals(1.0d, doubleValue(params("topP", "1"), "topP"));
        }

        @Test
        @DisplayName("returns null for text rather than throwing")
        void returnsNullForUnparseable() {
            assertNull(doubleValue(params("temperature", "warm"), "temperature"));
        }

        @Test
        @DisplayName("returns null for absent and blank values")
        void returnsNullForMissing() {
            assertNull(doubleValue(params("topP", ""), "topP"));
            assertNull(doubleValue(params("topP", "0.9"), "missing"));
        }
    }
}
