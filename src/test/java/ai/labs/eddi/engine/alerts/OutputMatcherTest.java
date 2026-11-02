/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.MatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OutputMatcher — the safe OUTPUT_MATCH matcher")
class OutputMatcherTest {

    private static TurnSnapshot snapshot(Map<String, Object> properties, String output, List<String> actions) {
        return new TurnSnapshot("c", "a", 1, properties, output, actions);
    }

    private static boolean matches(String path, MatchOp op, Object value, TurnSnapshot snapshot) {
        return OutputMatcher.matches(AlertCondition.outputMatch(path, op, value, null), snapshot);
    }

    @Nested
    @DisplayName("paths")
    class Paths {

        @Test
        @DisplayName("properties, nested properties, actions")
        void propertiesAndActions() {
            TurnSnapshot s = snapshot(Map.of("escalate", true, "order", Map.of("status", "late")), null, List.of("greet", "handoff_requested"));
            assertTrue(matches("properties.escalate", MatchOp.EQ, true, s));
            assertTrue(matches("properties.order.status", MatchOp.EQ, "late", s));
            assertTrue(matches("actions", MatchOp.CONTAINS, "handoff_requested", s));
            assertTrue(matches("actions", MatchOp.EXISTS, null, s));
            assertFalse(matches("properties.missing", MatchOp.EXISTS, null, s));
        }

        @Test
        @DisplayName("a turn without actions has none: actions exists does not fire, contains still does not match")
        void emptyActionsAreAbsent() {
            TurnSnapshot none = snapshot(Map.of(), null, List.of());
            assertFalse(matches("actions", MatchOp.EXISTS, null, none));
            assertFalse(matches("actions", MatchOp.CONTAINS, "handoff_requested", none));
            assertEquals(Optional.empty(), OutputMatcher.resolve("actions", none));
            assertFalse(matches("actions", MatchOp.EXISTS, null, snapshot(Map.of(), null, null)));
        }

        @Test
        @DisplayName("output.json reads the output only when it is JSON; plain text, oversized or broken JSON reads nothing")
        void outputJson() {
            assertTrue(matches("output.json.sentiment", MatchOp.IN, List.of("angry", "frustrated"),
                    snapshot(Map.of(), "{\"sentiment\":\"angry\",\"score\":0.2}", null)));
            assertTrue(matches("output.json.result.score", MatchOp.LT, 0.5, snapshot(Map.of(), " {\"result\":{\"score\":0.2}} ", null)));
            assertFalse(matches("output.json.sentiment", MatchOp.EXISTS, null, snapshot(Map.of(), "The sentiment is angry", null)));
            assertFalse(matches("output.json.sentiment", MatchOp.EXISTS, null, snapshot(Map.of(), "{\"sentiment\":", null)));
            String huge = "{\"sentiment\":\"" + "x".repeat(TurnSnapshot.MAX_OUTPUT_CHARS) + "\"}";
            assertFalse(matches("output.json.sentiment", MatchOp.EXISTS, null, snapshot(Map.of(), huge, null)));
        }

        @Test
        @DisplayName("deeply nested JSON beyond the parser's cap reads nothing instead of failing")
        void nestingCap() {
            String deep = "[".repeat(200) + "]".repeat(200);
            assertEquals(Optional.empty(), OutputMatcher.resolve("output.json.a", snapshot(Map.of(), deep, null)));
        }
    }

    @Nested
    @DisplayName("operators")
    class Operators {

        private final TurnSnapshot s = snapshot(Map.of("n", 5, "text", "please escalate now", "flag", false, "list", List.of("a", "b")), null,
                null);

        @Test
        @DisplayName("numbers compare by value; a value of the wrong type never matches")
        void numbers() {
            assertTrue(matches("properties.n", MatchOp.GT, 3, s));
            assertTrue(matches("properties.n", MatchOp.GTE, 5.0, s));
            assertTrue(matches("properties.n", MatchOp.EQ, 5.0, s));
            assertFalse(matches("properties.n", MatchOp.LT, 5, s));
            assertFalse(matches("properties.text", MatchOp.GT, 3, s));
            assertFalse(matches("properties.n", MatchOp.EQ, "5", s));
        }

        @Test
        @DisplayName("contains on strings and lists; neq; booleans")
        void stringsAndLists() {
            assertTrue(matches("properties.text", MatchOp.CONTAINS, "escalate", s));
            assertTrue(matches("properties.list", MatchOp.CONTAINS, "b", s));
            assertTrue(matches("properties.flag", MatchOp.EQ, false, s));
            assertTrue(matches("properties.flag", MatchOp.NEQ, true, s));
            assertFalse(matches("properties.missing", MatchOp.NEQ, true, s));
        }

        @Test
        @DisplayName("the description uses the rule's constant, never the runtime value")
        void describe() {
            assertEquals("properties.escalate eq true",
                    OutputMatcher.describe(AlertCondition.outputMatch("properties.escalate", MatchOp.EQ, true, null)));
            assertEquals("output.json.sentiment in [angry, frustrated]",
                    OutputMatcher.describe(AlertCondition.outputMatch("output.json.sentiment", MatchOp.IN, List.of("angry", "frustrated"), null)));
            assertEquals(280, OutputMatcher.render("y".repeat(500), 280).codePointCount(0, 280));
        }
    }

    @Nested
    @DisplayName("save-time validation")
    class Validation {

        private static List<String> problems(String path, MatchOp op, Object value) {
            return OutputMatcher.problems(AlertCondition.outputMatch(path, op, value, null));
        }

        @Test
        @DisplayName("valid conditions pass")
        void valid() {
            assertEquals(List.of(), problems("properties.escalate", MatchOp.EQ, true));
            assertEquals(List.of(), problems("output.json.sentiment", MatchOp.IN, List.of("angry")));
            assertEquals(List.of(), problems("actions", MatchOp.CONTAINS, "handoff_requested"));
            assertEquals(List.of(), problems("output.json.a.b.c.d.e.f.g.h", MatchOp.EXISTS, null));
        }

        @Test
        @DisplayName("path grammar: wildcards, other roots, empty segments and depth over 8 are refused")
        void grammar() {
            for (String path : List.of("properties.*", "memory.current.input", "properties", "output.json", "properties..x",
                    "output.json.a.b.c.d.e.f.g.h.i", "properties.a[0]", "{properties.x}")) {
                assertFalse(problems(path, MatchOp.EXISTS, null).isEmpty(), path);
            }
        }

        @Test
        @DisplayName("op and value must fit: numbers for comparisons, a list of 1–50 scalars for in, a string for contains")
        void types() {
            assertFalse(problems("properties.n", MatchOp.GT, "3").isEmpty());
            assertFalse(problems("properties.n", MatchOp.IN, "angry").isEmpty());
            assertFalse(problems("properties.n", MatchOp.IN, Collections.nCopies(51, "x")).isEmpty());
            assertFalse(problems("properties.n", MatchOp.IN, List.of(Map.of("a", 1))).isEmpty());
            assertFalse(problems("properties.n", MatchOp.CONTAINS, 3).isEmpty());
            assertFalse(problems("properties.n", MatchOp.EQ, List.of(1)).isEmpty());
            assertFalse(problems("properties.n", MatchOp.EQ, null).isEmpty());
            assertFalse(problems("properties.n", MatchOp.EXISTS, "x").isEmpty());
            assertFalse(problems("actions", MatchOp.EQ, "x").isEmpty());
            assertFalse(problems("properties.n", null, "x").isEmpty());
            assertFalse(problems("properties.n", MatchOp.EQ, "x".repeat(1001)).isEmpty());
        }
    }
}
