/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code evaluateExpression} probes every operand range for the longest
 * resolvable prefix, so its work grows with the square of the operand count —
 * each probe a regex pass. The count is capped; past it the expression resolves
 * to nothing.
 */
@DisplayName("PathNavigator — operand count is capped")
class PathNavigatorOperandCapTest {

    private static final Map<String, Object> ROOT = Map.of("a", "x", "n", 1);

    private static String joined(String operand, String operator, int count) {
        return (operand + operator).repeat(count - 1) + operand;
    }

    @Test
    @DisplayName("an expression at the cap resolves")
    void atTheCap() {
        assertEquals("x".repeat(PathNavigator.MAX_OPERANDS), PathNavigator.getValue(joined("a", "+", PathNavigator.MAX_OPERANDS), ROOT));
        assertEquals(PathNavigator.MAX_OPERANDS, PathNavigator.getValue(joined("n", "+", PathNavigator.MAX_OPERANDS), ROOT));
    }

    @Test
    @DisplayName("one operand more resolves to nothing")
    void overTheCap() {
        assertNull(PathNavigator.getValue(joined("a", "+", PathNavigator.MAX_OPERANDS + 1), ROOT));
        assertNull(PathNavigator.getValue(joined("n", "-", PathNavigator.MAX_OPERANDS + 1), ROOT));
    }

    @Test
    @DisplayName("a pathological operand count is answered at once")
    void pathological() {
        assertNull(PathNavigator.getValue(joined("a", "+", 5_000), ROOT));
    }
}
