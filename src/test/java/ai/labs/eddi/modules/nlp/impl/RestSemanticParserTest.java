/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.impl;

import ai.labs.eddi.modules.nlp.impl.RestSemanticParser.ResponseSolution;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestSemanticParser} — specifically the
 * {@link ResponseSolution} inner class and testable utility methods.
 * <p>
 * The {@code parse()} method itself requires CDI + IRuntime and is tested via
 * integration tests. These tests cover the serializable DTO.
 */
class RestSemanticParserTest {

    @Nested
    @DisplayName("ResponseSolution")
    class ResponseSolutionTests {

        @Test
        @DisplayName("should create from Expressions")
        void createFromExpressions() {
            var expressions = new Expressions(new Expression("greeting", new Expression("hello")));
            var solution = new ResponseSolution(expressions);

            assertNotNull(solution.getExpressions());
            assertTrue(solution.getExpressions().contains("greeting"));
        }

        @Test
        @DisplayName("should support default constructor")
        void defaultConstructor() {
            var solution = new ResponseSolution();

            assertNull(solution.getExpressions());
        }

        @Test
        @DisplayName("should support setter")
        void setter() {
            var solution = new ResponseSolution();
            solution.setExpressions("test(value)");

            assertEquals("test(value)", solution.getExpressions());
        }

        @Test
        @DisplayName("should handle empty Expressions")
        void emptyExpressions() {
            var expressions = new Expressions();
            var solution = new ResponseSolution(expressions);

            assertNotNull(solution.getExpressions());
        }

        @Test
        @DisplayName("should handle multiple expressions")
        void multipleExpressions() {
            var expressions = new Expressions(
                    new Expression("greeting", new Expression("hello")),
                    new Expression("farewell", new Expression("bye")));
            var solution = new ResponseSolution(expressions);

            assertNotNull(solution.getExpressions());
            assertTrue(solution.getExpressions().contains("greeting"));
            assertTrue(solution.getExpressions().contains("farewell"));
        }
    }
}
