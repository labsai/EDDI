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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExpectationEvaluator")
class ExpectationEvaluatorTest {

    private static final TurnObservation OBSERVED = new TurnObservation(List.of("greet", "ask_city"),
            Map.of("city", "Vienna", "rooms", 3, "profile", Map.of("tier", "gold", "since", 2020)), "We have 3 rooms in Vienna.",
            List.of(new ObservedToolCall("getAvailability", "{\"city\":\"Vienna\",\"nights\":2}"), new ObservedToolCall("logVisit", null)),
            0.015, "READY", Set.of("gpt-x"));

    private static Expectation expect(List<String> contain, List<String> exact, Map<String, PropertyExpectation> props, String contains,
                                      String matches, List<ToolCallExpectation> called, List<String> notCalled, Integer maxCalls,
                                      Double maxCost, String state) {
        return new Expectation(contain, exact, props, contains, matches, called, notCalled, maxCalls, maxCost, state);
    }

    private static List<ExpectationFailure> eval(Expectation e) {
        return ExpectationEvaluator.evaluate(e, OBSERVED);
    }

    private static List<String> failed(Expectation e) {
        return eval(e).stream().map(ExpectationFailure::expectation).toList();
    }

    @Test
    void nullExpectationAssertsNothing() {
        assertTrue(ExpectationEvaluator.evaluate(null, OBSERVED).isEmpty());
    }

    @Test
    @DisplayName("every expectation that holds passes together")
    void everythingThatHoldsPasses() {
        var all = expect(List.of("greet"), List.of("greet", "ask_city"),
                Map.of("city", new PropertyExpectation("Vienna", null, null, null), "missing", new PropertyExpectation(null, null, null, true)),
                "3 rooms", "(?i)3 ROOMS", List.of(new ToolCallExpectation("getAvailability", Map.of("city", "Vienna"))), List.of("createBooking"),
                2, 0.02, "READY");

        assertEquals(List.of(), eval(all));
    }

    @Nested
    class Actions {
        @Test
        void missingActionFailsWithTheActualActions() {
            var failures = eval(expect(List.of("greet", "book"), null, null, null, null, null, null, null, null, null));

            assertEquals(1, failures.size());
            assertEquals("actionsContain", failures.getFirst().expectation());
            assertEquals(List.of("greet", "ask_city"), failures.getFirst().actual());
        }

        @Test
        void exactIsOrderSensitive() {
            assertEquals(List.of("actionsExact"), failed(expect(null, List.of("ask_city", "greet"), null, null, null, null, null, null, null, null)));
        }
    }

    @Nested
    class Properties {
        private List<String> prop(String name, PropertyExpectation condition) {
            return failed(expect(null, null, Map.of(name, condition), null, null, null, null, null, null, null));
        }

        @Test
        void equalsComparesLoosely() {
            assertEquals(List.of(), prop("rooms", new PropertyExpectation("3", null, null, null)), "a rule-set string equals a number");
            assertEquals(List.of(), prop("rooms", new PropertyExpectation(3.0, null, null, null)));
            assertEquals(List.of("properties.rooms.equals"), prop("rooms", new PropertyExpectation(4, null, null, null)));
        }

        @Test
        void equalsOnObjectsIsStructural() {
            assertEquals(List.of(), prop("profile", new PropertyExpectation(Map.of("since", 2020, "tier", "gold"), null, null, null)));
            assertEquals(List.of("properties.profile.equals"), prop("profile", new PropertyExpectation(Map.of("tier", "gold"), null, null, null)),
                    "equals is not a subset match");
        }

        @Test
        void equalsOnAnAbsentPropertyFails() {
            assertEquals(List.of("properties.nope.equals"), prop("nope", new PropertyExpectation("x", null, null, null)));
        }

        @Test
        void existsAndAbsent() {
            assertEquals(List.of(), prop("city", new PropertyExpectation(null, null, true, null)));
            assertEquals(List.of("properties.nope.exists"), prop("nope", new PropertyExpectation(null, null, true, null)));
            assertEquals(List.of("properties.city.absent"), prop("city", new PropertyExpectation(null, null, null, true)));
        }

        @Test
        void matchesUsesTheTextForm() {
            assertEquals(List.of(), prop("city", new PropertyExpectation(null, "^Vie", null, null)));
            assertEquals(List.of(), prop("profile", new PropertyExpectation(null, "\"tier\":\"gold\"", null, null)));
            assertEquals(List.of("properties.city.matches"), prop("city", new PropertyExpectation(null, "^Graz$", null, null)));
        }
    }

