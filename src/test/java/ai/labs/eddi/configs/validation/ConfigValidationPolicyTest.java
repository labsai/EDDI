/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigValidationPolicy")
class ConfigValidationPolicyTest {

    @Test
    @DisplayName("an interactive save refuses with every problem in the message")
    void strictModeRefuses() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> ConfigValidationPolicy.report("LLM configuration", List.of("tasks[0].type is required", "tasks[1].type 'x' unknown")));
        assertTrue(refused.getMessage().contains("tasks[0].type is required"));
        assertTrue(refused.getMessage().contains("tasks[1].type 'x' unknown"));
    }

    @Test
    @DisplayName("no problems never throws, in either mode")
    void nothingToReport() {
        assertDoesNotThrow(() -> ConfigValidationPolicy.report("x", List.of()));
        try (var scope = ConfigValidationPolicy.lenient()) {
            assertDoesNotThrow(() -> ConfigValidationPolicy.report("x", null));
            assertTrue(scope.warnings().isEmpty());
        }
    }

    @Test
    @DisplayName("a lenient scope records the problems as warnings instead of refusing")
    void lenientRecords() {
        try (var scope = ConfigValidationPolicy.lenient()) {
            assertTrue(ConfigValidationPolicy.isLenient());
            ConfigValidationPolicy.report("workflow", List.of("workflowSteps[0].type is not a known workflow step"));
            ConfigValidationPolicy.report("workflow", List.of("workflowSteps[0].type is not a known workflow step"));
            assertEquals(List.of("workflow: workflowSteps[0].type is not a known workflow step"), scope.warnings(),
                    "recorded once, with the kind of document in front");
        }
        assertFalse(ConfigValidationPolicy.isLenient(), "closing the scope restores the strict mode");
    }

    @Test
    @DisplayName("nested scopes forward to every enclosing scope and restore the outer one")
    void nestedScopes() {
        try (var outer = ConfigValidationPolicy.lenient()) {
            try (var inner = ConfigValidationPolicy.lenient()) {
                ConfigValidationPolicy.report("a", List.of("p"));
                assertEquals(1, inner.warnings().size());
            }
            assertTrue(ConfigValidationPolicy.isLenient(), "still inside the outer scope");
            assertEquals(List.of("a: p"), outer.warnings());
        }
        assertFalse(ConfigValidationPolicy.isLenient());
    }
}
