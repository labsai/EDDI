/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import ai.labs.eddi.utils.SafeJsonExpression.EvaluationException;
import ai.labs.eddi.utils.SafeJsonExpression.SyntaxException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SafeJsonExpression")
class SafeJsonExpressionTest {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

    private static Object json(String text) {
        try {
            return JSON.readValue(text, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Object eval(String expr, String data) {
        return SafeJsonExpression.compile(expr).evaluate(json(data));
    }

    private static Object eval(String expr) {
        return eval(expr, "{}");
    }

    private static String evalError(String expr, String data) {
        return assertThrows(EvaluationException.class, () -> eval(expr, data)).code();
    }

    private static SyntaxException syntaxError(String expr) {
        return assertThrows(SyntaxException.class, () -> SafeJsonExpression.compile(expr));
    }

    private static void assertNumber(String expected, Object actual) {
        assertTrue(actual instanceof BigDecimal, "a number, got " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), "expected " + expected + ", got " + actual);
    }

    private static final String INVOICE = """
            {"invoice": {"total": 30.30, "currency": "EUR"},
             "total": 30.30,
             "lines": [{"amount": 10.10, "qty": 1}, {"amount": 20.20, "qty": 2}],
             "tags": ["a", "b", "c"],
             "paid": false,
             "note": null}
            """;

    @Nested
    @DisplayName("grammar and precedence")
    class Grammar {

        @ParameterizedTest
        @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {"1 + 2 * 3 => 7", "(1 + 2) * 3 => 9", "10 - 4 - 3 => 3", "2 * 3 % 4 => 2",
                "-2 * 3 => -6", "- -2 => 2", "7 / 2 => 3.5", "8 / 2 / 2 => 2", "10 % 4 => 2", "-(1 + 2) => -3"})
        void arithmetic(String expr, String expected) {
            assertNumber(expected, eval(expr));
        }

        @ParameterizedTest
        @CsvSource(delimiterString = " => ", quoteCharacter = '`',
                   value = {"1 < 2 && 2 < 3 => true", "true || false && false => true", "(true || false) && false => false", "!true || true => true",
                           "!(1 == 1) => false", "1 + 1 == 2 => true", "2 > 1 == true => true", "'a' < 'b' => true", "\"x\" == 'x' => true",
                           "1.0 == 1.00 => true", "1 != 2 => true", "3 >= 3 && 3 <= 3 => true", "null == null => true", "false == false => true"})
        void logicAndComparison(String expr, boolean expected) {
            assertEquals(expected, eval(expr));
        }

        @Test
        void literals() {
            assertEquals(Boolean.TRUE, eval("true"));
            assertEquals("it's", eval("'it\\'s'"));
            assertEquals("a\\b", eval("'a\\\\b'"));
            assertEquals(null, eval("null"));
            assertNumber("12.50", eval("12.50"));
        }

        @Test
        @DisplayName("whitespace anywhere between tokens")
        void whitespace() {
            assertEquals(Boolean.TRUE, eval("  sum ( lines [ * ] . amount )\n==\ttotal ", INVOICE));
        }
    }

    @Nested
    @DisplayName("BigDecimal arithmetic")
    class Exactness {

        @Test
        @DisplayName("0.1 + 0.2 == 0.3 — the binary-float trap does not apply")
        void decimalSum() {
            assertEquals(Boolean.TRUE, eval("0.1 + 0.2 == 0.3"));
            assertEquals(Boolean.TRUE, eval("a + b == c", "{\"a\": 0.1, \"b\": 0.2, \"c\": 0.3}"));
        }

        @Test
        @DisplayName("money sums are exact, and a cent off is not equal")
        void moneySum() {
            assertEquals(Boolean.TRUE, eval("sum(lines[*].amount) == total", INVOICE));
            assertEquals(Boolean.FALSE,
                    eval("sum(lines[*].amount) == total", "{\"total\": 30.31, \"lines\": [{\"amount\": 10.10}, {\"amount\": 20.20}]}"));
        }

        @Test
        @DisplayName("division rounds to 34 significant digits, half-even")
        void division() {
            assertNumber("0.3333333333333333333333333333333333", eval("1 / 3"));
            assertNumber("0.6666666666666666666666666666666667", eval("2 / 3"));
        }

        @Test
        @DisplayName("round() is half-up to n decimals; approx() compares with a tolerance")
        void roundAndApprox() {
            assertNumber("2.35", eval("round(2.345, 2)"));
            assertNumber("3", eval("round(2.5)"));
            assertNumber("-3", eval("round(-2.5)"));
            assertEquals(Boolean.TRUE, eval("approx(10.004, 10, 0.005)"));
            assertEquals(Boolean.FALSE, eval("approx(10.006, 10, 0.005)"));
        }

        @Test
        @DisplayName("numbers of every Java type are accepted, doubles by their decimal form")
        void javaNumbers() {
            Map<String, Object> data = new HashMap<>();
            data.put("i", 2);
            data.put("l", 3L);
            data.put("d", 0.1);
            data.put("f", 0.5f);
            assertEquals(Boolean.TRUE, SafeJsonExpression.compile("i + l == 5 && d == 0.1 && f == 0.5").evaluate(data));
        }
    }

    @Nested
    @DisplayName("paths, wildcards and aggregates")
    class Paths {

        @Test
        void navigation() {
            assertNumber("30.30", eval("invoice.total", INVOICE));
            assertNumber("20.20", eval("lines[1].amount", INVOICE));
            assertEquals("EUR", eval("invoice['currency']", INVOICE));
            assertEquals("b", eval("tags[1]", INVOICE));
            assertNumber("10", eval("$[0] + $[1]", "[4, 6]"));
            assertEquals(Boolean.TRUE, eval("sum($[*].amount) == 3", "[{\"amount\": 1}, {\"amount\": 2}]"));
        }

        @Test
        void wildcardYieldsAList() {
            assertEquals(List.of(new BigDecimal("10.10"), new BigDecimal("20.20")), eval("lines[*].amount", INVOICE));
        }

        @Test
        @DisplayName("a wildcard after a wildcard flattens")
        void nestedWildcard() {
            String orders = "{\"orders\": [{\"lines\": [{\"a\": 1}, {\"a\": 2}]}, {\"lines\": [{\"a\": 3}]}]}";
            assertNumber("6", eval("sum(orders[*].lines[*].a)", orders));
            assertNumber("3", eval("count(orders[*].lines[*])", orders));
        }

        @Test
        void aggregates() {
            assertNumber("2", eval("count(lines)", INVOICE));
            assertNumber("2", eval("count(lines[*].amount)", INVOICE));
            assertNumber("10.10", eval("min(lines[*].amount)", INVOICE));
            assertNumber("20.20", eval("max(lines[*].amount)", INVOICE));
            assertNumber("3", eval("max(1, 3, 2)"));
            assertNumber("1", eval("min(1, 3, 2)"));
            assertNumber("3", eval("len(tags)", INVOICE));
            assertNumber("3", eval("len('abc')"));
            assertNumber("2", eval("len(invoice)", INVOICE));
            assertNumber("4.5", eval("abs(-4.5)"));
            assertNumber("0", eval("sum(lines[*].amount)", "{\"lines\": []}"));
            assertNumber("50.50", eval("lines[0].amount * lines[0].qty", "{\"lines\": [{\"amount\": 25.25, \"qty\": 2}]}"));
        }

        @Test
        @DisplayName("a wildcard over something that is not an array is null")
        void wildcardOverNonArray() {
            assertEquals(null, eval("invoice[*].total", INVOICE));
            assertEquals(null, eval("missing[*].total", INVOICE));
        }

        @Test
        @DisplayName("after a wildcard, an element without the rest of the path contributes null")
        void wildcardMissingElementIsNull() {
            List<?> values = (List<?>) eval("lines[*].amount", "{\"lines\": [{\"amount\": 1}, {}]}");
            assertEquals(2, values.size());
            assertEquals(null, values.get(1));
            assertEquals(SafeJsonExpression.ERR_NULL_OPERAND, evalError("sum(lines[*].amount) == 1", "{\"lines\": [{\"amount\": 1}, {}]}"));
        }
    }

    @Nested
    @DisplayName("null and missing paths")
    class Nulls {

        @Test
        @DisplayName("a missing path and a JSON null are both null")
        void missingIsNull() {
            assertEquals(Boolean.TRUE, eval("discount == null", INVOICE));
            assertEquals(Boolean.TRUE, eval("note == null", INVOICE));
            assertEquals(Boolean.TRUE, eval("lines[9] == null && invoice.total.x == null && tags.x == null", INVOICE));
            assertEquals(Boolean.FALSE, eval("total == null", INVOICE));
            assertEquals(Boolean.TRUE, eval("total != null", INVOICE));
        }

        @ParameterizedTest
        @ValueSource(strings = {"discount > 0", "discount < 0", "discount >= 0", "discount <= 0", "0 < discount"})
        @DisplayName("ordering comparisons with null are false")
        void orderingWithNullIsFalse(String expr) {
            assertEquals(Boolean.FALSE, eval(expr, INVOICE));
        }

        @ParameterizedTest
        @ValueSource(strings = {"discount + 1 > 0", "-discount == 1", "discount && true", "!discount", "sum(discount) == 0", "abs(note) == 0",
                "len(note) == 0", "count(missing) == 0"})
        @DisplayName("arithmetic, logic and functions on null cannot decide")
        void arithmeticOnNullIsAnError(String expr) {
            assertEquals(SafeJsonExpression.ERR_NULL_OPERAND, evalError(expr, INVOICE));
        }

        @Test
        @DisplayName("exists() tells a present key holding null from a missing one")
        void exists() {
            assertEquals(Boolean.TRUE, eval("exists(note)", INVOICE));
            assertEquals(Boolean.FALSE, eval("exists(discount)", INVOICE));
            assertEquals(Boolean.TRUE, eval("exists(lines[1].amount)", INVOICE));
            assertEquals(Boolean.FALSE, eval("exists(lines[2])", INVOICE));
            assertEquals(Boolean.TRUE, eval("exists($)", INVOICE));
        }

        @Test
        @DisplayName("&& and || short-circuit: the guarded side is never evaluated")
        void shortCircuit() {
            assertEquals(Boolean.TRUE, eval("!exists(discount) || discount > 0 && discount + 1 > 1", INVOICE));
            assertEquals(Boolean.FALSE, eval("exists(discount) && discount + 1 > 1", INVOICE));
            assertEquals(Boolean.TRUE, eval("true || 1 / 0 == 1"));
        }
    }

    @Nested
    @DisplayName("evaluation errors")
    class Errors {

        @ParameterizedTest
        @ValueSource(strings = {"1 / 0", "1 % 0", "total / (total - total) > 0"})
        void divisionByZero(String expr) {
            assertEquals(SafeJsonExpression.ERR_DIVISION_BY_ZERO, evalError(expr, INVOICE));
        }

        @ParameterizedTest
        @ValueSource(strings = {"total == 'x'", "invoice.currency + 1", "total && true", "paid == 0", "tags == tags", "invoice == 1", "total < 'a'",
                "sum(tags) == 0", "len(total) == 1", "sum(total) == 0", "!total", "-invoice.currency"})
        void typeMismatch(String expr) {
            assertEquals(SafeJsonExpression.ERR_TYPE_MISMATCH, evalError(expr, INVOICE));
        }

        @Test
        void emptyAggregateAndBadArguments() {
            assertEquals(SafeJsonExpression.ERR_EMPTY_AGGREGATE, evalError("max(lines[*].amount) > 0", "{\"lines\": []}"));
            assertEquals(SafeJsonExpression.ERR_BAD_ARGUMENT, evalError("round(1.5, 21) == 1", "{}"));
            assertEquals(SafeJsonExpression.ERR_BAD_ARGUMENT, evalError("round(1.5, 0.5) == 1", "{}"));
            assertEquals(SafeJsonExpression.ERR_BAD_ARGUMENT, evalError("round(1.5, -1) == 1", "{}"));
            assertEquals(SafeJsonExpression.ERR_BAD_ARGUMENT, evalError("approx(1, 1, -0.1)", "{}"));
        }

        @Test
        @DisplayName("numbers beyond 1000 digits or scale are refused, never materialised")
        @Timeout(5)
        void numberRange() {
            assertEquals(SafeJsonExpression.ERR_NUMBER_RANGE, evalError("x + 1 > 0", "{\"x\": 1e-999999}"));
            assertEquals(SafeJsonExpression.ERR_NUMBER_RANGE, evalError("x > 0", "{\"x\": 1e999999}"));
            assertEquals(SafeJsonExpression.ERR_NUMBER_RANGE, evalError("x * x * x > 0", "{\"x\": 1e400}"));
            assertEquals(SafeJsonExpression.ERR_NUMBER_RANGE,
                    assertThrows(EvaluationException.class, () -> SafeJsonExpression.compile("x > 0").evaluate(Map.of("x", Double.NaN))).code());
        }

        @Test
        @DisplayName("a non-boolean result is returned as is: deciding what it means is the caller's job")
        void nonBooleanResult() {
            assertNumber("30.30", eval("total", INVOICE));
            assertEquals("EUR", eval("invoice.currency", INVOICE));
        }

        @Test
        @DisplayName("the data is never modified")
        void dataUntouched() {
            Object data = json(INVOICE);
            String before = data.toString();
            SafeJsonExpression.compile("sum(lines[*].amount) == total && count(tags) == 3").evaluate(data);
            assertEquals(before, data.toString());
        }
    }

    @Nested
    @DisplayName("syntax errors carry a position")
    class Syntax {

        @ParameterizedTest
        @CsvSource(delimiterString = " => ", quoteCharacter = '`',
                   value = {"total = 1 => 6", "total & x => 6", "total | x => 6", "1 + => 3", "(1 + 2 => 6", "foo(1) => 0", "sum(1, 2) => 0",
                           "approx(1, 2) => 0", "a < b < c => 6", "a == b == c => 7", "a == b != c => 7", "1 2 => 2", "lines[-1] => 6",
                           "lines[1.5] => 6", "lines[x] => 6", "a. => 2", "'open => 0", "# => 0", "12abc => 2", "exists(1) => 7",
                           "exists(lines[*].amount) => 7", "'\\n' => 1", ") => 0", " => 0"})
        void refusedWithPosition(String expr, int position) {
            String source = expr == null ? "" : expr;
            assertEquals(position, syntaxError(source).position(), source);
        }

        @Test
        @DisplayName("a non-printable character is named by its code point, not echoed")
        void nonPrintable() {
            assertTrue(syntaxError("a \u0007 b").getMessage().contains("U+0007"));
        }

        @Test
        @DisplayName("an unknown function is refused at compile time, so no name is ever looked up at run time")
        void noReflectionPath() {
            assertTrue(syntaxError("getClass()").getMessage().contains("unknown function"));
            assertThrows(SyntaxException.class, () -> SafeJsonExpression.compile("java.lang.System.exit(0)"));
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("an expression longer than the cap is refused before it is lexed")
        void length() {
            String longExpr = "1 == 1" + " && 1 == 1".repeat(250);
            assertTrue(longExpr.length() > SafeJsonExpression.MAX_EXPRESSION_CHARS);
            assertEquals(SafeJsonExpression.MAX_EXPRESSION_CHARS, syntaxError(longExpr).position());
        }

        @Test
        @DisplayName("deep nesting is refused cleanly — parentheses, negation, function arguments — with no StackOverflowError")
        @Timeout(5)
        void depth() {
            int over = SafeJsonExpression.MAX_DEPTH + 1;
            assertTrue(syntaxError("(".repeat(over) + "1" + ")".repeat(over)).getMessage().contains("nested"));
            assertTrue(syntaxError("!".repeat(over) + "true").getMessage().contains("nested"));
            assertTrue(syntaxError("abs(".repeat(over) + "1" + ")".repeat(over)).getMessage().contains("nested"));
            assertThrows(SyntaxException.class, () -> SafeJsonExpression.compile("(".repeat(1900)));
            // just under the cap compiles
            int under = SafeJsonExpression.MAX_DEPTH - 1;
            assertNumber("1", SafeJsonExpression.compile("(".repeat(under) + "1" + ")".repeat(under)).evaluate(Map.of()));
        }

        @Test
        @DisplayName("more parts than the node cap are refused")
        void nodes() {
            String many = "1" + " + 1".repeat(SafeJsonExpression.MAX_NODES / 2);
            assertTrue(many.length() <= SafeJsonExpression.MAX_EXPRESSION_CHARS);
            assertTrue(syntaxError(many).getMessage().contains("parts"));
            String fits = "1" + " + 1".repeat(SafeJsonExpression.MAX_NODES / 2 - 1);
            assertTrue(SafeJsonExpression.compile(fits).nodeCount() <= SafeJsonExpression.MAX_NODES);
        }

        @Test
        @DisplayName("a path with more segments than the cap is refused")
        void pathSegments() {
            String path = "a" + ".b".repeat(SafeJsonExpression.MAX_PATH_SEGMENTS);
            assertTrue(syntaxError(path).getMessage().contains("segments"));
            SafeJsonExpression.compile("a" + ".b".repeat(SafeJsonExpression.MAX_PATH_SEGMENTS - 1));
        }

        @Test
        @DisplayName("a wildcard over more elements than the fan-out cap cannot decide")
        @Timeout(5)
        void fanOut() {
            List<Object> lines = new ArrayList<>();
            for (int i = 0; i <= SafeJsonExpression.MAX_FAN_OUT; i++) {
                lines.add(Map.of("amount", 1));
            }
            Map<String, Object> data = Map.of("lines", lines, "rows", lines.subList(0, 101));
            var expression = SafeJsonExpression.compile("sum(lines[*].amount) > 0");
            assertEquals(SafeJsonExpression.ERR_FAN_OUT, assertThrows(EvaluationException.class, () -> expression.evaluate(data)).code());
            assertEquals(SafeJsonExpression.ERR_FAN_OUT,
                    assertThrows(EvaluationException.class, () -> SafeJsonExpression.compile("count(lines) > 0").evaluate(data)).code());
            // A bare path, with no aggregate around it: only the path's own cap can stop
            // this (sum/count above also hit the list() check, which would mask a missing
            // path cap).
            assertEquals(SafeJsonExpression.ERR_FAN_OUT,
                    assertThrows(EvaluationException.class, () -> SafeJsonExpression.compile("lines[*].amount != null").evaluate(data)).code());
            // 101 x 101 through a nested wildcard is 10,201 values
            List<Object> grid = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                grid.add(Map.of("cells", lines.subList(0, 101)));
            }
            assertEquals(SafeJsonExpression.ERR_FAN_OUT, assertThrows(EvaluationException.class,
                    () -> SafeJsonExpression.compile("count(grid[*].cells[*]) > 0").evaluate(Map.of("grid", grid))).code());
            assertEquals(Boolean.TRUE, SafeJsonExpression.compile("count(rows[*].amount) == 101").evaluate(data));
        }

        @Test
        @DisplayName("an expression that would take more than the step budget stops")
        @Timeout(5)
        void stepBudget() {
            List<Object> lines = new ArrayList<>();
            for (int i = 0; i < SafeJsonExpression.MAX_FAN_OUT; i++) {
                lines.add(Map.of("amount", 1));
            }
            // each sum walks 10,000 values; 40 of them are well past the budget
            String expr = "sum(lines[*].amount)" + " + sum(lines[*].amount)".repeat(39) + " > 0";
            var expression = SafeJsonExpression.compile(expr);
            assertEquals(SafeJsonExpression.ERR_STEP_BUDGET,
                    assertThrows(EvaluationException.class, () -> expression.evaluate(Map.of("lines", lines))).code());
            assertEquals(Boolean.TRUE, SafeJsonExpression.compile("sum(lines[*].amount) == 10000").evaluate(Map.of("lines", lines)));
        }

        @Test
        @DisplayName("a long left-associative chain within the caps evaluates without deep recursion trouble")
        void longChain() {
            String fits = "1" + " + 1".repeat(SafeJsonExpression.MAX_NODES / 2 - 1);
            assertNumber(String.valueOf(SafeJsonExpression.MAX_NODES / 2), SafeJsonExpression.compile(fits).evaluate(Map.of()));
        }
    }
}