    @Nested
    class Output {
        @Test
        void containsIsCaseSensitive() {
            assertEquals(List.of("outputContains"), failed(expect(null, null, null, "3 ROOMS", null, null, null, null, null, null)));
        }

        @Test
        void matchesIsAFind() {
            assertEquals(List.of(), failed(expect(null, null, null, null, "\\d rooms", null, null, null, null, null)));
            assertEquals(List.of("outputMatches"), failed(expect(null, null, null, null, "^\\d rooms$", null, null, null, null, null)));
        }

        @Test
        // This pattern backtracks for minutes on the JDK's engine (checked: still
        // running
        // after 3s without the bound); nested-quantifier classics like (a+)+$ are
        // optimised away by modern JDKs and would prove nothing.
        @DisplayName("a catastrophically backtracking regex fails the expectation instead of hanging the runner")
        void redosPatternTimesOutAsAFailure() {
            var observation = new TurnObservation(List.of(), Map.of(), "1,".repeat(30), List.of(), 0, "READY", Set.of());
            long start = System.nanoTime();

            var failures = ExpectationEvaluator.evaluate(expect(null, null, null, null, "(.*?,){11}P", null, null, null, null, null), observation);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(1, failures.size());
            assertEquals("<regex timed out>", failures.getFirst().actual());
            assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
        }
    }

    @Nested
    class Tools {
        @Test
        void calledWithArgumentSubset() {
            assertEquals(List.of(), failed(expect(null, null, null, null, null,
                    List.of(new ToolCallExpectation("getAvailability", Map.of("nights", 2))), null, null, null, null)));
            assertEquals(List.of("toolCalled"), failed(expect(null, null, null, null, null,
                    List.of(new ToolCallExpectation("getAvailability", Map.of("city", "Graz"))), null, null, null, null)));
            assertEquals(List.of("toolCalled"), failed(expect(null, null, null, null, null,
                    List.of(new ToolCallExpectation("createBooking", null)), null, null, null, null)));
        }

        @Test
        void callWithoutArgumentsMatchesOnlyWithoutSubset() {
            assertEquals(List.of(), failed(expect(null, null, null, null, null, List.of(new ToolCallExpectation("logVisit", null)), null, null,
                    null, null)));
            assertEquals(List.of("toolCalled"), failed(expect(null, null, null, null, null,
                    List.of(new ToolCallExpectation("logVisit", Map.of("x", 1))), null, null, null, null)));
        }

        @Test
        void notCalledAndMaxCalls() {
            var failures = eval(expect(null, null, null, null, null, null, List.of("logVisit"), 1, null, null));
            assertEquals(List.of("toolNotCalled", "maxToolCalls"), failures.stream().map(ExpectationFailure::expectation).toList());
            assertEquals(List.of("logVisit"), failures.getFirst().actual());
            assertEquals(2, failures.get(1).actual());
        }
    }

    @Test
    void costAndState() {
        assertEquals(List.of("maxCostUsd", "conversationState"),
                failed(expect(null, null, null, null, null, null, null, null, 0.01, "AWAITING_HUMAN")));
    }

    @Test
    void nestedArgumentSubsets() {
        assertTrue(ExpectationEvaluator.argumentsContain("{\"a\":{\"b\":1,\"c\":2}}", Map.of("a", Map.of("b", 1))));
        assertFalse(ExpectationEvaluator.argumentsContain("{\"a\":{\"b\":1}}", Map.of("a", Map.of("b", 2))));
        assertFalse(ExpectationEvaluator.argumentsContain("not json", Map.of("a", 1)));
    }

    @Test
    @DisplayName("a failed output expectation records the output cut like the turn's own, not in full")
    void failedOutputIsClipped() {
        String longOutput = "x".repeat(AgentTestRunner.MAX_OUTPUT_CHARS + 500);
        var observation = new TurnObservation(List.of(), Map.of(), longOutput, List.of(), 0, "READY", Set.of());

        var failures = ExpectationEvaluator.evaluate(expect(null, null, null, "absent text", "^y", null, null, null, null, null),
                observation);

        assertEquals(2, failures.size());
        for (var failure : failures) {
            assertEquals(AgentTestRunner.MAX_OUTPUT_CHARS + 1, String.valueOf(failure.actual()).length(), failure.expectation());
        }
    }
}
