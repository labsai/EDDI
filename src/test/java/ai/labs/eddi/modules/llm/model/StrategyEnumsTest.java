/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the lenient config-boundary contract of the cascade strategy enums:
 * known tokens resolve case-insensitively and trimmed, unknown/null resolve to
 * {@code null} (so callers can warn + default), and the canonical
 * {@code configValue()} round-trips the lowercase wire token.
 */
class StrategyEnumsTest {

    @Nested
    @DisplayName("EvaluationStrategy")
    class Evaluation {

        @Test
        @DisplayName("configValue() is the lowercase wire token")
        void configValues() {
            assertEquals("structured_output", EvaluationStrategy.STRUCTURED_OUTPUT.configValue());
            assertEquals("heuristic", EvaluationStrategy.HEURISTIC.configValue());
            assertEquals("judge_model", EvaluationStrategy.JUDGE_MODEL.configValue());
            assertEquals("none", EvaluationStrategy.NONE.configValue());
        }

        @Test
        @DisplayName("fromConfig resolves known tokens, case-insensitively and trimmed")
        void fromConfigKnown() {
            assertEquals(EvaluationStrategy.HEURISTIC, EvaluationStrategy.fromConfig("heuristic"));
            assertEquals(EvaluationStrategy.JUDGE_MODEL, EvaluationStrategy.fromConfig("JUDGE_MODEL"));
            assertEquals(EvaluationStrategy.NONE, EvaluationStrategy.fromConfig("  None  "));
            assertEquals(EvaluationStrategy.STRUCTURED_OUTPUT, EvaluationStrategy.fromConfig("Structured_Output"));
        }

        @Test
        @DisplayName("fromConfig returns null for null/blank/unknown")
        void fromConfigUnknown() {
            assertNull(EvaluationStrategy.fromConfig(null));
            assertNull(EvaluationStrategy.fromConfig(""));
            assertNull(EvaluationStrategy.fromConfig("   "));
            assertNull(EvaluationStrategy.fromConfig("custom_unknown"));
        }

        @Test
        @DisplayName("fromConfigOrDefault falls back to STRUCTURED_OUTPUT for null/unknown")
        void fromConfigOrDefault() {
            assertEquals(EvaluationStrategy.STRUCTURED_OUTPUT, EvaluationStrategy.DEFAULT);
            assertEquals(EvaluationStrategy.STRUCTURED_OUTPUT, EvaluationStrategy.fromConfigOrDefault(null));
            assertEquals(EvaluationStrategy.STRUCTURED_OUTPUT, EvaluationStrategy.fromConfigOrDefault("custom_unknown"));
            assertEquals(EvaluationStrategy.NONE, EvaluationStrategy.fromConfigOrDefault("none"));
        }
    }

    @Nested
    @DisplayName("CascadingStrategy")
    class Cascading {

        @Test
        @DisplayName("configValue() and fromConfig round-trip")
        void configValuesAndFromConfig() {
            assertEquals("cascade", CascadingStrategy.CASCADE.configValue());
            assertEquals("parallel", CascadingStrategy.PARALLEL.configValue());
            assertEquals(CascadingStrategy.CASCADE, CascadingStrategy.fromConfig("CASCADE"));
            assertEquals(CascadingStrategy.PARALLEL, CascadingStrategy.fromConfig("  parallel "));
            assertEquals(CascadingStrategy.CASCADE, CascadingStrategy.DEFAULT);
        }

        @Test
        @DisplayName("fromConfig returns null for null/blank/unknown")
        void fromConfigUnknown() {
            assertNull(CascadingStrategy.fromConfig(null));
            assertNull(CascadingStrategy.fromConfig(""));
            assertNull(CascadingStrategy.fromConfig("sequential"));
        }
    }
}
