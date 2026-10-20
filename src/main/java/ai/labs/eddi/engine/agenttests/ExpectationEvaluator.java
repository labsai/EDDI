/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Expectation;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.PropertyExpectation;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolCallExpectation;
import ai.labs.eddi.engine.agenttests.TurnObservation.ObservedToolCall;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun.ExpectationFailure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Judges one turn's {@link TurnObservation} against its {@link Expectation}.
 * Pure: no I/O, no state, so every rule is unit-testable on its own.
 * <p>
 * Every present field of the expectation is checked and every one that does not
 * hold is reported — not just the first — so a failing turn shows the whole
 * difference in one run.
 *
 * @since 6.5.0
 */
public final class ExpectationEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private ExpectationEvaluator() {
    }

    public static List<ExpectationFailure> evaluate(Expectation expect, TurnObservation observed) {
        var failures = new ArrayList<ExpectationFailure>();
        if (expect == null) {
            return failures;
        }

        if (expect.actionsContain() != null) {
            var missing = expect.actionsContain().stream().filter(a -> !observed.actions().contains(a)).toList();
            if (!missing.isEmpty()) {
                failures.add(new ExpectationFailure("actionsContain", expect.actionsContain(), observed.actions()));
            }
        }
        if (expect.actionsExact() != null && !expect.actionsExact().equals(observed.actions())) {
            failures.add(new ExpectationFailure("actionsExact", expect.actionsExact(), observed.actions()));
        }

        if (expect.properties() != null) {
            expect.properties().forEach((name, condition) -> checkProperty(name, condition, observed.properties(), failures));
        }

        if (expect.outputContains() != null && !observed.output().contains(expect.outputContains())) {
            failures.add(new ExpectationFailure("outputContains", expect.outputContains(), clip(observed.output())));
        }
        if (expect.outputMatches() != null) {
            var result = BoundedRegex.find(expect.outputMatches(), observed.output());
            if (result != BoundedRegex.Result.MATCH) {
                failures.add(new ExpectationFailure("outputMatches", expect.outputMatches(),
                        result == BoundedRegex.Result.TIMED_OUT ? "<regex timed out>" : clip(observed.output())));
            }
        }

        List<ObservedToolCall> calls = observed.toolCalls();
        if (expect.toolCalled() != null) {
            for (ToolCallExpectation wanted : expect.toolCalled()) {
                boolean found = calls.stream()
                        .anyMatch(call -> wanted.tool().equals(call.tool()) && argumentsContain(call.arguments(), wanted.argsSubset()));
                if (!found) {
                    failures.add(new ExpectationFailure("toolCalled",
                            wanted.argsSubset() == null ? wanted.tool() : Map.of("tool", wanted.tool(), "argsSubset", wanted.argsSubset()),
                            calls.stream().map(ObservedToolCall::tool).toList()));
                }
            }
        }
        if (expect.toolNotCalled() != null) {
            var called = calls.stream().map(ObservedToolCall::tool).filter(expect.toolNotCalled()::contains).distinct().toList();
            if (!called.isEmpty()) {
                failures.add(new ExpectationFailure("toolNotCalled", expect.toolNotCalled(), called));
            }
        }
        if (expect.maxToolCalls() != null && calls.size() > expect.maxToolCalls()) {
            failures.add(new ExpectationFailure("maxToolCalls", expect.maxToolCalls(), calls.size()));
        }
        if (expect.maxCostUsd() != null && observed.costUsd() > expect.maxCostUsd()) {
            failures.add(new ExpectationFailure("maxCostUsd", expect.maxCostUsd(), observed.costUsd()));
        }
        if (expect.conversationState() != null && !expect.conversationState().equals(observed.conversationState())) {
            failures.add(new ExpectationFailure("conversationState", expect.conversationState(), observed.conversationState()));
        }
        return failures;
    }

    /**
     * The output as a failure reports it: cut like the turn's own {@code output},
     * so every failed turn of every repetition does not copy a full answer into the
     * single run document.
     */
    static String clip(String text) {
        int max = AgentTestRunner.MAX_OUTPUT_CHARS;
        return text != null && text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static void checkProperty(String name, PropertyExpectation condition, Map<String, Object> properties,
                                      List<ExpectationFailure> failures) {
        String label = "properties." + name;
        boolean present = properties.containsKey(name);
        Object actual = properties.get(name);
        if (Boolean.TRUE.equals(condition.exists()) && !present) {
            failures.add(new ExpectationFailure(label + ".exists", true, null));
        }
        if (Boolean.TRUE.equals(condition.absent()) && present) {
            failures.add(new ExpectationFailure(label + ".absent", true, actual));
        }
        if (condition.equalTo() != null && !(present && looselyEqual(condition.equalTo(), actual))) {
            failures.add(new ExpectationFailure(label + ".equals", condition.equalTo(), actual));
        }
        if (condition.matches() != null) {
            var result = present
                    ? BoundedRegex.find(Pattern.compile(condition.matches()), asText(actual), BoundedRegex.DEFAULT_TIMEOUT_MS)
                    : BoundedRegex.Result.NO_MATCH;
            if (result != BoundedRegex.Result.MATCH) {
                failures.add(new ExpectationFailure(label + ".matches", condition.matches(),
                        result == BoundedRegex.Result.TIMED_OUT ? "<regex timed out>" : actual));
            }
        }
    }

    /**
     * Whether a call's JSON arguments contain {@code subset}. A null subset matches
     * any call; arguments that are not a JSON object match only an empty subset.
     */
    static boolean argumentsContain(String argumentsJson, Map<String, Object> subset) {
        if (subset == null || subset.isEmpty()) {
            return true;
        }
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return false;
        }
        Map<String, Object> arguments;
        try {
            arguments = MAPPER.readValue(argumentsJson, MAP_TYPE);
        } catch (Exception e) {
            return false;
        }
        return containsSubset(arguments, subset);
    }

    static boolean containsSubset(Map<?, ?> actual, Map<?, ?> subset) {
        for (var entry : subset.entrySet()) {
            if (!actual.containsKey(entry.getKey())) {
                return false;
            }
            Object expected = entry.getValue();
            Object value = actual.get(entry.getKey());
            if (expected instanceof Map<?, ?> nested) {
                if (!(value instanceof Map<?, ?> nestedActual) || !containsSubset(nestedActual, nested)) {
                    return false;
                }
            } else if (!looselyEqual(expected, value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Equality that forgives representation, not meaning: {@code 3}, {@code 3.0}
     * and {@code "3"} are equal (properties set by a rule are strings, and JSON
     * does not distinguish integer from decimal), maps and lists compare element by
     * element.
     */
    static boolean looselyEqual(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof Map<?, ?> expectedMap) {
            if (!(actual instanceof Map<?, ?> actualMap) || expectedMap.size() != actualMap.size()) {
                return false;
            }
            return containsSubset(actualMap, expectedMap);
        }
        if (expected instanceof List<?> expectedList) {
            if (!(actual instanceof List<?> actualList) || expectedList.size() != actualList.size()) {
                return false;
            }
            for (int i = 0; i < expectedList.size(); i++) {
                if (!looselyEqual(expectedList.get(i), actualList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        BigDecimal expectedNumber = asNumber(expected);
        BigDecimal actualNumber = asNumber(actual);
        if (expectedNumber != null && actualNumber != null) {
            return expectedNumber.compareTo(actualNumber) == 0;
        }
        return asText(expected).equals(asText(actual));
    }

    private static BigDecimal asNumber(Object value) {
        String text = value instanceof Number number ? number.toString() : value instanceof String string ? string.trim() : null;
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null; // NaN, Infinity, or not a number at all
        }
    }

    private static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
