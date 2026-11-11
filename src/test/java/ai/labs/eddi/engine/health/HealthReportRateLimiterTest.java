/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthReportRateLimiter — per link, all reports, unknown tokens")
class HealthReportRateLimiterTest {

    @Test
    @DisplayName("a link gets 30 reads a minute, refilled continuously; another link has its own bucket")
    void perLink() {
        AtomicLong clock = new AtomicLong(0);
        HealthReportRateLimiter limiter = new HealthReportRateLimiter(clock::get);
        for (int i = 0; i < HealthReportRateLimiter.PER_LINK_PER_MINUTE; i++) {
            assertTrue(limiter.tryLink("h1"), "read " + i);
        }
        assertFalse(limiter.tryLink("h1"));
        assertTrue(limiter.tryLink("h2"));
        clock.addAndGet(2_000_000_000L); // two seconds: one token back
        assertTrue(limiter.tryLink("h1"));
        assertFalse(limiter.tryLink("h1"));
    }

    @Test
    @DisplayName("all reports share 300 a minute; unknown tokens 60")
    void globalAndMisses() {
        AtomicLong clock = new AtomicLong(0);
        HealthReportRateLimiter limiter = new HealthReportRateLimiter(clock::get);
        for (int i = 0; i < HealthReportRateLimiter.GLOBAL_PER_MINUTE; i++) {
            assertTrue(limiter.tryRequest());
        }
        assertFalse(limiter.tryRequest());
        for (int i = 0; i < HealthReportRateLimiter.MISSES_PER_MINUTE; i++) {
            assertTrue(limiter.tryMiss());
        }
        assertFalse(limiter.tryMiss());
        clock.addAndGet(60_000_000_000L);
        assertTrue(limiter.tryRequest());
        assertTrue(limiter.tryMiss());
    }
}
