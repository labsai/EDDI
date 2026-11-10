/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.cluster;

import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.Kind;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.LlmCall;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.Outcome;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.Report;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationHistoryCheckerTest {

    private static final String C = "conv-1";

    private static Turn ok(String input, long sent, long answered) {
        return new Turn(C, input, "n1", 200, sent, answered);
    }

    private static Turn status(String input, int status, long sent, long answered) {
        return new Turn(C, input, "n2", status, sent, answered);
    }

    private static LlmCall call(String user, String prev, double start, double end) {
        return new LlmCall(user, prev, "user", start, end);
    }

    private static Report check(List<Turn> turns, List<String> committed, List<LlmCall> calls) {
        return ConversationHistoryChecker.check(turns, Map.of(C, committed), calls);
    }

    @Test
    @DisplayName("a sequential, fully committed history with chained LLM calls is clean")
    void cleanHistory() {
        Report r = check(List.of(ok("a", 0, 10), ok("b", 11, 20), ok("c", 21, 30)), List.of("a", "b", "c"),
                List.of(call("a", "", 0.001, 0.009), call("b", "a", 0.012, 0.019), call("c", "b", 0.022, 0.029)));
        assertTrue(r.clean(), r.summary());
        assertEquals(3, r.committedTurns());
        assertEquals(Map.of(Outcome.OK, 3), r.outcomes());
    }

    @Nested
    @DisplayName("what the clients saw against what was committed")
    class ClientVsCommitted {

        @Test
        @DisplayName("200 but not committed is LOST")
        void lost() {
            Report r = check(List.of(ok("a", 0, 10), ok("b", 11, 20)), List.of("a"), List.of());
            assertEquals(1, r.of(Kind.LOST).size(), r.summary());
            assertTrue(r.of(Kind.LOST).get(0).detail().contains("'b'"));
        }

        @Test
        @DisplayName("an input committed twice is DUPLICATED")
        void duplicated() {
            Report r = check(List.of(ok("a", 0, 10)), List.of("a", "a"), List.of());
            assertEquals(List.of(Kind.DUPLICATED), r.violations().stream().map(v -> v.kind()).toList(), r.summary());
        }

        @Test
        @DisplayName("an input nobody sent is PHANTOM")
        void phantom() {
            Report r = check(List.of(ok("a", 0, 10)), List.of("a", "x"), List.of());
            assertEquals(List.of(Kind.PHANTOM), r.violations().stream().map(v -> v.kind()).toList(), r.summary());
        }

        @Test
        @DisplayName("an input committed to another conversation than it was sent to is PHANTOM there and LOST here")
        void wrongConversation() {
            Report r = ConversationHistoryChecker.check(List.of(ok("a", 0, 10)), Map.of(C, List.of(), "other", List.of("a")), List.of());
            assertEquals(1, r.of(Kind.PHANTOM).size(), r.summary());
            assertEquals(1, r.of(Kind.LOST).size(), r.summary());
        }

        @Test
        @DisplayName("409 or 503 and committed anyway is REFUSED_BUT_COMMITTED")
        void refusedButCommitted() {
            Report r = check(List.of(ok("a", 0, 10), status("b", 409, 11, 20), status("c", 503, 21, 30)), List.of("a", "b"), List.of());
            assertEquals(1, r.of(Kind.REFUSED_BUT_COMMITTED).size(), r.summary());
            assertEquals(1, r.violations().size(), r.summary());
        }

        @Test
        @DisplayName("an indeterminate turn may be committed once, or not at all")
        void indeterminateEitherWay() {
            Report committed = check(List.of(status("a", 0, 0, 10), status("b", 500, 11, 20)), List.of("a"), List.of());
            assertTrue(committed.clean(), committed.summary());
            assertEquals(Map.of(Outcome.INDETERMINATE, 2), committed.outcomes());
        }

        @Test
        @DisplayName("a refused turn that is not committed is fine")
        void refusedNotCommitted() {
            assertTrue(check(List.of(status("a", 409, 0, 10)), List.of(), List.of()).clean());
        }
    }

    @Nested
    @DisplayName("real-time order")
    class RealTimeOrder {

        @Test
        @DisplayName("answered before the next was sent, but stored after it, is OUT_OF_ORDER")
        void outOfOrder() {
            Report r = check(List.of(ok("a", 0, 10), ok("b", 11, 20)), List.of("b", "a"), List.of());
            assertEquals(1, r.of(Kind.OUT_OF_ORDER).size(), r.summary());
        }

        @Test
        @DisplayName("concurrent turns may commit in either order")
        void concurrentEitherOrder() {
            assertTrue(check(List.of(ok("a", 0, 10), ok("b", 5, 15)), List.of("b", "a"), List.of()).clean());
        }

        @Test
        @DisplayName("an indeterminate turn sent after an answered one still has to come after it")
        void indeterminateLaterTurnIsOrdered() {
            Report r = check(List.of(ok("a", 0, 10), status("b", 0, 11, 40)), List.of("b", "a"), List.of());
            assertEquals(1, r.of(Kind.OUT_OF_ORDER).size(), r.summary());
        }
    }

    @Nested
    @DisplayName("what the LLM saw")
    class LlmView {

        @Test
        @DisplayName("two committed turns whose calls overlap is OVERLAP")
        void overlap() {
            Report r = check(List.of(ok("a", 0, 10), ok("b", 0, 12)), List.of("a", "b"),
                    List.of(call("a", "", 1.0, 3.0), call("b", "a", 2.0, 4.0)));
            assertEquals(1, r.of(Kind.OVERLAP).size(), r.summary());
        }

        @Test
        @DisplayName("an overlap within the clock tolerance is not one")
        void touchingIsNoOverlap() {
            Report r = check(List.of(ok("a", 0, 10), ok("b", 0, 12)), List.of("a", "b"),
                    List.of(call("a", "", 1.0, 3.0), call("b", "a", 2.995, 4.0)));
            assertTrue(r.clean(), r.summary());
        }

        @Test
        @DisplayName("a zombie call that never committed may overlap; it is only counted")
        void zombieOverlapIsCounted() {
            Report r = check(List.of(status("z", 0, 0, 60), ok("b", 5, 12)), List.of("b"),
                    List.of(call("z", "", 1.0, 40.0), call("b", "", 25.0, 26.0)));
            assertTrue(r.clean(), r.summary());
            assertEquals(1, r.zombieOverlaps());
        }

        @Test
        @DisplayName("a committed turn that did not see its committed predecessor is STALE_CONTEXT")
        void staleContext() {
            Report r = check(List.of(ok("a", 0, 10), ok("b", 11, 20), ok("c", 21, 30)), List.of("a", "b", "c"),
                    List.of(call("a", "", 1, 2), call("b", "a", 12, 13), call("c", "a", 22, 23)));
            assertEquals(1, r.of(Kind.STALE_CONTEXT).size(), r.summary());
        }

        @Test
        @DisplayName("the committed call is the last one started for that input")
        void lastCallCounts() {
            Report r = check(List.of(ok("a", 0, 10), ok("b", 11, 30)), List.of("a", "b"),
                    List.of(call("a", "", 1, 2), call("b", "", 12, 13), call("b", "a", 20, 21)));
            assertTrue(r.clean(), r.summary());
        }

        @Test
        @DisplayName("tool rounds and calls of other scenarios are ignored")
        void foreignCallsIgnored() {
            Report r = check(List.of(ok("a", 0, 10)), List.of("a"),
                    List.of(call("a", "", 1, 2), new LlmCall("a", "zzz", "tool", 1.5, 1.8), call("elsewhere", "", 1, 5)));
            assertTrue(r.clean(), r.summary());
        }

        @Test
        @DisplayName("the mock's 80-character truncation of the previous message still matches")
        void truncatedPrevious() {
            String longInput = "x".repeat(100);
            Report r = check(List.of(ok(longInput, 0, 10), ok("b", 11, 20)), List.of(longInput, "b"),
                    List.of(call(longInput, "", 1, 2), call("b", longInput.substring(0, 80), 12, 13)));
            assertTrue(r.clean(), r.summary());
        }
    }

    @Test
    @DisplayName("an input sent twice is a bug in the suite, not in the cluster")
    void duplicateInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> check(List.of(ok("a", 0, 1), ok("a", 2, 3)), List.of(), List.of()));
    }

    @Test
    @DisplayName("the summary names every kind found")
    void summaryNamesKinds() {
        Report r = check(List.of(ok("a", 0, 10), ok("b", 11, 20)), List.of("b", "b"), List.of());
        String s = r.summary();
        assertTrue(s.contains("LOST") && s.contains("DUPLICATED"), s);
    }
}
