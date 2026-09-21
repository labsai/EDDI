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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Blank is absent, not invalid. {@code isNullOrEmpty} only tests
     * {@code isEmpty()}, so a parameter left as " " used to reach the parser and be
     * reported as "not a valid integer" — a warning about a value nobody set.
     */
    @Nested
    @DisplayName("blank values")
    class BlankValues {

        @Test
        @DisplayName("whitespace-only is treated as absent, not as a parse failure")
        void whitespaceOnlyIsAbsent() {
            assertNull(intValue(params("maxTokens", "   "), "maxTokens"));
            assertNull(longValue(params("timeout", "\t"), "timeout"));
            assertNull(doubleValue(params("temperature", " \n "), "temperature"));
        }

        @Test
        @DisplayName("a padded but valid value still parses")
        void paddedValueStillParses() {
            assertEquals(8192, intValue(params("maxTokens", "  8192  "), "maxTokens"));
            assertEquals(0.3d, doubleValue(params("temperature", " 0.3 "), "temperature"));
        }
    }

    /**
     * The apply-form is what the builders actually call — one line per parameter
     * instead of a read plus a null check, which is where a transcription slip
     * would hide across the ~36 call sites.
     */
    @Nested
    @DisplayName("apply* — the form the builders use")
    class ApplyForms {

        @Test
        @DisplayName("hands a parsed value to the setter")
        void appliesParsedValues() {
            AtomicInteger intSeen = new AtomicInteger(-1);
            AtomicLong longSeen = new AtomicLong(-1);
            AtomicReference<Double> doubleSeen = new AtomicReference<>();

            ModelParameterValues.applyInt(params("maxTokens", "8192"), "maxTokens", intSeen::set);
            ModelParameterValues.applyLong(params("timeout", "60000"), "timeout", longSeen::set);
            ModelParameterValues.applyDouble(params("temperature", "0.3"), "temperature", doubleSeen::set);

            assertEquals(8192, intSeen.get());
            assertEquals(60000L, longSeen.get());
            assertEquals(0.3d, doubleSeen.get());
        }

        @Test
        @DisplayName("leaves the setter untouched for an unparseable value, so the model default stands")
        void skipsSetterForUnparseable() {
            AtomicInteger intSeen = new AtomicInteger(-1);
            AtomicLong longSeen = new AtomicLong(-1);
            AtomicReference<Double> doubleSeen = new AtomicReference<>();

            ModelParameterValues.applyInt(params("maxTokens", "16k"), "maxTokens", intSeen::set);
            ModelParameterValues.applyLong(params("timeout", "30s"), "timeout", longSeen::set);
            // A European decimal comma is the realistic typo here.
            ModelParameterValues.applyDouble(params("temperature", "0,3"), "temperature", doubleSeen::set);

            assertEquals(-1, intSeen.get(), "setter must not run");
            assertEquals(-1L, longSeen.get(), "setter must not run");
            assertNull(doubleSeen.get(), "setter must not run");
        }

        @Test
        @DisplayName("leaves the setter untouched for an absent key")
        void skipsSetterForMissingKey() {
            AtomicInteger seen = new AtomicInteger(-1);
            ModelParameterValues.applyInt(params("other", "5"), "maxTokens", seen::set);
            assertEquals(-1, seen.get());
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
