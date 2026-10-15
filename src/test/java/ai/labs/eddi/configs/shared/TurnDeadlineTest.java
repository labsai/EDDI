/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurnDeadline")
class TurnDeadlineTest {

    @Test
    @DisplayName("remaining time follows the clock, and the reserve is set aside")
    void remainingFollowsTheClock() {
        var clock = new MutableTestClock();
        var deadline = TurnDeadline.of(clock, clock.millis(), 10_000, 1_500L);

        assertEquals(10_000, deadline.remainingMs());
        assertEquals(8_500, deadline.remainingAfterReserveMs());
        clock.advance(7_000);
        assertEquals(3_000, deadline.remainingMs());
        assertEquals(1_500, deadline.remainingAfterReserveMs());
        assertFalse(deadline.isExpired());
        assertFalse(deadline.canFit(TurnDeadline.MIN_ATTEMPT_MS));
        clock.advance(3_000);
        assertTrue(deadline.isExpired());
    }

    @Test
    @DisplayName("the clock starts at the request's arrival, not at construction")
    void startsAtArrival() {
        var clock = new MutableTestClock();
        long arrival = clock.millis() - 4_000; // 4 s spent queued
        var deadline = TurnDeadline.of(clock, arrival, 10_000, 0L);
        assertEquals(6_000, deadline.remainingMs());
    }

    @Test
    @DisplayName("reserve defaults to 1500 ms when unset or negative, and is capped")
    void reserveDefaults() {
        var clock = new MutableTestClock();
        assertEquals(1_500, TurnDeadline.of(clock, clock.millis(), 5_000, null).reserveMs());
        assertEquals(1_500, TurnDeadline.of(clock, clock.millis(), 5_000, -1L).reserveMs());
        assertEquals(0, TurnDeadline.of(clock, clock.millis(), 5_000, 0L).reserveMs());
        assertEquals(TurnDeadline.MAX_RESERVE_MS, TurnDeadline.of(clock, clock.millis(), 5_000, 999_999L).reserveMs());
    }

    @Test
    @DisplayName("config alone, header alone, both (smaller wins), neither")
    void resolveBudget() {
        assertNull(TurnDeadline.resolveBudgetMs(null, null));
        assertEquals(55_000L, TurnDeadline.resolveBudgetMs(55_000L, null));
        assertEquals(20_000L, TurnDeadline.resolveBudgetMs(null, 20_000L), "a header alone enables a deadline");
        assertEquals(20_000L, TurnDeadline.resolveBudgetMs(55_000L, 20_000L), "the header can shorten the config");
        assertEquals(55_000L, TurnDeadline.resolveBudgetMs(55_000L, 90_000L), "the header can never extend the config");
    }

    @Test
    @DisplayName("a header-only budget is capped at the engine maximum")
    void headerOnlyIsCapped() {
        assertEquals(TurnDeadline.MAX_BUDGET_MS, TurnDeadline.resolveBudgetMs(null, Long.MAX_VALUE));
        assertEquals(TurnDeadline.MAX_BUDGET_MS, TurnDeadline.resolveBudgetMs(Long.MAX_VALUE, null));
    }

    @Test
    @DisplayName("non-positive configured/requested values count as unset")
    void nonPositiveIsUnset() {
        assertNull(TurnDeadline.resolveBudgetMs(0L, -5L));
        assertEquals(30_000L, TurnDeadline.resolveBudgetMs(30_000L, 0L));
        assertEquals(30_000L, TurnDeadline.resolveBudgetMs(-1L, 30_000L));
    }

    @Test
    @DisplayName("header parsing: valid values pass, junk and negatives are ignored")
    void headerParsing() {
        assertEquals(45_000L, TurnDeadline.parseHeader("45000"));
        assertEquals(45_000L, TurnDeadline.parseHeader("  45000 "));
        assertNull(TurnDeadline.parseHeader(null));
        assertNull(TurnDeadline.parseHeader(""));
        assertNull(TurnDeadline.parseHeader("   "));
        assertNull(TurnDeadline.parseHeader("abc"));
        assertNull(TurnDeadline.parseHeader("12.5"));
        assertNull(TurnDeadline.parseHeader("-100"));
        assertNull(TurnDeadline.parseHeader("0"));
        assertNull(TurnDeadline.parseHeader("99999999999999999999999"));
    }

    @Test
    @DisplayName("forTurn: null when nobody asks for a deadline")
    void forTurn() {
        var clock = new MutableTestClock();
        assertNull(TurnDeadline.forTurn(clock, clock.millis(), null, null, null));
        var deadline = TurnDeadline.forTurn(clock, clock.millis(), 55_000L, 1_000L, 10_000L);
        assertNotNull(deadline);
        assertEquals(10_000, deadline.remainingMs());
        assertEquals(1_000, deadline.reserveMs());
    }
}
