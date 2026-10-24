/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import ai.labs.eddi.utils.SafeJsonExpression.EvaluationException;
import ai.labs.eddi.utils.SafeJsonExpression.SyntaxException;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-guided fuzz tests for {@link SafeJsonExpression}, the evaluator of
 * the {@code expression} output guardrail. The expression is author
 * configuration, but the data is a model's answer — attacker-influenced — so
 * both sides are fuzzed. No input may cause anything but a
 * {@link SyntaxException} or an {@link EvaluationException}: no other
 * exception, no {@link StackOverflowError}, no unbounded runtime.
 * <p>
 * In CI these run as standard JUnit regression tests, together with the seeded
 * random sweeps below. Run with the Jazzer agent for real coverage-guided
 * fuzzing:
 * {@code mvn test -Dtest=SafeJsonExpressionFuzzTest -Djazzer.instrument=ai.labs.eddi.utils.SafeJsonExpression*}
 */
class SafeJsonExpressionFuzzTest {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

    private static final Object SEED_DATA = Map.of("total", new BigDecimal("30.30"), "invoice", Map.of("total", 30, "currency", "EUR"), "lines",
            List.of(Map.of("amount", new BigDecimal("10.10"), "qty", 1), Map.of("amount", new BigDecimal("20.20"), "qty", 2)), "tags",
            List.of("a", "b"), "paid", false);

    private static final List<String> EXPRESSIONS = List.of("sum(lines[*].amount) == total", "count(lines) > 0 && max(lines[*].qty) <= 10",
            "exists(invoice.total) && approx(invoice.total, total, 0.5)", "len(tags) == 2 || !paid", "round(total / 3, 2) * 3 >= total - 0.01",
            "min($[*]) < 0", "lines[0].amount % 2 == 0", "sum(orders[*].lines[*].amount) == total");

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz_$0123456789.,()[]*'\"\\+-/%=!<>&| \tsumcountminmaxlenabsroundexistsapprox";

    /**
     * Compiles and evaluates; anything other than the two declared failures is a
     * bug.
     */
    private static void check(String expression, Object data) {
        SafeJsonExpression compiled;
        try {
            compiled = SafeJsonExpression.compile(expression);
        } catch (SyntaxException e) {
            assertTrue(e.position() >= 0 && e.position() <= SafeJsonExpression.MAX_EXPRESSION_CHARS, "position out of range");
            return;
        }
        assertTrue(compiled.nodeCount() <= SafeJsonExpression.MAX_NODES);
        try {
            Object result = compiled.evaluate(data);
            assertTrue(
                    result == null || result instanceof Boolean || result instanceof BigDecimal || result instanceof String || result instanceof List
                            || result instanceof Map,
                    "unexpected result type");
        } catch (EvaluationException e) {
            assertTrue(e.code() != null && e.code().matches("[a-z_]+"), "evaluation error code must be EDDI's");
        }
    }

    private static Object parse(String json) {
        try {
            return JSON.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Fuzz Tests ────────────────────────────────────────────────

    @FuzzTest(maxDuration = "120s")
    void fuzzExpression(FuzzedDataProvider data) {
        check(data.consumeString(SafeJsonExpression.MAX_EXPRESSION_CHARS + 10), SEED_DATA);
    }

    @FuzzTest(maxDuration = "120s")
    void fuzzData(FuzzedDataProvider data) {
        String expression = data.pickValue(EXPRESSIONS);
        Object parsed = parse(data.consumeRemainingAsString());
        if (parsed != null) {
            check(expression, parsed);
        }
    }

    // ── Regression sweeps (run as standard JUnit) ─────────────────

    @Test
    @Timeout(30)
    void randomExpressionsNeverEscape() {
        Random random = new Random(20261024L);
        for (int i = 0; i < 20_000; i++) {
            int length = random.nextInt(80);
            StringBuilder s = new StringBuilder(length);
            for (int c = 0; c < length; c++) {
                s.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            check(s.toString(), SEED_DATA);
        }
    }

    @Test
    @Timeout(30)
    void mutatedValidExpressionsNeverEscape() {
        Random random = new Random(7L);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder s = new StringBuilder(EXPRESSIONS.get(random.nextInt(EXPRESSIONS.size())));
            for (int m = 0; m < 1 + random.nextInt(4); m++) {
                int at = random.nextInt(s.length() + 1);
                if (random.nextBoolean() && at < s.length()) {
                    s.deleteCharAt(at);
                } else {
                    s.insert(at, ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                }
            }
            check(s.toString(), SEED_DATA);
        }
    }

    @Test
    @Timeout(30)
    void pathologicalDataNeverEscapes() {
        String deep = "[".repeat(900) + "]".repeat(900);
        for (String json : List.of("{\"total\": 1e999999999, \"lines\": [{\"amount\": 1e-999999999}]}", "{\"lines\": " + deep + "}", deep,
                "{\"total\": \"30.30\", \"lines\": [{\"amount\": \"10.10\"}]}", "{\"lines\": [1, 2, 3], \"total\": true}", "[null, null]",
                "{\"total\": 123456789012345678901234567890123456789012345678901234567890}")) {
            Object parsed = parse(json);
            for (String expression : EXPRESSIONS) {
                check(expression, parsed);
            }
        }
    }
}
