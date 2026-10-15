/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.MutableTestClock;
import ai.labs.eddi.configs.shared.TurnDeadline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ToolLoopRunner — tool timeout inside a turn deadline")
class ToolLoopRunnerDeadlineTest {

    private final MutableTestClock clock = new MutableTestClock();

    private TurnDeadline deadline(long budgetMs, long reserveMs) {
        return TurnDeadline.of(clock, clock.millis(), budgetMs, reserveMs);
    }

    @Test
    @DisplayName("a tool timeout longer than the budget left after the reserve is shortened to it")
    void clampedToTheRemainingBudget() {
        assertEquals(4_000, ToolLoopRunner.deadlineBoundedToolTimeoutMs(30_000, deadline(5_000, 1_000)));
    }

    @Test
    @DisplayName("a tool timeout already within the budget is kept")
    void shorterTimeoutIsKept() {
        assertEquals(2_000, ToolLoopRunner.deadlineBoundedToolTimeoutMs(2_000, deadline(60_000, 1_500)));
    }

    @Test
    @DisplayName("when only the reserve is left the tool gets what is left, never less than 1 ms")
    void reserveOnlyFallsBackToWhatIsLeft() {
        assertEquals(1_200, ToolLoopRunner.deadlineBoundedToolTimeoutMs(30_000, deadline(1_200, 1_000)));
    }

    @Test
    @DisplayName("a non-positive (unbounded) tool timeout gets the whole remaining budget, not 1 ms")
    void unboundedToolGetsTheBudget() {
        assertEquals(4_000, ToolLoopRunner.deadlineBoundedToolTimeoutMs(-1, deadline(5_000, 1_000)));
        assertEquals(4_000, ToolLoopRunner.deadlineBoundedToolTimeoutMs(0, deadline(5_000, 1_000)));
    }
}
