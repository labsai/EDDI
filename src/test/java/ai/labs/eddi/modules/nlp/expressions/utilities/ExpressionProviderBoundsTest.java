/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.utilities;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits.ExpressionLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expression parser is reachable by any chatting user through a
 * {@code context} entry of type {@code expressions}. It used to recurse once
 * per nesting level with no bound and copy the remainder of the string at every
 * level: twenty thousand nested parentheses overflowed the stack and a 3 MB
 * payload cost ten seconds of CPU. Both are now refused by
 * {@link ExpressionLimits} in one linear scan, and the index-based parser keeps
 * the grammar — quirks included — for everything within the bounds.
 */
@DisplayName("ExpressionProvider — length and depth bounds")
class ExpressionProviderBoundsTest {

    private static ExpressionProvider provider() {
        return new ExpressionProvider(new ExpressionFactory());
    }

    private static ExpressionProvider provider(int maxLength, int maxDepth) {
        return new ExpressionProvider(new ExpressionFactory(), new ExpressionLimits(maxLength, maxDepth));
    }

    /** {@code a(a(a(x)))}: {@code depth} parenthesised levels around a leaf. */
    private static String nested(int depth) {
        return "a(".repeat(depth) + "x" + ")".repeat(depth);
    }

    @Nested
    @DisplayName("the live payloads")
    class LivePayloads {

        @Test
        @DisplayName("20,000 nested parentheses are refused instead of overflowing the stack")
        void deepNestingIsRefusedNotOverflowed() {
            var provider = provider();
            // 60 KB — the length bound answers it before the depth bound gets a look.
            String payload = nested(20_000);

            var e = assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpressions(payload));

            assertTrue(e.getMessage().contains(ExpressionLimits.MAX_LENGTH_PROPERTY), e.getMessage());
        }

        @Test
        @DisplayName("deep nesting within the length bound is refused by the depth bound, naming it")
        void deepNestingWithinLengthIsRefusedByDepth() {
            var provider = provider();
            // 10 KB, 5,000 levels: below the length bound, far past the depth bound.
            String payload = "(".repeat(5_000) + ")".repeat(5_000);

            var e = assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpressions(payload));

