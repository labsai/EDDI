/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CascadingModelExecutor Tests")
class CascadingModelExecutorTest {

    @Nested
    @DisplayName("mergeParams")
    class MergeParamsTests {

        @Test
        @DisplayName("both null — returns empty map")
        void bothNull() {
            var result = CascadingModelExecutor.mergeParams(null, null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("baseParams null — returns stepParams")
        void baseNull() {
            var step = Map.of("model", "gpt-4");
            var result = CascadingModelExecutor.mergeParams(null, step);
            assertEquals("gpt-4", result.get("model"));
        }

        @Test
        @DisplayName("stepParams null — returns baseParams")
        void stepNull() {
            var base = Map.of("temperature", "0.7");
            var result = CascadingModelExecutor.mergeParams(base, null);
            assertEquals("0.7", result.get("temperature"));
        }

        @Test
        @DisplayName("step params override base params")
        void stepOverridesBase() {
            var base = new HashMap<String, String>();
            base.put("model", "gpt-3.5");
            base.put("temperature", "0.7");

            var step = new HashMap<String, String>();
            step.put("model", "gpt-4");

            var result = CascadingModelExecutor.mergeParams(base, step);
            assertEquals("gpt-4", result.get("model"));
            assertEquals("0.7", result.get("temperature"));
        }

        @Test
        @DisplayName("non-overlapping keys are merged")
        void nonOverlapping() {
            var base = Map.of("key1", "val1");
            var step = Map.of("key2", "val2");

            var result = CascadingModelExecutor.mergeParams(base, step);
            assertEquals("val1", result.get("key1"));
            assertEquals("val2", result.get("key2"));
        }
    }
}