            assertTrue(e.getMessage().contains(ExpressionLimits.MAX_DEPTH_PROPERTY), e.getMessage());
            assertEquals(ExpressionLimits.DEFAULT_MAX_DEPTH, e.getLimit());
            assertEquals(ExpressionLimits.DEFAULT_MAX_DEPTH + 1, e.getMeasured());
        }

        @Test
        @DisplayName("a 3 MB payload is refused by its length, before any parsing")
        void hugePayloadIsRefusedByLength() {
            var provider = provider();
            String payload = nested(1_000_000);

            var e = assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpressions(payload));

            assertTrue(e.getMessage().contains(ExpressionLimits.MAX_LENGTH_PROPERTY), e.getMessage());
            assertEquals(payload.length(), e.getMeasured());
            assertEquals(ExpressionLimits.DEFAULT_MAX_LENGTH, e.getLimit());
        }
    }

    @Nested
    @DisplayName("the bounds")
    class Bounds {

        @Test
        @DisplayName("nesting exactly at the depth bound parses; one level more is refused")
        void depthBoundary() {
            var provider = provider();

            Expressions atLimit = assertDoesNotThrow(() -> provider.parseExpressions(nested(ExpressionLimits.DEFAULT_MAX_DEPTH)));
            assertEquals(1, atLimit.size());
            assertEquals(ExpressionLimits.DEFAULT_MAX_DEPTH, depthOf(atLimit.get(0)));

            assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpressions(nested(ExpressionLimits.DEFAULT_MAX_DEPTH + 1)));
        }

        @Test
        @DisplayName("text exactly at the length bound parses; one character more is refused")
        void lengthBoundary() {
            var provider = provider();
            // "a,a,a,…" — long, flat, no nesting.
            String atLimit = "a,".repeat(ExpressionLimits.DEFAULT_MAX_LENGTH / 2);
            assertEquals(ExpressionLimits.DEFAULT_MAX_LENGTH, atLimit.length());

            assertEquals(ExpressionLimits.DEFAULT_MAX_LENGTH / 2, provider.parseExpressions(atLimit).size());
            assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpressions(atLimit + "a"));
        }

        @Test
        @DisplayName("the bounds are configurable")
        void configurable() {
            var provider = provider(10, 2);

            assertDoesNotThrow(() -> provider.parseExpressions("a(b(c))"));
            assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpressions("a(b(c(d)))"));
            assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpressions("abcdefghijk"));
        }

        @Test
        @DisplayName("zero disables a bound")
        void zeroDisables() {
            var provider = provider(0, 0);

            Expressions result = assertDoesNotThrow(() -> provider.parseExpressions(nested(200)));
            assertEquals(200, depthOf(result.get(0)));
        }

        @Test
        @DisplayName("parseExpression and createExpression enforce the same bounds")
        void everyEntryPoint() {
            var provider = provider(0, 3);

            assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpression(nested(4)));
            assertThrows(ExpressionLimitExceededException.class, () -> provider.createExpression("wrap", nested(3)));
            assertDoesNotThrow(() -> provider.createExpression("wrap", nested(2)));
        }

        @Test
        @DisplayName("the refusal is not swallowed by the parser's error logging")
        void notSwallowed() {
            // The old parser logged and continued on any exception from a nested level.
            // The limit is enforced in the entry scan AND re-checked where the parser
            // descends, and neither is caught.
            var limits = new ExpressionLimits(0, 3);
            assertThrows(ExpressionLimitExceededException.class, () -> limits.requireDepth(4));
            assertDoesNotThrow(() -> limits.requireDepth(3));
        }

        private int depthOf(Expression expression) {
            int depth = 0;
            Expression current = expression;
            while (current.getSubExpressions().length > 0) {
                depth++;
                current = current.getSubExpressions()[0];
            }
            return depth;
        }
    }

    @Nested
    @DisplayName("ExpressionLimits.check")
    class LimitsCheck {

        @Test
        @DisplayName("null and empty pass")
        void nullAndEmpty() {
            var limits = new ExpressionLimits(1, 1);
            assertDoesNotThrow(() -> limits.check(null));
            assertDoesNotThrow(() -> limits.check(""));
        }

        @Test
        @DisplayName("a stray closing parenthesis does not offset later nesting")
        void strayClosingParenthesis() {
            var limits = new ExpressionLimits(0, 2);
            // Without the floor at zero, ")))" would let three extra levels through.
            assertThrows(ExpressionLimitExceededException.class, () -> limits.check(")))a(b(c(d)))"));
        }

        @Test
        @DisplayName("unclosed parentheses count as nesting")
        void unclosedParentheses() {
            var limits = new ExpressionLimits(0, 2);
            assertThrows(ExpressionLimitExceededException.class, () -> limits.check("((("));
            assertDoesNotThrow(() -> limits.check("(("));
        }

        @Test
        @DisplayName("the exception names what was measured and the bound")
        void measuredAndLimit() {
            var e = assertThrows(ExpressionLimitExceededException.class, () -> new ExpressionLimits(5, 0).check("abcdef"));
            assertEquals(6, e.getMeasured());
            assertEquals(5, e.getLimit());
            // An IllegalArgumentException, so a pipeline task that parses it fails the turn
            // like any other bad input and no caller has to know the subtype.
            assertEquals(IllegalArgumentException.class, e.getClass().getSuperclass());
        }
    }

    /**
     * The grammar the index-based parser must keep — including the behaviour of the
     * old one on inputs nobody writes on purpose, since stored configs may contain
     * them.
     */
    @Nested
    @DisplayName("grammar parity with the substring-based parser")
    class GrammarParity {

        private final ExpressionProvider provider = provider();

        @Test
        @DisplayName("nested sub-expressions keep their names and order")
        void nested() {
            Expressions result = provider.parseExpressions("intent(greeting(hello, there), farewell), other(1)");

            assertEquals(2, result.size());
            Expression intent = result.get(0);
            assertEquals("intent", intent.getExpressionName());
            assertEquals(2, intent.getSubExpressions().length);
            Expression greeting = intent.getSubExpressions()[0];
            assertEquals("greeting", greeting.getExpressionName());
            assertEquals("hello", greeting.getSubExpressions()[0].getExpressionName());
            assertEquals("there", greeting.getSubExpressions()[1].getExpressionName());
            assertEquals("farewell", intent.getSubExpressions()[1].getExpressionName());
            assertEquals("other", result.get(1).getExpressionName());
            assertEquals("1", result.get(1).getSubExpressions()[0].getExpressionName());
        }

        @Test
        @DisplayName("whitespace around names and separators is trimmed")
        void whitespace() {
            Expressions result = provider.parseExpressions("  a( b , c ),  d  ");

            assertEquals(2, result.size());
            assertEquals("a", result.get(0).getExpressionName());
            assertEquals("b", result.get(0).getSubExpressions()[0].getExpressionName());
            assertEquals("c", result.get(0).getSubExpressions()[1].getExpressionName());
            assertEquals("d", result.get(1).getExpressionName());
        }

        @Test
        @DisplayName("a closing parenthesis before the opening one leaves the name and no sub-expressions")
        void closingBeforeOpening() {
            Expression result = provider.parseExpression(")name(");

            assertEquals(")name", result.getExpressionName());
            assertEquals(0, result.getSubExpressions().length);
        }

        @Test
        @DisplayName("an unclosed parenthesis is part of the name")
        void unclosed() {
            Expression result = provider.parseExpression("name(a");

            assertEquals("name(a", result.getExpressionName());
            assertEquals(0, result.getSubExpressions().length);
        }

        @Test
        @DisplayName("the character after a closing parenthesis is skipped (the old separator assumption)")
        void characterAfterClosingIsSkipped() {
            Expressions result = provider.parseExpressions("a(b)cd");

            assertEquals(2, result.size());
            assertEquals("a", result.get(0).getExpressionName());
            assertEquals("d", result.get(1).getExpressionName());
        }

        @Test
        @DisplayName("an empty name with parentheses keeps its sub-expressions")
        void emptyName() {
            Expressions result = provider.parseExpressions("(a)");

            assertEquals(1, result.size());
            assertEquals("", result.get(0).getExpressionName());
            assertEquals("a", result.get(0).getSubExpressions()[0].getExpressionName());
        }

        @Test
        @DisplayName("a trailing comma and empty parts produce nothing")
        void emptyParts() {
            assertEquals(2, provider.parseExpressions("a,,b,").size());
            assertEquals(0, provider.parseExpressions("   ").size());
        }
    }
}
